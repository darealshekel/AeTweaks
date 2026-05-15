package com.mmm.tracker;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.LinkedHashMap;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.WeekFields;
import java.util.stream.Collectors;
import net.minecraft.client.MinecraftClient;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import com.mmm.config.Configs;
import com.mmm.config.Configs.ProjectEntry;
import com.mmm.config.FeatureToggle;
import com.mmm.storage.SessionData;
import com.mmm.storage.SessionHistory;
import com.mmm.storage.WorldSessionContext;
import com.mmm.MMM;
import com.mmm.sync.CloudSyncManager;
import com.mmm.sync.DigsSyncManager;
import com.mmm.sync.ScoreboardSourceResolver;
import com.mmm.sync.SyncQueueManager;
import com.mmm.util.BlockBreakdownCatalog;
import com.mmm.util.MmmDebugLogger;
import com.mmm.util.UiFormat;

public final class MiningStats
{
    private static final long ONE_HOUR_MS = 3_600_000L;
    private static final long ONE_MINUTE_MS = 60_000L;
    private static final long MIN_SYNCED_SESSION_DURATION_MS = 10L * 60L * 1000L;
    private static final long MIN_SYNCED_SESSION_BLOCKS = 1_000L;
    private static final long STREAK_GAP_MS = 5_000L;
    private static final long AUTO_MINING_REQUIRED_MS = 10_000L;
    private static final long AUTO_MINING_GAP_MS = 2_000L;
    private static final long AUTO_PAUSE_IDLE_MS = 90_000L;
    private static final long FASTEST_100K_TARGET = 100_000L;
    private static final long TOTAL_MINED_PERSIST_INTERVAL_MS = 5_000L;
    private static final long BLOCK_MINED_DEBUG_LOG_INTERVAL_MS = 5_000L;
    private static final long SESSION_DEBUG_LOG_INTERVAL_MS = 30_000L;
    private static final long SCOREBOARD_BOOTSTRAP_SKIPPED_LOG_INTERVAL_MS = 10_000L;
    private static final long SCOREBOARD_BOOTSTRAP_APPLIED_LOG_INTERVAL_MS = 5_000L;
    private static final long SOURCE_UPDATE_DEBUG_LOG_INTERVAL_MS = 5_000L;
    private static final int TICKS_PER_SECOND = 20;
    private static final int BPH_WINDOW_TICKS = 72_000;
    private static final int BPS_UPDATE_INTERVAL_TICKS = 10;
    private static final int BPS_WINDOW_TICKS = 100;
    private static final ZoneId DAILY_RESET_ZONE = ZoneId.of("UTC");

    private static final Deque<Long> MINE_EVENTS = new ArrayDeque<>();
    private static final Deque<Long> FASTEST_100K_EVENT_TIMES = new ArrayDeque<>();
    private static final Deque<TickBlockCount> METRIC_TICK_COUNTS = new ArrayDeque<>();
    private static SessionData currentSession = new SessionData(System.currentTimeMillis());
    private static String currentWorldId = "default";
    private static boolean sessionActive = true;
    private static boolean sessionPaused;
    private static long pausedAtMs;
    private static long pausedAccumulatedMs;
    private static long sessionStartTotalMined;
    private static long pausedSessionMinedOffset;
    private static long lastPersistedTotalMinedMs;
    private static boolean session100kRecorded;
    private static long autoMiningStreakStartMs;
    private static long lastValidBlockMineMs;
    private static boolean sessionAutoPaused;
    private static long metricTickIndex;
    private static long lastBpsUpdateTick;
    private static long lastBphUpdateTick;
    private static long sessionActiveTicks;
    private static int currentTickBpsBlocks;
    private static int currentTickBphBlocks;
    private static double rollingBlocksPerSecond;
    private static double rollingBlocksPerHour;
    private static double displayedBlocksPerSecond;
    private static double displayedBlocksPerHour;
    private static Configs.BpsSmoothing lastBpsSmoothing = Configs.BpsSmoothing.FAST;

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
        resetRollingMetrics();
        MiningSpeedTracker.resetSession();
        touchCurrentWorldStats(System.currentTimeMillis());

        resetDailyProgressIfNeeded();
        resetPeriodStatsIfNeeded(System.currentTimeMillis());

