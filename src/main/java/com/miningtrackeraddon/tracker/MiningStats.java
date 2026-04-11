package com.miningtrackeraddon.tracker;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.stream.Collectors;
import net.minecraft.client.MinecraftClient;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import com.miningtrackeraddon.config.Configs;
import com.miningtrackeraddon.config.Configs.ProjectEntry;
import com.miningtrackeraddon.config.FeatureToggle;
import com.miningtrackeraddon.storage.SessionData;
import com.miningtrackeraddon.storage.SessionHistory;
import com.miningtrackeraddon.storage.WorldSessionContext;
import com.miningtrackeraddon.MiningTrackerAddon;
import com.miningtrackeraddon.sync.CloudSyncManager;
import com.miningtrackeraddon.sync.DigsSyncManager;
import com.miningtrackeraddon.sync.ScoreboardSourceResolver;
import com.miningtrackeraddon.sync.SyncQueueManager;
import com.miningtrackeraddon.util.UiFormat;

public final class MiningStats
{
    private static final long ONE_HOUR_MS = 3_600_000L;
    private static final long ONE_MINUTE_MS = 60_000L;
    private static final long MIN_SYNCED_SESSION_DURATION_MS = 15L * 60L * 1000L;
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
    private static long sessionStartTotalMined;
    private static long pausedSessionMinedOffset;
    private static long lastPersistedTotalMinedMs;

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
        touchCurrentWorldStats(System.currentTimeMillis());

        resetDailyProgressIfNeeded();
        if (FeatureToggle.TWEAK_CARRY_GOAL_PROGRESS.getBooleanValue() == false)
        {
            Configs.dailyProgress = 0L;
            Configs.dailyGoalLastResetMs = System.currentTimeMillis();
            Configs.saveToFile();
        }

        GoalNotificationManager.clear();
        CloudSyncManager.syncHeartbeat();
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
        if (shouldPersistSession(currentSession))
        {
            SessionHistory.save(currentSession);
        }

        SessionData finished = currentSession;
        if (sessionActive && shouldPersistSession(finished))
        {
            CloudSyncManager.syncFinishedSession(finished);
        }
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
        long now = System.currentTimeMillis();
        boolean authoritativeMode = DigsSyncManager.hasAuthoritativeTotalDigs();
        long beforeLifetime = Configs.totalBlocksMined;
        Configs.WorldStatsEntry beforeWorldStats = getCurrentWorldStats();
        long beforeSourceTotal = beforeWorldStats == null ? 0L : Math.max(0L, beforeWorldStats.totalBlocks);
        long beforeSession = Math.max(0L, currentSession.totalBlocks);

        // Realtime block tracking must always advance local counters.
        // Authoritative scoreboard sync is used for reconciliation, not suppression.
        Configs.totalBlocksMined++;
        Configs.WorldStatsEntry worldStats = touchCurrentWorldStats(now);
        worldStats.totalBlocks++;

        resetDailyProgressIfNeeded();
        long previousDaily = Configs.dailyProgress;
        Configs.dailyProgress++;
        GoalNotificationManager.onGoalProgressChanged(previousDaily, getDailyGoalProgress());

        ProjectEntry active = Configs.getActiveProject();
        if (active != null)
        {
            active.progress++;
        }

        if (sessionActive && sessionPaused == false)
        {
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
        }

        CloudSyncManager.onBlockMined(now);

        Configs.WorldStatsEntry afterWorldStats = getCurrentWorldStats();
        long afterSourceTotal = afterWorldStats == null ? beforeSourceTotal : Math.max(0L, afterWorldStats.totalBlocks);
        debugAttribution("manual-block",
                beforeSourceTotal,
                afterSourceTotal,
                Math.max(0L, afterSourceTotal - beforeSourceTotal));
        if (Configs.Generic.WEBSITE_SYNC_DEBUG.getBooleanValue())
        {
            WorldSessionContext.WorldInfo world = WorldSessionContext.getCurrentWorldInfo();
            MiningTrackerAddon.LOGGER.info(
                    "[AET_DEBUG] block-mined worldKey={} worldName={} authoritative={} sessionActive={} sessionBefore={} sessionAfter={} lifetimeBefore={} lifetimeAfter={}",
                    world.id(),
                    world.displayName(),
                    authoritativeMode,
                    sessionActive,
                    beforeSession,
                    currentSession.totalBlocks,
                    beforeLifetime,
                    Configs.totalBlocksMined
            );
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
        sessionStartTotalMined = Math.max(0L, getCurrentSourceTotalMined());
        pausedSessionMinedOffset = 0L;
    }

