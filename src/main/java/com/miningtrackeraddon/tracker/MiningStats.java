package com.miningtrackeraddon.tracker;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.stream.Collectors;

import com.miningtrackeraddon.config.Configs;
import com.miningtrackeraddon.config.Configs.ProjectEntry;
import com.miningtrackeraddon.config.FeatureToggle;
import com.miningtrackeraddon.storage.SessionData;
import com.miningtrackeraddon.storage.SessionHistory;
import com.miningtrackeraddon.storage.WorldSessionContext;
import com.miningtrackeraddon.util.UiFormat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

public final class MiningStats
{
    private static final long ONE_HOUR_MS = 3_600_000L;
    private static final long ONE_MINUTE_MS = 60_000L;
    private static final long STREAK_GAP_MS = 5_000L;
    private static final boolean SMART_ETA_ENABLED = true;

    private static final Deque<Long> MINE_EVENTS = new ArrayDeque<>();
    private static final MiningPaceEstimator PACE_ESTIMATOR = new MiningPaceEstimator();
    private static SessionData currentSession = new SessionData(System.currentTimeMillis());
    private static String currentWorldId = "default";
    private static boolean sessionActive = true;
    private static boolean sessionPaused;
    private static long pausedAtMs;
    private static long pausedAccumulatedMs;

    private static long streakStartMs;
    private static long lastMineMs;
    private static long lastDailyResetCheckMs;

    private MiningStats()
    {
    }

    public static void startWorldSession(String worldId)
    {
        currentWorldId = worldId == null || worldId.isBlank() ? "default" : worldId;
        sessionActive = false;
        resetSession();

        resetDailyProgressIfNeeded();
        if (FeatureToggle.TWEAK_CARRY_GOAL_PROGRESS.getBooleanValue() == false)
        {
            Configs.dailyProgress = 0L;
            Configs.dailyGoalLastResetMs = System.currentTimeMillis();
            Configs.saveToFile();
        }

        GoalNotificationManager.clear();
    }

    public static SessionData finaliseSession()
    {
        if (sessionPaused)
        {
            pausedAccumulatedMs += Math.max(0L, System.currentTimeMillis() - pausedAtMs);
            pausedAtMs = 0L;
            sessionPaused = false;
        }

        resetDailyProgressIfNeeded();
        currentSession.endTimeMs = System.currentTimeMillis() - pausedAccumulatedMs;
        if (currentSession.totalBlocks > 0)
        {
            SessionHistory.save(currentSession);
        }

        SessionData finished = currentSession;
        sessionActive = false;
        resetSession();
        Configs.saveToFile();
        GoalNotificationManager.clear();
        return finished;
    }

    public static void recordBlockMined(Block block)
    {
        recordBlockMined(block, null, null);
    }

    public static void recordBlockMined(Block block, BlockPos pos, BlockState previousState)
    {
        if (sessionActive == false || sessionPaused)
        {
            return;
        }

        long now = System.currentTimeMillis();
        MINE_EVENTS.addLast(now);
        PACE_ESTIMATOR.recordBlock(now);
        pruneOldEvents(now);

        currentSession.totalBlocks++;
        currentSession.endTimeMs = now;
        currentSession.recordMineEvent(getActiveElapsedMs(now));
        currentSession.peakBlocksPerHour = Math.max(currentSession.peakBlocksPerHour, getEstimatedBlocksPerHourAt(now));

        if (lastMineMs == 0L || now - lastMineMs > STREAK_GAP_MS)
        {
            streakStartMs = now;
        }
        lastMineMs = now;
        currentSession.bestStreakSeconds = Math.max(currentSession.bestStreakSeconds, (now - streakStartMs) / 1000L);

        if (block != null)
        {
            String key = Registries.BLOCK.getId(block).toString();
            currentSession.blockBreakdown.merge(key, 1L, Long::sum);
        }

        resetDailyProgressIfNeeded();
        long previousDaily = Configs.dailyProgress;
        Configs.dailyProgress++;
        GoalNotificationManager.onGoalProgressChanged(previousDaily, getDailyGoalProgress());

        ProjectEntry active = Configs.getActiveProject();
        if (active != null)
        {
            active.progress++;
        }
    }

    public static void resetSession()
    {
        MINE_EVENTS.clear();
        currentSession = new SessionData(System.currentTimeMillis());
        PACE_ESTIMATOR.reset(currentSession.startTimeMs);
        streakStartMs = 0L;
        lastMineMs = 0L;
        pausedAtMs = 0L;
        pausedAccumulatedMs = 0L;
        sessionPaused = false;
    }

    public static void startNewSession()
    {
        resetSession();
        sessionActive = true;
    }