        GoalNotificationManager.clear();
        CloudSyncManager.syncNow("world join");
    }

    public static SessionData finaliseSession()
    {
        if (sessionPaused)
        {
            pausedAccumulatedMs += Math.max(0L, System.currentTimeMillis() - pausedAtMs);
            pausedAtMs = 0L;
            sessionPaused = false;
            sessionAutoPaused = false;
        }

        resetDailyProgressIfNeeded();
        resetPeriodStatsIfNeeded(System.currentTimeMillis());
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
        resetRollingMetrics();
        MiningSpeedTracker.resetSession();
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
        if (BlockBreakdownCatalog.isValid(block) == false)
        {
            return;
        }

        handleAutoSessionOnValidMine(now);
        recordSuccessfulHarvestForRollingMetrics();

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
        recordCurrentWorldBlockBreakdown(worldStats, block, now);

        resetDailyProgressIfNeeded();
        long previousDaily = Configs.dailyProgress;
        Configs.dailyProgress++;
        recordPeriodBlocksMined(1L, now);
        recordFastest100kWindow(now);
        GoalNotificationManager.onGoalProgressChanged(previousDaily, getDailyGoalProgress());

        ProjectEntry active = Configs.getActiveProject();
        if (active != null)
        {
            active.progress++;
        }

        if (sessionPaused == false)
        {
            MINE_EVENTS.addLast(now);
            pruneOldEvents(now);
        }

        if (sessionActive && sessionPaused == false)
        {
            MiningValidationTracker.onBlockMined(pos, previousState, now);

            currentSession.totalBlocks++;
            currentSession.endTimeMs = now;
            currentSession.recordMineEvent(getActiveElapsedMs(now));
            recordFastest100kIfReached(now);

            if (lastMineMs == 0L || now - lastMineMs > STREAK_GAP_MS)
            {
                streakStartMs = now;
            }
            lastMineMs = now;
            currentSession.bestStreakSeconds = Math.max(currentSession.bestStreakSeconds, (now - streakStartMs) / 1000L);

            if (block != null)
            {
                String key = BlockBreakdownCatalog.blockId(block);
                currentSession.blockBreakdown.merge(key, 1L, Long::sum);
            }
        }

        CloudSyncManager.onBlockMined(now);

        if (now - lastPersistedTotalMinedMs >= TOTAL_MINED_PERSIST_INTERVAL_MS)
        {
            Configs.saveToFile();
            lastPersistedTotalMinedMs = now;
        }

        Configs.WorldStatsEntry afterWorldStats = getCurrentWorldStats();
        long afterSourceTotal = afterWorldStats == null ? beforeSourceTotal : Math.max(0L, afterWorldStats.totalBlocks);
        debugAttribution("manual-block",
                beforeSourceTotal,
                afterSourceTotal,
                Math.max(0L, afterSourceTotal - beforeSourceTotal));
        if (MmmDebugLogger.shouldLog("miningstats.block-mined", BLOCK_MINED_DEBUG_LOG_INTERVAL_MS))
        {
            WorldSessionContext.WorldInfo world = WorldSessionContext.getCurrentWorldInfo();
            MMM.LOGGER.info(
                    "[MMM_DEBUG] block-mined worldKey={} worldName={} authoritative={} sessionActive={} sessionBefore={} sessionAfter={} lifetimeBefore={} lifetimeAfter={}",
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
        MiningValidationTracker.resetSession(currentSession.startTimeMs);
        streakStartMs = 0L;
        lastMineMs = 0L;
        pausedAtMs = 0L;
        pausedAccumulatedMs = 0L;
        sessionPaused = false;
        sessionStartTotalMined = Math.max(0L, getCurrentSourceTotalMined());
        pausedSessionMinedOffset = 0L;
        session100kRecorded = false;
        autoMiningStreakStartMs = 0L;
        lastValidBlockMineMs = 0L;
        sessionAutoPaused = false;
        sessionActiveTicks = 0L;
    }

    public static void startNewSession()
    {
        resetSession();
        resetRollingMetrics();
        MiningSpeedTracker.resetSession();
        sessionActive = true;
        sessionStartTotalMined = getCurrentSourceTotalMined();
        WorldSessionContext.WorldInfo world = WorldSessionContext.getCurrentWorldInfo();
        MmmDebugLogger.info(
                "miningstats-session-start",
                SESSION_DEBUG_LOG_INTERVAL_MS,
                "[MMM_DEBUG] session-start worldKey={} worldName={} sessionStartSourceTotal={} lifetime={}",
                world.id(),
                world.displayName(),
                sessionStartTotalMined,
                Configs.totalBlocksMined);
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
            sessionAutoPaused = false;
            rollingBlocksPerHour = calculateSessionBph();
            rollingBlocksPerSecond = calculateRollingBps(lastBpsSmoothing);
            updateDisplayedRollingMetrics();
        }
        else
        {
            pausedAtMs = now;
            sessionPaused = true;
            sessionAutoPaused = false;
            freezeRollingMetrics();
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
        boolean hasMiningContext = client != null && client.world != null && client.player != null;
        if (hasMiningContext)
        {
            WorldSessionContext.update(client);
        }

        long now = System.currentTimeMillis();
        if (now - lastDailyResetCheckMs >= 1_000L)
        {
            lastDailyResetCheckMs = now;
            resetDailyProgressIfNeeded();
            resetPeriodStatsIfNeeded(now);
        }

        updateRollingMetrics(hasMiningContext);
        DigsSyncManager.onClientTick(now);
        CloudSyncManager.onClientTick(now);
        BlockBreakdownTracker.onClientTick(client, now);
        MiningValidationTracker.onClientTick(now);
        SyncQueueManager.onClientTick(now);
        maybeAutoPauseSession(now);
    }

    public static int getBlocksPerHour()
    {
        return getEstimatedBlocksPerHour();
    }

    public static int getEstimatedBlocksPerHour()
    {
        if (sessionActive == false)
        {
            return 0;
        }

        return SessionData.clampBlocksPerHour(Math.round(rollingBlocksPerHour));
    }

    public static double getEstimatedBlocksPerSecond()
    {
        if (sessionActive == false)
        {
            return 0D;
        }

        return Math.max(0D, Math.min(20D, rollingBlocksPerSecond));
    }

    public static int getDisplayedBlocksPerHour()
    {
        if (sessionActive == false)
        {
            return 0;
        }

        return SessionData.clampBlocksPerHour(Math.round(displayedBlocksPerHour));
    }

    public static double getDisplayedBlocksPerSecond()
    {
        if (sessionActive == false)
        {
            return 0D;
        }

        return Math.max(0D, Math.min(20D, displayedBlocksPerSecond));
    }

    public static boolean hasActualBlocksPerHour()
    {
        return getSessionDurationMs() >= ONE_HOUR_MS;
    }

    public static int getActualBlocksPerHour()
    {
        return currentSession.getAverageBlocksPerHour();
    }

    public static long getTotalMined()
    {
        return Configs.totalBlocksMined;
    }

    public static long getGlobalTotalMinedForDisplay()
    {
        long websiteTotal = Math.max(0L, Configs.websiteGlobalTotalBlocks);
        return websiteTotal > 0L ? websiteTotal : Math.max(0L, Configs.totalBlocksMined);
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

    public static void applyScoreboardTotalMined(long totalDigs, long now)
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
            worldStats.totalBlocks = totalDigs;
            worldStats.lastSeenAt = now;
            Configs.totalBlocksMined = Math.max(0L, Configs.totalBlocksMined - correction);
            if (sessionActive)
            {
                sessionStartTotalMined = Math.max(0L, sessionStartTotalMined - correction);
                currentSession.totalBlocks = Math.max(0L, currentSession.totalBlocks - correction);
            }
            debugAttribution("authoritative-correction", previousSourceTotal, worldStats.totalBlocks, 0L);
            Configs.saveToFile();
            return;
        }

        long delta = Math.max(0L, totalDigs - previousSourceTotal);
        worldStats.totalBlocks = totalDigs;
        worldStats.lastSeenAt = now;
        if (delta > 0L)
        {
            Configs.totalBlocksMined += delta;
            recordPeriodBlocksMined(delta, now);
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
                recordFastest100kIfReached(now);
            }
        }

        debugAttribution("authoritative-update", previousSourceTotal, worldStats.totalBlocks, delta);

        if (now - lastPersistedTotalMinedMs >= TOTAL_MINED_PERSIST_INTERVAL_MS)
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
            if (MmmDebugLogger.shouldLog("miningstats.scoreboard-bootstrap-skipped", SCOREBOARD_BOOTSTRAP_SKIPPED_LOG_INTERVAL_MS))
            {
                MMM.LOGGER.info(
                        "[MMM_DEBUG] scoreboard-bootstrap-skipped worldKey={} worldName={} scoreboardSourceName={}",
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

        if (MmmDebugLogger.shouldLog("miningstats.scoreboard-bootstrap-applied", SCOREBOARD_BOOTSTRAP_APPLIED_LOG_INTERVAL_MS))
        {
            String sourceKey = ScoreboardSourceResolver.sourceKey(
                    worldInfo.displayName(),
                    worldInfo
            );
            String sourceDisplay = ScoreboardSourceResolver.displayName(
                    worldInfo.displayName(),
                    worldInfo
            );
            MMM.LOGGER.info(
                    "[MMM_DEBUG] scoreboard-bootstrap-applied sourceKey={} sourceName={} scoreboardPlayerTotal={} sourceBefore={} sourceAfter={} lifetimeBefore={} lifetimeAfter={}",
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

    public static void applyMinecraftStatsBlockBreakdown(Map<String, Long> breakdown, long now)
    {
        Map<String, Long> sanitized = Configs.sanitizeBlockBreakdown(breakdown);
        if (sanitized.isEmpty())
        {
            return;
        }

        Configs.WorldStatsEntry worldStats = touchCurrentWorldStats(now);
        Map<String, Long> previous = Configs.sanitizeBlockBreakdown(worldStats.blockBreakdown);
        long sourceBefore = Math.max(0L, worldStats.totalBlocks);
        long statsTotal = sanitized.values().stream().mapToLong(Long::longValue).sum();
        boolean breakdownChanged = sanitized.equals(previous) == false
                || Configs.BLOCK_BREAKDOWN_SOURCE_MINECRAFT_STATS.equals(worldStats.blockBreakdownSource) == false;

        if (breakdownChanged)
        {
            worldStats.blockBreakdown = new LinkedHashMap<>(sanitized);
            worldStats.blockBreakdownSource = Configs.BLOCK_BREAKDOWN_SOURCE_MINECRAFT_STATS;
            worldStats.blockBreakdownUpdatedAtMs = now;
            worldStats.lastSeenAt = now;
        }

        if (statsTotal > sourceBefore)
        {
            long delta = statsTotal - sourceBefore;
            worldStats.totalBlocks = statsTotal;
            Configs.totalBlocksMined += delta;

            if (sessionActive)
            {
                sessionStartTotalMined += delta;
            }
        }

        if (breakdownChanged || statsTotal > sourceBefore)
        {
            Configs.saveToFile();
            CloudSyncManager.syncHeartbeat();
            debugAttribution("minecraft-stats-breakdown", sourceBefore, worldStats.totalBlocks, Math.max(0L, worldStats.totalBlocks - sourceBefore));
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

        ZonedDateTime now = ZonedDateTime.now(DAILY_RESET_ZONE);
        ZonedDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(DAILY_RESET_ZONE);
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

        int blocksPerHour = (int) Math.round(rollingBlocksPerHour);
        if (blocksPerHour <= 0)
        {
            return isSessionPaused() ? "Paused" : "Calculating...";
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

    public static long getDailyBlocksMined()
    {
        resetPeriodStatsIfNeeded(System.currentTimeMillis());
        return Math.max(0L, Configs.dailyBlocksMined);
    }

    public static String getDailyBlocksDate()
    {
        resetPeriodStatsIfNeeded(System.currentTimeMillis());
        return Configs.dailyBlocksDate == null ? "" : Configs.dailyBlocksDate;
    }

    public static long getWeeklyBlocksMined()
    {
        resetPeriodStatsIfNeeded(System.currentTimeMillis());
        return Math.max(0L, Configs.weeklyBlocksMined);
    }

    public static String getWeeklyBlocksWeek()
    {
        resetPeriodStatsIfNeeded(System.currentTimeMillis());
        return Configs.weeklyBlocksWeek == null ? "" : Configs.weeklyBlocksWeek;
    }

    public static long getPersonalRecordDailyBlocks()
    {
        resetPeriodStatsIfNeeded(System.currentTimeMillis());
        return Math.max(Configs.personalRecordDailyBlocks, Configs.dailyBlocksMined);
    }

    public static long getPersonalRecordWeeklyBlocks()
    {
        resetPeriodStatsIfNeeded(System.currentTimeMillis());
        return Math.max(Configs.personalRecordWeeklyBlocks, Configs.weeklyBlocksMined);
    }

    public static long getFastest100kMs()
    {
        return Math.max(0L, Configs.fastest100kMs);
    }

    public static long getFastest100kSeconds()
    {
        return Math.max(0L, Math.round(getFastest100kMs() / 1000.0D));
    }

    public static String getFastest100kClock()
    {
        long fastestMs = getFastest100kMs();
        return fastestMs <= 0L ? "--" : UiFormat.formatDuration(Math.max(1L, Math.round(fastestMs / 1000.0D)));
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

    public static SessionData simulateDevFinishedSession()
    {
        long now = System.currentTimeMillis();
        int durationMinutes = 195;
        long startTimeMs = now - durationMinutes * ONE_MINUTE_MS - 30_000L;
        SessionData session = new SessionData(startTimeMs);

        long totalBlocks = 0L;
        for (int minute = 0; minute < durationMinutes; minute++)
        {
            int warmup = Math.min(90, minute * 7);
            int cooldown = Math.max(0, minute - 168) * 9;
            int wave = (int) Math.round(Math.sin(minute / 8.0D) * 95.0D + Math.sin(minute / 21.0D) * 70.0D);
            int bucketBlocks = Math.max(120, 650 + warmup - cooldown + wave + (minute % 11) * 11);
            session.recordMinedAmount(minute * ONE_MINUTE_MS, bucketBlocks);
            totalBlocks += bucketBlocks;
        }

        session.totalBlocks = totalBlocks;
        session.endTimeMs = startTimeMs + durationMinutes * ONE_MINUTE_MS;
        session.bestStreakSeconds = 27L * 60L;
        session.blockBreakdown = buildDevSessionBreakdown(totalBlocks);

        resetDailyProgressIfNeeded();
        resetPeriodStatsIfNeeded(now);

        Configs.totalBlocksMined = Math.max(0L, Configs.totalBlocksMined) + totalBlocks;
        Configs.dailyProgress = Math.max(0L, Configs.dailyProgress) + totalBlocks;
        recordPeriodBlocksMined(totalBlocks, now);

        ProjectEntry activeProject = Configs.getActiveProject();
        if (activeProject != null)
        {
            activeProject.progress = Math.max(0L, activeProject.progress) + totalBlocks;
        }

        Configs.WorldStatsEntry worldStats = touchCurrentWorldStats(now);
        worldStats.totalBlocks = Math.max(0L, worldStats.totalBlocks) + totalBlocks;
        worldStats.lastSeenAt = now;
        if (worldStats.blockBreakdown == null)
        {
            worldStats.blockBreakdown = new LinkedHashMap<>();
        }
        for (Map.Entry<String, Long> entry : session.blockBreakdown.entrySet())
        {
            worldStats.blockBreakdown.merge(entry.getKey(), entry.getValue(), Long::sum);
        }
        worldStats.blockBreakdown = Configs.sanitizeBlockBreakdown(worldStats.blockBreakdown);
        worldStats.blockBreakdownSource = Configs.BLOCK_BREAKDOWN_SOURCE_LOCAL_OBSERVED;
        worldStats.blockBreakdownUpdatedAtMs = now;

        if (session.totalBlocks >= FASTEST_100K_TARGET)
        {
            long fastestDurationMs = Math.max(1L, (FASTEST_100K_TARGET * session.getDurationMs()) / session.totalBlocks);
            if (Configs.fastest100kMs <= 0L || fastestDurationMs < Configs.fastest100kMs)
            {
                Configs.fastest100kMs = fastestDurationMs;
                Configs.fastest100kStartedAtMs = session.startTimeMs;
                Configs.fastest100kFinishedAtMs = session.startTimeMs + fastestDurationMs;
            }
        }

        SessionHistory.save(session);
        Configs.saveToFile();
        return session;
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

    public static void onBpsSmoothingChanged()
    {
        lastBpsSmoothing = Configs.getBpsSmoothingMode();
        trimMetricWindow();
        rollingBlocksPerSecond = calculateRollingBps(lastBpsSmoothing);
        lastBpsUpdateTick = metricTickIndex;
    }

    public static Map<String, Long> getSortedBreakdown(SessionData session)
    {
        return session.blockBreakdown.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (left, right) -> left, java.util.LinkedHashMap::new));
    }

    private static Map<String, Long> buildDevSessionBreakdown(long totalBlocks)
    {
        String[] ids = {
                "minecraft:deepslate",
                "minecraft:tuff",
                "minecraft:stone",
                "minecraft:gravel",
                "minecraft:diorite",
                "minecraft:andesite",
                "minecraft:deepslate_redstone_ore",
                "minecraft:deepslate_diamond_ore"
        };
        int[] weights = { 355, 210, 170, 95, 70, 62, 28, 10 };
        Map<String, Long> breakdown = new LinkedHashMap<>();
        long assigned = 0L;
        for (int i = 0; i < ids.length; i++)
        {
            long amount = i == ids.length - 1 ? totalBlocks - assigned : (totalBlocks * weights[i]) / 1000L;
            assigned += amount;
            if (amount > 0L)
            {
                breakdown.put(ids[i], amount);
            }
        }
        return breakdown;
    }

    private static void resetDailyProgressIfNeeded()
    {
        if (FeatureToggle.TWEAK_DAILY_AUTO_RESET.getBooleanValue() == false)
        {
            return;
        }

        long now = System.currentTimeMillis();
        ZoneId zoneId = DAILY_RESET_ZONE;
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

    private static void resetPeriodStatsIfNeeded(long now)
    {
        ZoneId zoneId = DAILY_RESET_ZONE;
        String todayKey = dateKey(now, zoneId);
        String weekKey = weekKey(now, zoneId);
        boolean changed = false;
        boolean resetPeriod = false;

        if (Configs.dailyBlocksDate == null || Configs.dailyBlocksDate.isBlank())
        {
            Configs.dailyBlocksDate = todayKey;
            changed = true;
        }
        else if (Configs.dailyBlocksDate.equals(todayKey) == false)
        {
            Configs.personalRecordDailyBlocks = Math.max(Configs.personalRecordDailyBlocks, Configs.dailyBlocksMined);
            Configs.dailyBlocksMined = 0L;
            Configs.dailyBlocksDate = todayKey;
            changed = true;
            resetPeriod = true;
        }

        if (Configs.weeklyBlocksWeek == null || Configs.weeklyBlocksWeek.isBlank())
        {
            Configs.weeklyBlocksWeek = weekKey;
            changed = true;
        }
        else if (Configs.weeklyBlocksWeek.equals(weekKey) == false)
        {
            Configs.personalRecordWeeklyBlocks = Math.max(Configs.personalRecordWeeklyBlocks, Configs.weeklyBlocksMined);
            Configs.weeklyBlocksMined = 0L;
            Configs.weeklyBlocksWeek = weekKey;
            changed = true;
            resetPeriod = true;
        }

        if (changed)
        {
            Configs.saveToFile();
            if (resetPeriod)
            {
                CloudSyncManager.syncNow("mining records period reset");
                DigsSyncManager.syncNow("mining records period reset");
            }
        }
    }

    private static void recordPeriodBlocksMined(long amount, long now)
    {
        if (amount <= 0L)
        {
            return;
        }

        resetPeriodStatsIfNeeded(now);
        Configs.dailyBlocksMined = Math.max(0L, Configs.dailyBlocksMined) + amount;
        Configs.weeklyBlocksMined = Math.max(0L, Configs.weeklyBlocksMined) + amount;
        Configs.personalRecordDailyBlocks = Math.max(Configs.personalRecordDailyBlocks, Configs.dailyBlocksMined);
        Configs.personalRecordWeeklyBlocks = Math.max(Configs.personalRecordWeeklyBlocks, Configs.weeklyBlocksMined);
    }

    private static void recordFastest100kIfReached(long now)
    {
        if (sessionActive == false || sessionPaused || session100kRecorded || currentSession.totalBlocks < FASTEST_100K_TARGET)
        {
            return;
        }

        long durationMs = Math.max(1L, getActiveElapsedMs(now));
        session100kRecorded = true;
        updateFastest100kRecord(durationMs, currentSession.startTimeMs, now);
    }

    private static void recordFastest100kWindow(long now)
    {
        FASTEST_100K_EVENT_TIMES.addLast(now);
        while (FASTEST_100K_EVENT_TIMES.size() > FASTEST_100K_TARGET)
        {
            FASTEST_100K_EVENT_TIMES.pollFirst();
        }

        if (FASTEST_100K_EVENT_TIMES.size() < FASTEST_100K_TARGET || FASTEST_100K_EVENT_TIMES.peekFirst() == null)
        {
            return;
        }

        long startedAt = FASTEST_100K_EVENT_TIMES.peekFirst();
        updateFastest100kRecord(Math.max(1L, now - startedAt), startedAt, now);
    }

    private static void updateFastest100kRecord(long durationMs, long startedAtMs, long finishedAtMs)
    {
        if (durationMs <= 0L)
        {
            return;
        }

        if (Configs.fastest100kMs <= 0L || durationMs < Configs.fastest100kMs)
        {
            Configs.fastest100kMs = durationMs;
            Configs.fastest100kStartedAtMs = Math.max(0L, startedAtMs);
            Configs.fastest100kFinishedAtMs = Math.max(0L, finishedAtMs);
            Configs.saveToFile();
            CloudSyncManager.syncHeartbeat();
        }
    }

    private static void recordSuccessfulHarvestForRollingMetrics()
    {
        if (sessionActive && !sessionPaused)
        {
            currentTickBpsBlocks++;
            currentTickBphBlocks++;
        }
    }

    private static void updateRollingMetrics(boolean hasMiningContext)
    {
        if (hasMiningContext == false)
        {
            resetRollingMetrics();
            return;
        }

        if (sessionActive == false)
        {
            resetRollingMetrics();
            return;
        }

        if (sessionPaused)
        {
            return;
        }

        Configs.BpsSmoothing mode = Configs.getBpsSmoothingMode();
        if (lastBpsSmoothing != mode)
        {
            lastBpsSmoothing = mode;
            trimMetricWindow();
            rollingBlocksPerSecond = calculateRollingBps(mode);
            lastBpsUpdateTick = metricTickIndex;
        }

        metricTickIndex++;
        METRIC_TICK_COUNTS.addLast(new TickBlockCount(metricTickIndex, currentTickBpsBlocks, currentTickBphBlocks));
        currentTickBpsBlocks = 0;
        currentTickBphBlocks = 0;
        trimMetricWindow();

        if (metricTickIndex - lastBpsUpdateTick >= BPS_UPDATE_INTERVAL_TICKS)
        {
            rollingBlocksPerSecond = calculateRollingBps(mode);
            lastBpsUpdateTick = metricTickIndex;
        }

        if (metricTickIndex - lastBphUpdateTick >= BPS_UPDATE_INTERVAL_TICKS)
        {
            rollingBlocksPerHour = calculateSessionBph();
            lastBphUpdateTick = metricTickIndex;
        }

        sessionActiveTicks++;

        updateDisplayedRollingMetrics();
    }

    public static void resetRollingMetrics()
    {
        METRIC_TICK_COUNTS.clear();
        metricTickIndex = 0L;
        lastBpsUpdateTick = 0L;
        lastBphUpdateTick = 0L;
        sessionActiveTicks = 0L;
        currentTickBpsBlocks = 0;
        currentTickBphBlocks = 0;
        rollingBlocksPerSecond = 0D;
        rollingBlocksPerHour = 0D;
        displayedBlocksPerSecond = 0D;
        displayedBlocksPerHour = 0D;
        lastBpsSmoothing = Configs.getBpsSmoothingMode();
    }

    private static void updateDisplayedRollingMetrics()
    {
        displayedBlocksPerSecond = Math.max(0D, Math.min(20D, rollingBlocksPerSecond));
        displayedBlocksPerHour = Math.max(0D, Math.min(SessionData.MAX_BLOCKS_PER_HOUR, rollingBlocksPerHour));
    }


    private static void trimMetricWindow()
    {
        while (METRIC_TICK_COUNTS.size() > BPH_WINDOW_TICKS)
        {
            METRIC_TICK_COUNTS.pollFirst();
        }
    }

    private static void freezeRollingMetrics()
    {
        currentTickBpsBlocks = 0;
        currentTickBphBlocks = 0;
        rollingBlocksPerSecond = 0D;
        rollingBlocksPerHour = 0D;
        displayedBlocksPerSecond = 0D;
        displayedBlocksPerHour = 0D;
    }

    private static double calculateRollingBps(Configs.BpsSmoothing mode)
    {
        int maxTicks = Math.max(1, Math.min(mode.getWindowTicks(), BPS_WINDOW_TICKS));
        int preferredTicks = Math.max(1, Math.min(maxTicks, mode.getPreferredMinimumTicks()));
        int availableTicks = METRIC_TICK_COUNTS.size();
        int targetTicks = availableTicks < preferredTicks ? availableTicks : Math.min(maxTicks, availableTicks);
        int ticksUsed = 0;
        int validBlocks = 0;
        Iterator<TickBlockCount> iterator = METRIC_TICK_COUNTS.descendingIterator();
        while (iterator.hasNext() && ticksUsed < targetTicks)
        {
            TickBlockCount tick = iterator.next();
            validBlocks += Math.max(0, tick.bpsBlocks());
            ticksUsed++;
        }

        if (ticksUsed <= 0 || validBlocks <= 0)
        {
            return 0D;
        }

        double seconds = ticksUsed / (double) TICKS_PER_SECOND;
        if (seconds <= 0D)
        {
            return 0D;
        }

        return Math.max(0D, Math.min(20D, validBlocks / seconds));
    }

    private static double calculateRollingBph()
    {
        int ticksUsed = 0;
        int validBlocks = 0;
        Iterator<TickBlockCount> iterator = METRIC_TICK_COUNTS.descendingIterator();
        while (iterator.hasNext() && ticksUsed < BPH_WINDOW_TICKS)
        {
            TickBlockCount tick = iterator.next();
            validBlocks += Math.max(0, tick.bphBlocks());
            ticksUsed++;
        }

        if (ticksUsed <= 0 || validBlocks <= 0)
        {
            return 0D;
        }

        return Math.min(SessionData.MAX_BLOCKS_PER_HOUR, Math.max(0D, validBlocks * (double) BPH_WINDOW_TICKS / ticksUsed));
    }

    private static double calculateSessionBph()
    {
        return calculateRollingBph();
    }

    private static String dateKey(long now, ZoneId zoneId)
    {
        return Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate().toString();
    }

    private static String weekKey(long now, ZoneId zoneId)
    {
        WeekFields weekFields = WeekFields.ISO;
        ZonedDateTime dateTime = Instant.ofEpochMilli(now).atZone(zoneId);
        int year = dateTime.get(weekFields.weekBasedYear());
        int week = dateTime.get(weekFields.weekOfWeekBasedYear());
        return String.format("%04d-W%02d", year, week);
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

    private static void recordCurrentWorldBlockBreakdown(Configs.WorldStatsEntry worldStats, Block block, long now)
    {
        if (worldStats == null || block == null)
        {
            return;
        }

        String key = BlockBreakdownCatalog.blockId(block);
        if (BlockBreakdownCatalog.isValid(key) == false)
        {
            return;
        }

        if (worldStats.blockBreakdown == null)
        {
            worldStats.blockBreakdown = new LinkedHashMap<>();
        }
        worldStats.blockBreakdown.merge(key, 1L, Long::sum);
        if (Configs.BLOCK_BREAKDOWN_SOURCE_MINECRAFT_STATS.equals(worldStats.blockBreakdownSource) == false)
        {
            worldStats.blockBreakdownSource = Configs.BLOCK_BREAKDOWN_SOURCE_LOCAL_OBSERVED;
        }
        worldStats.blockBreakdownUpdatedAtMs = now;
    }

    private static void handleAutoSessionOnValidMine(long now)
    {
        if (lastValidBlockMineMs == 0L || now - lastValidBlockMineMs > AUTO_MINING_GAP_MS)
        {
            autoMiningStreakStartMs = now;
        }
        lastValidBlockMineMs = now;

        if (autoMiningStreakStartMs <= 0L || now - autoMiningStreakStartMs < AUTO_MINING_REQUIRED_MS)
        {
            return;
        }

        if (sessionActive == false)
        {
            startNewSession();
            lastValidBlockMineMs = now;
            autoMiningStreakStartMs = 0L;
            return;
        }

        if (sessionPaused && sessionAutoPaused)
        {
            resumeAutoPausedSession(now);
            lastValidBlockMineMs = now;
            autoMiningStreakStartMs = 0L;
        }
    }

    private static void maybeAutoPauseSession(long now)
    {
        if (sessionActive == false || sessionPaused || lastValidBlockMineMs <= 0L)
        {
            return;
        }

        if (now - lastValidBlockMineMs < AUTO_PAUSE_IDLE_MS)
        {
            return;
        }

        pausedAtMs = now;
        sessionPaused = true;
        sessionAutoPaused = true;
        autoMiningStreakStartMs = 0L;
        freezeRollingMetrics();
        CloudSyncManager.syncHeartbeat();
        MmmDebugLogger.info(
                "miningstats-auto-pause",
                SESSION_DEBUG_LOG_INTERVAL_MS,
                "[MMM_DEBUG] session-auto-paused idleMs={}",
                now - lastValidBlockMineMs);
    }

    private static void resumeAutoPausedSession(long now)
    {
        pausedAccumulatedMs += Math.max(0L, now - pausedAtMs);
        pausedAtMs = 0L;
        sessionPaused = false;
        sessionAutoPaused = false;
        rollingBlocksPerHour = calculateSessionBph();
        rollingBlocksPerSecond = calculateRollingBps(lastBpsSmoothing);
        updateDisplayedRollingMetrics();
        CloudSyncManager.syncHeartbeat();
        MmmDebugLogger.info(
                "miningstats-auto-resume",
                SESSION_DEBUG_LOG_INTERVAL_MS,
                "[MMM_DEBUG] session-auto-resumed");
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
        if (MmmDebugLogger.shouldLog("miningstats.source-update." + reason, SOURCE_UPDATE_DEBUG_LOG_INTERVAL_MS) == false)
        {
            return;
        }

        WorldSessionContext.WorldInfo world = WorldSessionContext.getCurrentWorldInfo();
        MMM.LOGGER.info(
                "[MMM_DEBUG] source-update reason={} worldKey={} worldName={} sessionActive={} sessionBlocks={} sourceBefore={} sourceAfter={} delta={} lifetime={}",
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
                && session.totalBlocks >= MIN_SYNCED_SESSION_BLOCKS
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

    private record TickBlockCount(long tickIndex, int bpsBlocks, int bphBlocks) {}
}