    public static void startNewSession()
    {
        resetSession();
        sessionActive = true;
        sessionStartTotalMined = getCurrentSourceTotalMined();
        if (Configs.Generic.WEBSITE_SYNC_DEBUG.getBooleanValue())
        {
            WorldSessionContext.WorldInfo world = WorldSessionContext.getCurrentWorldInfo();
            MiningTrackerAddon.LOGGER.info(
                    "[AET_DEBUG] session-start worldKey={} worldName={} sessionStartSourceTotal={} lifetime={}",
                    world.id(),
                    world.displayName(),
                    sessionStartTotalMined,
                    Configs.totalBlocksMined
            );
        }
        CloudSyncManager.syncHeartbeat();
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

        CloudSyncManager.syncHeartbeat();

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
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.world != null && client.player != null)
        {
            WorldSessionContext.update(client);
        }

        long now = System.currentTimeMillis();
        if (now - lastDailyResetCheckMs >= 1_000L)
        {
            lastDailyResetCheckMs = now;
            resetDailyProgressIfNeeded();
        }

        CloudSyncManager.onClientTick(now);
        DigsSyncManager.onClientTick(now);
        SyncQueueManager.onClientTick(now);
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
        return Configs.totalBlocksMined;
    }

    public static long getCurrentSourceTotalMined()
    {
        Configs.WorldStatsEntry worldStats = getCurrentWorldStats();
        if (worldStats == null)
        {
            return 0L;
        }
        return Math.max(0L, worldStats.totalBlocks);
    }

    public static long getSessionTotal()
    {
        return getSessionBlocksMined();
    }

    public static long getSessionBlocksMined()
    {
        return Math.max(0L, currentSession.totalBlocks);
    }

    public static void applyAeternumTotalMined(long totalDigs, long now)
    {
        if (totalDigs < 0L)
        {
            return;
        }

        Configs.WorldStatsEntry worldStats = touchCurrentWorldStats(now);
        long previousSourceTotal = Math.max(0L, worldStats.totalBlocks);
        if (totalDigs < previousSourceTotal)
        {
            long correction = previousSourceTotal - totalDigs;
            if (correction <= 100L)
            {
                worldStats.totalBlocks = totalDigs;
                Configs.totalBlocksMined = Math.max(0L, Configs.totalBlocksMined - correction);
                if (sessionActive)
                {
                    sessionStartTotalMined = Math.max(0L, sessionStartTotalMined - correction);
                    currentSession.totalBlocks = Math.max(0L, currentSession.totalBlocks - correction);
                }
                debugAttribution("authoritative-correction", previousSourceTotal, worldStats.totalBlocks, 0L);
                return;
            }
            return;
        }

        long delta = Math.max(0L, totalDigs - previousSourceTotal);
        worldStats.totalBlocks = totalDigs;
        if (delta > 0L)
        {
            Configs.totalBlocksMined += delta;
            // Authoritative scoreboard deltas are the live mining update path on some servers.
            // Trigger sync from this authoritative path as well.
            CloudSyncManager.onBlockMined(now);
        }

        if (sessionActive)
        {
            if (sessionStartTotalMined <= 0L)
            {
                sessionStartTotalMined = previousSourceTotal;
            }

            if (sessionPaused && delta > 0L)
            {
                pausedSessionMinedOffset += delta;
            }

            long sessionTotal = Math.max(0L, totalDigs - sessionStartTotalMined - pausedSessionMinedOffset);
            long sessionDelta = Math.max(0L, sessionTotal - currentSession.totalBlocks);
            currentSession.totalBlocks = sessionTotal;
            currentSession.endTimeMs = now;

            if (sessionPaused == false && sessionDelta > 0L)
            {
                currentSession.recordMinedAmount(getActiveElapsedMs(now), sessionDelta);
                currentSession.peakBlocksPerHour = Math.max(currentSession.peakBlocksPerHour, getEstimatedBlocksPerHourAt(now));
            }
        }

        debugAttribution("authoritative-update", previousSourceTotal, worldStats.totalBlocks, delta);

        if (now - lastPersistedTotalMinedMs >= 5_000L)
        {
            Configs.saveToFile();
            lastPersistedTotalMinedMs = now;
        }
    }

    public static void bootstrapSourceTotalFromScoreboard(long scoreboardPlayerTotal, String scoreboardSourceName, long now)
    {
        if (scoreboardPlayerTotal <= 0L)
        {
            return;
        }

        WorldSessionContext.WorldInfo worldInfo = WorldSessionContext.getCurrentWorldInfo();
        if (sourceMatchesCurrentWorld(worldInfo, scoreboardSourceName) == false)
        {
            if (Configs.Generic.WEBSITE_SYNC_DEBUG.getBooleanValue())
            {
                MiningTrackerAddon.LOGGER.info(
                        "[AET_DEBUG] scoreboard-bootstrap-skipped worldKey={} worldName={} scoreboardSourceName={}",
                        worldInfo.id(),
                        worldInfo.displayName(),
                        scoreboardSourceName
                );
            }
            return;
        }

        Configs.WorldStatsEntry worldStats = touchCurrentWorldStats(now);
        long sourceBefore = Math.max(0L, worldStats.totalBlocks);
        long lifetimeBefore = Math.max(0L, Configs.totalBlocksMined);

        if (scoreboardPlayerTotal <= sourceBefore)
        {
            return;
        }

        long delta = scoreboardPlayerTotal - sourceBefore;
        worldStats.totalBlocks = scoreboardPlayerTotal;
        Configs.totalBlocksMined += delta;

        // Bootstrap should not retroactively inflate session progress.
        if (sessionActive)
        {
            sessionStartTotalMined += delta;
        }

        if (Configs.Generic.WEBSITE_SYNC_DEBUG.getBooleanValue())
        {
            String sourceKey = ScoreboardSourceResolver.sourceKey(
                    worldInfo.displayName(),
                    worldInfo
            );
            String sourceDisplay = ScoreboardSourceResolver.displayName(
                    worldInfo.displayName(),
                    worldInfo
            );
            MiningTrackerAddon.LOGGER.info(
                    "[AET_DEBUG] scoreboard-bootstrap-applied sourceKey={} sourceName={} scoreboardPlayerTotal={} sourceBefore={} sourceAfter={} lifetimeBefore={} lifetimeAfter={}",
                    sourceKey,
                    sourceDisplay,
                    scoreboardPlayerTotal,
                    sourceBefore,
                    worldStats.totalBlocks,
                    lifetimeBefore,
                    Configs.totalBlocksMined
            );
        }
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

    private static Configs.WorldStatsEntry touchCurrentWorldStats(long now)
    {
        WorldSessionContext.WorldInfo worldInfo = WorldSessionContext.getCurrentWorldInfo();
        Configs.WorldStatsEntry entry = Configs.getOrCreateWorldStats(
                worldInfo.id(),
                worldInfo.displayName(),
                worldInfo.kind(),
                worldInfo.host());
        entry.lastSeenAt = now;
        return entry;
    }

    private static Configs.WorldStatsEntry getCurrentWorldStats()
    {
        WorldSessionContext.WorldInfo worldInfo = WorldSessionContext.getCurrentWorldInfo();
        for (Configs.WorldStatsEntry entry : Configs.WORLD_STATS)
        {
            if (worldInfo.id().equals(entry.worldId))
            {
                return entry;
            }
        }

        return null;
    }

    private static boolean sourceMatchesCurrentWorld(WorldSessionContext.WorldInfo worldInfo, String scoreboardSourceName)
    {
        if (worldInfo == null)
        {
            return false;
        }

        String currentKey = ScoreboardSourceResolver.sourceKey(worldInfo.displayName(), worldInfo);
        WorldSessionContext.WorldInfo scoreboardInfo = new WorldSessionContext.WorldInfo(
                worldInfo.id(),
                scoreboardSourceName == null || scoreboardSourceName.isBlank() ? worldInfo.displayName() : scoreboardSourceName,
                worldInfo.kind(),
                worldInfo.host()
        );
        String scoreboardKey = ScoreboardSourceResolver.sourceKey(scoreboardInfo.displayName(), scoreboardInfo);
        return currentKey.equalsIgnoreCase(scoreboardKey);
    }

    private static void debugAttribution(String reason, long beforeSourceTotal, long afterSourceTotal, long delta)
    {
        if (Configs.Generic.WEBSITE_SYNC_DEBUG.getBooleanValue() == false)
        {
            return;
        }

        WorldSessionContext.WorldInfo world = WorldSessionContext.getCurrentWorldInfo();
        MiningTrackerAddon.LOGGER.info(
                "[AET_DEBUG] source-update reason={} worldKey={} worldName={} sessionActive={} sessionBlocks={} sourceBefore={} sourceAfter={} delta={} lifetime={}",
                reason,
                world.id(),
                world.displayName(),
                sessionActive,
                currentSession.totalBlocks,
                beforeSourceTotal,
                afterSourceTotal,
                delta,
                Configs.totalBlocksMined
        );
    }

    private static boolean shouldPersistSession(SessionData session)
    {
        return session != null
                && session.totalBlocks > 0L
                && session.getDurationMs() >= MIN_SYNCED_SESSION_DURATION_MS;
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