    public static boolean toggleSession()
    {
        if (sessionActive)
        {
            finaliseSession();
            return false;
        }

        startNewSession();
        return true;
    }

    public static boolean togglePauseSession()
    {
        if (sessionActive == false)
        {
            return false;
        }

        long now = System.currentTimeMillis();
        if (sessionPaused)
        {
            pausedAccumulatedMs += Math.max(0L, now - pausedAtMs);
            pausedAtMs = 0L;
            sessionPaused = false;
        }
        else
        {
            pausedAtMs = now;
            sessionPaused = true;
        }

        return sessionPaused;
    }

    public static boolean isSessionActive()
    {
        return sessionActive;
    }

    public static boolean isSessionPaused()
    {
        return sessionActive && sessionPaused;
    }

    public static void onClientTick()
    {
        long now = System.currentTimeMillis();
        if (now - lastDailyResetCheckMs >= 1_000L)
        {
            lastDailyResetCheckMs = now;
            resetDailyProgressIfNeeded();
        }
    }

    public static int getBlocksPerHour()
    {
        pruneOldEvents(System.currentTimeMillis());
        return MINE_EVENTS.size();
    }

    public static int getEstimatedBlocksPerHour()
    {
        long now = System.currentTimeMillis();
        pruneOldEvents(now);
        return (int) Math.round(getPredictionSnapshot(now).blocksPerHour());
    }

    public static boolean hasActualBlocksPerHour()
    {
        return getSessionDurationMs() >= ONE_HOUR_MS;
    }

    public static int getActualBlocksPerHour()
    {
        return currentSession.getAverageBlocksPerHour();
    }

    private static int getEstimatedBlocksPerHourAt(long now)
    {
        if (SMART_ETA_ENABLED)
        {
            return (int) Math.round(getPredictionSnapshot(now).blocksPerHour());
        }

        if (MINE_EVENTS.isEmpty())
        {
            return 0;
        }
        long windowStart = Math.max(currentSession.startTimeMs, now - ONE_MINUTE_MS);
        int recentCount = 0;

        for (Long timestamp : MINE_EVENTS)
        {
            if (timestamp >= windowStart)
            {
                recentCount++;
            }
        }

        if (recentCount <= 0)
        {
            return 0;
        }

        long elapsedMs = Math.max(1_000L, now - windowStart);
        return (int) Math.round((recentCount * (double) ONE_HOUR_MS) / elapsedMs);
    }

    public static long getTotalMined()
    {
        return currentSession.totalBlocks;
    }

