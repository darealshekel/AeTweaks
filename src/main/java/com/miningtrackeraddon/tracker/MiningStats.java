package com.miningtrackeraddon.tracker;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.stream.Collectors;

import com.miningtrackeraddon.config.Configs;
import com.miningtrackeraddon.config.Configs.ProjectEntry;
import com.miningtrackeraddon.config.FeatureToggle;
import com.miningtrackeraddon.storage.SessionData;
import com.miningtrackeraddon.storage.SessionHistory;
import com.miningtrackeraddon.storage.WorldSessionContext;
import com.miningtrackeraddon.sync.MmmSyncManager;

import net.minecraft.block.Block;
import net.minecraft.registry.Registries;

public final class MiningStats
{
    private static final long ONE_DAY_MS = 86_400_000L;
    private static final long ONE_HOUR_MS = 3_600_000L;
    private static final long ONE_MINUTE_MS = 60_000L;
    private static final long STREAK_GAP_MS = 5_000L;

    private static final Deque<Long> MINE_EVENTS = new ArrayDeque<>();
    private static SessionData currentSession = new SessionData(System.currentTimeMillis());
    private static String currentWorldId = "default";

    private static long streakStartMs;
    private static long lastMineMs;

    private MiningStats()
    {
    }

    public static void startWorldSession(String worldId)
    {
        currentWorldId = worldId == null || worldId.isBlank() ? "default" : worldId;
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
        resetDailyProgressIfNeeded();
        currentSession.endTimeMs = System.currentTimeMillis();
        if (currentSession.totalBlocks > 0)
        {
            SessionHistory.save(currentSession);
        }

        SessionData finished = currentSession;
        resetSession();
        Configs.saveToFile();
        GoalNotificationManager.clear();
        return finished;
    }

    public static void recordBlockMined(Block block)
    {
        long now = System.currentTimeMillis();
        MINE_EVENTS.addLast(now);
        pruneOldEvents(now);

        currentSession.totalBlocks++;
        currentSession.endTimeMs = now;
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

        MmmSyncManager.onBlockMined();
    }

    public static void resetSession()
    {
        MINE_EVENTS.clear();
        currentSession = new SessionData(System.currentTimeMillis());
        streakStartMs = 0L;
        lastMineMs = 0L;
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
        return getEstimatedBlocksPerHourAt(now);
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

    public static long getSessionDurationMs()
    {
        return Math.max(0L, System.currentTimeMillis() - currentSession.startTimeMs);
    }

    public static String getEstimatedTimeToDailyGoal()
    {
        GoalProgress progress = getDailyGoalProgress();
        if (progress.enabled() == false || progress.target() <= 0 || progress.current() >= progress.target())
        {
            return "Complete";
        }

        int blocksPerHour = getEstimatedBlocksPerHour();
        if (blocksPerHour <= 0)
        {
            return "Calculating...";
        }

        long remainingBlocks = progress.target() - progress.current();
        long seconds = (remainingBlocks * 3600L) / blocksPerHour;
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
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
        currentSession.endTimeMs = System.currentTimeMillis();
        return currentSession;
    }

    public static void setDailyProgress(long value)
    {
        Configs.dailyProgress = Math.max(0L, value);
        Configs.saveToFile();
    }

    public static void setActiveProjectProgress(long value)
    {
        ProjectEntry activeProject = Configs.getActiveProject();
        if (activeProject != null)
        {
            activeProject.progress = Math.max(0L, value);
            Configs.saveToFile();
        }
    }

    public static String getCurrentWorldId()
    {
        return currentWorldId != null ? currentWorldId : WorldSessionContext.getCurrentWorldId();
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
        if (Configs.dailyGoalLastResetMs <= 0L)
        {
            Configs.dailyGoalLastResetMs = now;
            Configs.saveToFile();
            return;
        }

        if (now - Configs.dailyGoalLastResetMs >= ONE_DAY_MS)
        {
            Configs.dailyProgress = 0L;
            Configs.dailyGoalLastResetMs = now;
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
}