    public static String getSessionDurationClock()
    {
        long totalSeconds = Math.max(0L, getSessionDurationMs() / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    public static String getDailyResetCountdownClock()
    {
        resetDailyProgressIfNeeded();

        if (FeatureToggle.TWEAK_DAILY_AUTO_RESET.getBooleanValue() == false)
        {
            return "Disabled";
        }

        ZoneId zoneId = ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        ZonedDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(zoneId);
        long remainingMs = Math.max(0L, nextMidnight.toInstant().toEpochMilli() - now.toInstant().toEpochMilli());

        long totalSeconds = remainingMs / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    public static long getSessionDurationMs()
    {
        if (sessionActive == false)
        {
            return 0L;
        }

        long now = sessionPaused ? pausedAtMs : System.currentTimeMillis();
        return Math.max(0L, now - currentSession.startTimeMs - pausedAccumulatedMs);
    }

    private static long getActiveElapsedMs(long now)
    {
        return Math.max(0L, now - currentSession.startTimeMs - pausedAccumulatedMs);
    }

    public static String getEstimatedTimeToDailyGoal()
    {
        GoalProgress progress = getDailyGoalProgress();
        if (progress.enabled() == false || progress.target() <= 0 || progress.current() >= progress.target())
        {
            return "Complete";
        }

        PredictionSnapshot prediction = getPredictionSnapshot(System.currentTimeMillis());
        int blocksPerHour = (int) Math.round(prediction.blocksPerHour());
        if (blocksPerHour <= 0)
        {
            return prediction.paceState() == MiningPaceEstimator.PaceState.PAUSED ? "Paused" : "Calculating...";
        }

        long remainingBlocks = progress.target() - progress.current();
        long seconds = Math.max(1L, Math.round((remainingBlocks * 3600.0D) / blocksPerHour));
        return UiFormat.formatDuration(seconds);
    }

    public static GoalProgress getDailyGoalProgress()
    {
        resetDailyProgressIfNeeded();
        return new GoalProgress("Daily Goal", FeatureToggle.TWEAK_DAILY_GOAL.getBooleanValue(), Configs.dailyProgress, Configs.Generic.DAILY_GOAL.getIntegerValue());
    }

    public static ProjectProgress getActiveProjectProgress()
    {
        ProjectEntry activeProject = Configs.getActiveProject();
        if (activeProject == null)
        {
            return new ProjectProgress("No Project", 0L);
        }
        return new ProjectProgress(activeProject.name, activeProject.progress);
    }

    public static SessionData getCurrentSession()
    {
        if (sessionActive == false)
        {
            currentSession.endTimeMs = currentSession.startTimeMs;
            return currentSession;
        }

        long now = sessionPaused ? pausedAtMs : System.currentTimeMillis();
        currentSession.endTimeMs = now - pausedAccumulatedMs;
        return currentSession;
    }

    public static void setDailyProgress(long value)
    {
        Configs.dailyProgress = Math.max(0L, value);
        GoalNotificationManager.clear();
        Configs.saveToFile();
    }

    public static void setActiveProjectProgress(long value)
    {
        ProjectEntry activeProject = Configs.getActiveProject();
        if (activeProject != null)
        {
            activeProject.progress = Math.max(0L, value);
            GoalNotificationManager.clear();
            Configs.saveToFile();
        }
    }

    public static String getCurrentWorldId()
    {
        return currentWorldId != null ? currentWorldId : WorldSessionContext.getCurrentWorldId();
    }

    public static PredictionSnapshot getPredictionSnapshot()
    {
        return getPredictionSnapshot(System.currentTimeMillis());
    }

    public static PredictionSnapshot getPredictionSnapshot(long now)
    {
        if (sessionActive == false)
        {
            return new PredictionSnapshot(0D, 0D, MiningPaceEstimator.PaceState.CALCULATING, 0D, 0D, 0D);
        }

        if (sessionPaused)
        {
            MiningRateSnapshot snapshot = PACE_ESTIMATOR.getSnapshot();
            return new PredictionSnapshot(
                    snapshot.predictedBlocksPerHour(),
                    snapshot.confidence(),
                    MiningPaceEstimator.PaceState.PAUSED,
                    snapshot.sessionRate(),
                    snapshot.rate60s(),
                    snapshot.rate5m());
        }

        if (SMART_ETA_ENABLED == false)
        {
            double naiveRate = getEstimatedBlocksPerHourAt(now);
            return new PredictionSnapshot(naiveRate, naiveRate > 0D ? 0.60D : 0D, naiveRate > 0D ? MiningPaceEstimator.PaceState.STABLE : MiningPaceEstimator.PaceState.CALCULATING, currentSession.getAverageBlocksPerHour(), naiveRate, naiveRate);
        }

        MiningRateSnapshot snapshot = PACE_ESTIMATOR.update(now, currentSession.totalBlocks, currentSession.startTimeMs, false);
        return new PredictionSnapshot(
                snapshot.predictedBlocksPerHour(),
                snapshot.confidence(),
                snapshot.paceState(),
                snapshot.sessionRate(),
                snapshot.rate60s(),
                snapshot.rate5m());
    }

    public static Map<String, Long> getSortedBreakdown(SessionData session)
    {
        return session.blockBreakdown.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (left, right) -> left, java.util.LinkedHashMap::new));
    }

    private static void resetDailyProgressIfNeeded()
    {
        if (FeatureToggle.TWEAK_DAILY_AUTO_RESET.getBooleanValue() == false)
        {
            return;
        }

        long now = System.currentTimeMillis();
        ZoneId zoneId = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zoneId);

        if (Configs.dailyGoalLastResetMs <= 0L)
        {
            Configs.dailyGoalLastResetMs = now;
            Configs.saveToFile();
            return;
        }

        LocalDate lastResetDate = Instant.ofEpochMilli(Configs.dailyGoalLastResetMs).atZone(zoneId).toLocalDate();

        if (lastResetDate.isAfter(today))
        {
            Configs.dailyGoalLastResetMs = now;
            Configs.saveToFile();
            return;
        }

        if (lastResetDate.isBefore(today))
        {
            Configs.dailyProgress = 0L;
            Configs.dailyGoalLastResetMs = now;
            GoalNotificationManager.clear();
            Configs.saveToFile();
        }
    }

    private static void pruneOldEvents(long now)
    {
        long cutoff = now - ONE_HOUR_MS;
        while (MINE_EVENTS.isEmpty() == false && MINE_EVENTS.peekFirst() < cutoff)
        {
            MINE_EVENTS.pollFirst();
        }
    }

    public record GoalProgress(String label, boolean enabled, long current, long target)
    {
        public int getPercent()
        {
            return target <= 0 ? 0 : (int) Math.min(100, (current * 100) / target);
        }
    }

    public record ProjectProgress(String name, long blocksMined) {}

    public record PredictionSnapshot(double blocksPerHour, double confidence, MiningPaceEstimator.PaceState paceState, double sessionRate, double recentRate, double longRate)
    {
    }
}
