package com.mmm.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mmm.Reference;
import com.mmm.MMM;
import com.mmm.config.Configs;
import com.mmm.config.Configs.ProjectEntry;
import com.mmm.storage.SessionData;
import com.mmm.storage.SessionHistory;
import com.mmm.storage.WorldSessionContext;
import com.mmm.tracker.MiningStats;
import com.mmm.tracker.MiningValidationTracker;
import com.mmm.util.MmmDebugLogger;
import com.mmm.util.PeriodKeys;
import com.mmm.util.UiFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.MinecraftClient;

public final class CloudSyncManager
{
    private static final String LOG_PREFIX = "[MMM_SYNC]";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final long SOURCE_SCOREBOARD_SCAN_INTERVAL_MS = 3_000L;
    private static final long SAVED_SESSION_BACKLOG_SCAN_INTERVAL_MS = 30_000L;
    private static final long HUD_FAILURE_GRACE_MS = 12_000L;
    private static final long HUD_HEALTH_STALE_MS = 90_000L;
    private static final long SYNC_UNAVAILABLE_LOG_INTERVAL_MS = 30_000L;
    private static final int MAX_SAVED_SESSIONS_TO_QUEUE = 25;

    private static long lastHeartbeatMs;
    private static long lastLiveBlockSyncMs;
    private static long lastSourceScoreboardScanMs;
    private static long lastSavedSessionBacklogScanMs;
    private static volatile SyncStatus syncStatus = SyncStatus.CONNECTED;
    private static volatile String syncStatusDetail = "";
    private static volatile long lastHealthySignalMs;
    private static volatile long lastFailureSignalMs;
    private static SourceLeaderboardSnapshot latestLeaderboardSnapshot;
    private static String lastQueuedLiveFingerprint;
    private static String lastSuccessfulLiveFingerprint;
    private static String lastSuccessfulLeaderboardFingerprint;
    private static volatile String lastPayloadSourceKey = "";
    private static volatile String lastPayloadSourceName = "";
    private static volatile long lastSyncUnavailableLogMs;
    private static volatile String lastSyncUnavailableReason = "";

    private CloudSyncManager()
    {
    }

    public static void onClientTick(long now)
    {
        if (canSync() == false || hasLiveContext() == false)
        {
            return;
        }

        if (isSyncCadenceDue(now))
        {
            syncHeartbeat();
        }
        queueSavedSessionsIfDue(now);

        MinecraftClient client = MinecraftClient.getInstance();
        if (now - lastSourceScoreboardScanMs >= SOURCE_SCOREBOARD_SCAN_INTERVAL_MS)
        {
            lastSourceScoreboardScanMs = now;
            latestLeaderboardSnapshot = SourceLeaderboardReader.read(client);
            maybeBootstrapFromLeaderboardSnapshot(client, now);
        }

        if (latestLeaderboardSnapshot != null && syncStatus != SyncStatus.SYNCING && syncStatus != SyncStatus.SYNCED)
        {
            syncStatus = SyncStatus.CONNECTED;
            syncStatusDetail = "Leaderboard detected";
            touchHealthy();
        }

        String currentLeaderboardFingerprint = leaderboardFingerprint(latestLeaderboardSnapshot);
        if (latestLeaderboardSnapshot != null
                && currentLeaderboardFingerprint.equals(lastSuccessfulLeaderboardFingerprint) == false)
        {
            queueCurrentLivePayloadIfDue(now);
        }
    }

    public static void syncHeartbeat()
    {
        if (canSync() == false || hasLiveContext() == false)
        {
            return;
        }

        long now = System.currentTimeMillis();
        if (isSyncCadenceDue(now) == false)
        {
            return;
        }

        lastHeartbeatMs = now;
        lastLiveBlockSyncMs = now;
        queueSavedSessionsForSync("heartbeat");
        SessionData liveSession = MiningStats.isSessionActive() ? MiningStats.getCurrentSession() : null;
        queueLivePayload(buildPayload(liveSession, liveSession == null ? null : getCurrentSessionStatus()), true);
    }

    public static void syncNow(String reason)
    {
        if (canSync() == false || hasLiveContext() == false)
        {
            return;
        }

        long now = System.currentTimeMillis();
        lastHeartbeatMs = now;
        lastLiveBlockSyncMs = now;
        queueSavedSessionsForSync(reason == null || reason.isBlank() ? "manual sync" : reason);
        SessionData liveSession = MiningStats.isSessionActive() ? MiningStats.getCurrentSession() : null;
        queueLivePayload(buildPayload(liveSession, liveSession == null ? null : getCurrentSessionStatus()), true);
        SyncQueueManager.forceFlush(reason == null || reason.isBlank() ? "manual sync" : reason);
    }

    public static void syncFinishedSession(SessionData session)
    {
        if (canSync() == false || session == null)
        {
            return;
        }

        String sessionKey = sessionKey(session);
        JsonObject payload = buildPayload(session, "ended");
        SyncQueueManager.enqueueCloudFinishedSession(sessionKey, payload);
    }

    public static void onBlockMined(long now)
    {
        MinecraftClient client = MinecraftClient.getInstance();
        WorldSessionContext.WorldInfo worldInfo = WorldSessionContext.getCurrentWorldInfo();
        String sourceKey = ScoreboardSourceResolver.sourceKey(worldInfo.displayName(), worldInfo);
        String sourceName = ScoreboardSourceResolver.displayName(worldInfo.displayName(), worldInfo);
        boolean autoSyncEnabled = Configs.Generic.WEBSITE_SYNC_ENABLED.getBooleanValue();
        boolean hasEndpoint = Configs.cloudSyncEndpoint != null && Configs.cloudSyncEndpoint.isBlank() == false;
        boolean hasContext = client != null && client.player != null && client.world != null;
        boolean loggedIn = hasContext && client.getSession() != null && client.getSession().getUsername().isBlank() == false;
        String playerUuid = hasContext ? client.player.getUuidAsString() : "";
        String playerName = hasContext ? resolveUsername(client) : "";
        long totalMined = MiningStats.getTotalMined();
        long sessionMined = MiningStats.getSessionBlocksMined();
        boolean hasSyncSecret = Configs.cloudSyncSecret != null && Configs.cloudSyncSecret.isBlank() == false;
        boolean hasLinkedIdentity = Configs.websiteLinkedMinecraftUuid != null && Configs.websiteLinkedMinecraftUuid.isBlank() == false;
        boolean hasSessionToken = hasSessionToken(client);

        if (MmmDebugLogger.shouldLog("cloud-sync-check", 5_000L))
        {
            MMM.LOGGER.info(
                    "{} sync-check autoSyncEnabled={} loggedIn={} hasWorldContext={} hasEndpoint={} hasSyncSecret={} hasLinkedIdentity={} hasSessionToken={} playerUuid={} playerName={} sourceSlug={} sourceName={} totalMined={} sessionMined={} targetUrl={}",
                    LOG_PREFIX,
                    autoSyncEnabled,
                    loggedIn,
                    hasContext,
                    hasEndpoint,
                    hasSyncSecret,
                    hasLinkedIdentity,
                    hasSessionToken,
                    playerUuid,
                    playerName,
                    sourceKey,
                    sourceName,
                    totalMined,
                    sessionMined,
                    Configs.cloudSyncEndpoint
            );
        }

        if (canSync() == false || hasLiveContext() == false)
        {
            return;
        }

        queueCurrentLivePayloadIfDue(now);
    }

    static void onQueued(SyncItemType type, JsonObject payload)
    {
        if (type != SyncItemType.CLOUD_LIVE_STATE && type != SyncItemType.CLOUD_FINISHED_SESSION)
        {
            return;
        }

        syncStatus = SyncStatus.QUEUED;
        syncStatusDetail = type == SyncItemType.CLOUD_FINISHED_SESSION ? "Session queued for sync." : "Live sync queued.";

        if (type == SyncItemType.CLOUD_LIVE_STATE)
        {
            lastQueuedLiveFingerprint = livePayloadFingerprint(payload);
        }
    }

    static void onQueueSuccess(SyncItemType type, JsonObject payload, String responseBody)
    {
        if (type != SyncItemType.CLOUD_LIVE_STATE && type != SyncItemType.CLOUD_FINISHED_SESSION)
        {
            return;
        }

        syncStatus = SyncStatus.SYNCED;
        syncStatusDetail = type == SyncItemType.CLOUD_FINISHED_SESSION ? "Finished session delivered." : "Latest sync delivered.";
        touchHealthy();
        markSyncedSessions(payload, responseBody);
        applySuccessfulSyncResponse(responseBody);

        if (type == SyncItemType.CLOUD_LIVE_STATE)
        {
            lastSuccessfulLiveFingerprint = livePayloadFingerprint(payload);
            lastQueuedLiveFingerprint = lastSuccessfulLiveFingerprint;
        }

        if (latestLeaderboardSnapshot != null)
        {
            lastSuccessfulLeaderboardFingerprint = leaderboardFingerprint(latestLeaderboardSnapshot);
        }
    }

    static void onQueueRetry(SyncItemType type, String detail, long nextRetryAtMs)
    {
        if (type != SyncItemType.CLOUD_LIVE_STATE && type != SyncItemType.CLOUD_FINISHED_SESSION)
        {
            return;
        }

        syncStatus = SyncStatus.FAILED;
        lastFailureSignalMs = System.currentTimeMillis();
        syncStatusDetail = "Retry scheduled for " + PendingSyncQueue.formatInstant(nextRetryAtMs)
                + (detail == null || detail.isBlank() ? "" : " (" + detail + ")");
    }

    static void onQueueDropped(SyncItemType type, String detail)
    {
        if (type != SyncItemType.CLOUD_LIVE_STATE && type != SyncItemType.CLOUD_FINISHED_SESSION)
        {
            return;
        }

        syncStatus = SyncStatus.FAILED;
        lastFailureSignalMs = System.currentTimeMillis();
        syncStatusDetail = detail == null || detail.isBlank() ? "Sync item dropped." : detail;
    }

    public static boolean isHudHealthy(long now)
    {
        if (Configs.Generic.WEBSITE_SYNC_ENABLED.getBooleanValue() == false
                || Configs.cloudSyncEndpoint == null
                || Configs.cloudSyncEndpoint.isBlank())
        {
            return false;
        }

        PendingSyncQueue.Snapshot snapshot = SyncQueueManager.getSnapshot();
        int pending = snapshot.countFor(SyncItemType.CLOUD_LIVE_STATE) + snapshot.countFor(SyncItemType.CLOUD_FINISHED_SESSION);
        long recentHealthyMs = Math.max(lastHealthySignalMs, snapshot.lastSuccessfulSyncAtMs());

        if (snapshot.flushActive())
        {
            return true;
        }

        if (syncStatus == SyncStatus.FAILED)
        {
            return recentHealthyMs > 0L && now - recentHealthyMs <= HUD_FAILURE_GRACE_MS;
        }

        if (pending > 0 && recentHealthyMs > 0L && now - recentHealthyMs > HUD_FAILURE_GRACE_MS)
        {
            return false;
        }

        if (latestLeaderboardSnapshot != null
                && latestLeaderboardSnapshot.isValid()
                && recentHealthyMs > 0L
                && now - recentHealthyMs <= HUD_HEALTH_STALE_MS)
        {
            return true;
        }

        return recentHealthyMs > 0L && now - recentHealthyMs <= HUD_HEALTH_STALE_MS;
    }

    public static String getStatusLabel()
    {
        PendingSyncQueue.Snapshot snapshot = SyncQueueManager.getSnapshot();
        int pending = snapshot.countFor(SyncItemType.CLOUD_LIVE_STATE)
                + snapshot.countFor(SyncItemType.CLOUD_FINISHED_SESSION)
                + snapshot.countFor(SyncItemType.PLAYER_TOTAL_DIGS)
                + snapshot.countFor(SyncItemType.WEBSITE_LINK_CLAIM);

        if (snapshot.flushActive() && pending > 0)
        {
            return "Syncing";
        }

        if (pending > 0)
        {
            return "Queued";
        }

        return switch (syncStatus)
        {
            case SYNCED -> "Synced";
            case FAILED -> "Retrying";
            default -> "Connected";
        };
    }

    public static String getStatusDetail()
    {
        if (Configs.Generic.WEBSITE_SYNC_DEBUG.getBooleanValue() == false)
        {
            return syncStatusDetail;
        }

        PendingSyncQueue.Snapshot snapshot = SyncQueueManager.getSnapshot();
        List<String> parts = new ArrayList<>();
        parts.add("Q:" + snapshot.queueSize());
        parts.add(snapshot.flushActive() ? "flush=active" : "flush=idle");

        if (snapshot.lastSuccessfulSyncAtMs() > 0L)
        {
            long ageSeconds = Math.max(0L, (System.currentTimeMillis() - snapshot.lastSuccessfulSyncAtMs()) / 1000L);
            parts.add("lastOk=" + UiFormat.formatDuration(ageSeconds));
        }

        if (syncStatusDetail != null && syncStatusDetail.isBlank() == false)
        {
            parts.add(syncStatusDetail);
        }

        return String.join(" | ", parts);
    }

    public static void resetForDisconnect()
    {
        latestLeaderboardSnapshot = null;
        syncStatus = SyncStatus.CONNECTED;
        syncStatusDetail = "";
        lastHeartbeatMs = 0L;
        lastLiveBlockSyncMs = 0L;
        lastSourceScoreboardScanMs = 0L;
        lastQueuedLiveFingerprint = null;
        lastSuccessfulLiveFingerprint = null;
        lastSuccessfulLeaderboardFingerprint = null;
        lastFailureSignalMs = 0L;
        lastPayloadSourceKey = "";
        lastPayloadSourceName = "";
    }

    public static String getLastPayloadSourceKey()
    {
        return lastPayloadSourceKey;
    }

    public static String getLastPayloadSourceName()
    {
        return lastPayloadSourceName;
    }

    public static long getSyncIntervalMs()
    {
        return Configs.normalizeWebsiteSyncIntervalMs(Configs.websiteSyncIntervalMs);
    }

    public static String getSyncTier()
    {
        return Configs.normalizeWebsiteSyncTier(Configs.websiteSyncTier);
    }

    private static boolean isSyncCadenceDue(long now)
    {
        long interval = getSyncIntervalMs();
        long lastSyncMs = Math.max(lastLiveBlockSyncMs, Configs.websiteLastSuccessfulSyncMs);
        return lastSyncMs <= 0L || now - lastSyncMs >= interval;
    }

    private static void queueCurrentLivePayloadIfDue(long now)
    {
        if (isSyncCadenceDue(now) == false)
        {
            return;
        }

        lastLiveBlockSyncMs = now;
        lastHeartbeatMs = now;
        SessionData liveSession = MiningStats.isSessionActive() ? MiningStats.getCurrentSession() : null;
        queueLivePayload(buildPayload(liveSession, liveSession == null ? null : getCurrentSessionStatus()), true);
    }

    private static void queueLivePayload(JsonObject payload)
    {
        queueLivePayload(payload, false);
    }

    private static void queueLivePayload(JsonObject payload, boolean force)
    {
        String fingerprint = livePayloadFingerprint(payload);

        if (!force
                && fingerprint != null
                && fingerprint.equals(lastSuccessfulLiveFingerprint)
                && syncStatus == SyncStatus.SYNCED)
        {
            return;
        }

        if (!force
                && fingerprint != null
                && fingerprint.equals(lastQueuedLiveFingerprint)
                && syncStatus == SyncStatus.QUEUED)
        {
            return;
        }

        lastQueuedLiveFingerprint = fingerprint;
        SyncQueueManager.enqueueCloudLiveState(payload);
    }

    static void applySuccessfulSyncResponse(String responseBody)
    {
        applySyncResponse(responseBody);
        WebsiteProfileTotals.refresh(true);
        Configs.websiteLastSuccessfulSyncMs = System.currentTimeMillis();
        Configs.saveToFile();
    }

    private static void applySyncResponse(String responseBody)
    {
        if (responseBody == null || responseBody.isBlank())
        {
            return;
        }

        try
        {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            boolean changed = false;

            JsonObject syncPolicy = getObject(root, "sync_policy");
            if (syncPolicy != null)
            {
                String tier = Configs.normalizeWebsiteSyncTier(getString(syncPolicy, "tier", Configs.websiteSyncTier));
                long intervalMs = Configs.normalizeWebsiteSyncIntervalMs(getLong(syncPolicy, "interval_ms", Configs.websiteSyncIntervalMs));
                if (tier.equals(Configs.websiteSyncTier) == false)
                {
                    Configs.websiteSyncTier = tier;
                    changed = true;
                }
                if (intervalMs != Configs.websiteSyncIntervalMs)
                {
                    Configs.websiteSyncIntervalMs = intervalMs;
                    changed = true;
                }
            }

            JsonObject playerProfile = getObject(root, "player_profile");
            if (playerProfile != null)
            {
                long currentGlobalTotal = Math.max(0L, Configs.websiteGlobalTotalBlocks);
                long globalTotal = Math.max(0L, getLong(playerProfile, "global_total_blocks", currentGlobalTotal));
                if (playerProfile.has("global_total_blocks") && globalTotal > 0L && (currentGlobalTotal <= 0L || globalTotal >= currentGlobalTotal))
                {
                    Configs.websiteGlobalTotalBlocks = globalTotal;
                    Configs.websiteGlobalTotalUpdatedAtMs = System.currentTimeMillis();
                    changed = true;
                }
                else if (playerProfile.has("global_total_blocks") && globalTotal > 0L && currentGlobalTotal > 0L && globalTotal < currentGlobalTotal)
                {
                    MmmDebugLogger.info(
                            "website-global-total-stale",
                            30_000L,
                            "[MMM_SYNC] ignored lower website global total candidate={} current={}",
                            globalTotal,
                            currentGlobalTotal);
                }
            }

            if (changed)
            {
                Configs.saveToFile();
            }
        }
        catch (Exception ignored)
        {
        }
    }

    private static JsonObject getObject(JsonObject root, String key)
    {
        if (root == null || root.has(key) == false)
        {
            return null;
        }

        JsonElement element = root.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String getString(JsonObject object, String key, String fallback)
    {
        if (object == null || object.has(key) == false)
        {
            return fallback;
        }

        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : fallback;
    }

    private static long getLong(JsonObject object, String key, long fallback)
    {
        if (object == null || object.has(key) == false)
        {
            return fallback;
        }

        try
        {
            JsonElement element = object.get(key);
            return element != null && element.isJsonPrimitive() ? element.getAsLong() : fallback;
        }
        catch (Exception ignored)
        {
            return fallback;
        }
    }

    private static void touchHealthy()
    {
        lastHealthySignalMs = System.currentTimeMillis();
        lastFailureSignalMs = 0L;
    }

    private static boolean canSync()
    {
        if (Configs.Generic.WEBSITE_SYNC_ENABLED.getBooleanValue() == false)
        {
            logSyncUnavailable("websiteSyncEnabled_false");
            return false;
        }

        if (Configs.cloudSyncEndpoint == null || Configs.cloudSyncEndpoint.isBlank())
        {
            logSyncUnavailable("endpoint_blank");
            return false;
        }

        return true;
    }

    private static void logSyncUnavailable(String reason)
    {
        long now = System.currentTimeMillis();
        syncStatusDetail = "Sync disabled: " + reason;

        if (reason.equals(lastSyncUnavailableReason) && now - lastSyncUnavailableLogMs < SYNC_UNAVAILABLE_LOG_INTERVAL_MS)
        {
            return;
        }

        lastSyncUnavailableReason = reason;
        lastSyncUnavailableLogMs = now;
        MMM.LOGGER.warn("{} cloud-sync-disabled reason={} endpoint={}",
                LOG_PREFIX,
                reason,
                Configs.cloudSyncEndpoint == null ? "" : Configs.cloudSyncEndpoint);
    }

    private static boolean hasLiveContext()
    {
        MinecraftClient client = MinecraftClient.getInstance();
        return client != null && client.player != null && client.world != null;
    }

    private static String getCurrentSessionStatus()
    {
        if (MiningStats.isSessionActive() == false)
        {
            return null;
        }

        return MiningStats.isSessionPaused() ? "paused" : "active";
    }

    private static void queueSavedSessionsIfDue(long now)
    {
        if (now - lastSavedSessionBacklogScanMs < SAVED_SESSION_BACKLOG_SCAN_INTERVAL_MS)
        {
            return;
        }

        lastSavedSessionBacklogScanMs = now;
        queueSavedSessionsForSync("saved session backlog");
    }

    private static void queueSavedSessionsForSync(String reason)
    {
        if (canSync() == false || hasLiveContext() == false)
        {
            return;
        }

        int queued = 0;
        for (SessionHistory.WorldHistory history : SessionHistory.getWorldHistories())
        {
            for (SessionData session : history.sessions())
            {
                String sessionKey = sessionKey(session);
                if (SessionSyncState.isSynced(sessionKey))
                {
                    continue;
                }

                SyncQueueManager.enqueueCloudFinishedSession(
                        sessionKey,
                        buildSavedSessionPayload(history, session));
                queued++;
                if (queued >= MAX_SAVED_SESSIONS_TO_QUEUE)
                {
                    break;
                }
            }

            if (queued >= MAX_SAVED_SESSIONS_TO_QUEUE)
            {
                break;
            }
        }

        if (queued > 0)
        {
            MMM.LOGGER.info("{} saved-session-backlog-queued count={} reason={}",
                    LOG_PREFIX,
                    queued,
                    reason == null || reason.isBlank() ? "sync" : reason);
        }
    }

    private static JsonObject buildPayload(SessionData session, String sessionStatus)
    {
        MinecraftClient client = MinecraftClient.getInstance();
        WorldSessionContext.WorldInfo worldInfo = WorldSessionContext.getCurrentWorldInfo();
        MiningStats.GoalProgress dailyGoal = MiningStats.getDailyGoalProgress();
        MiningStats.ProjectProgress projectProgress = MiningStats.getActiveProjectProgress();

        JsonObject payload = new JsonObject();
        payload.addProperty("client_id", Configs.cloudClientId);
        payload.addProperty("minecraft_uuid", client != null && client.player != null ? client.player.getUuidAsString() : null);
        payload.addProperty("username", resolveUsername(client));
        payload.addProperty("mod_version", Reference.MOD_VERSION);
        payload.addProperty("minecraft_version", client != null ? client.getGameVersion() : null);
        payload.add("world", buildWorld(worldInfo));
        payload.add("lifetime_totals", buildLifetimeTotals());
        payload.add("mining_records", buildMiningRecords());
        payload.add("current_world_totals", buildCurrentWorldTotals(worldInfo));

        JsonObject currentWorldBlockBreakdown = BlockBreakdownPayloads.buildCurrentWorldBlockBreakdown(worldInfo);
        if (currentWorldBlockBreakdown != null)
        {
            payload.add("current_world_block_breakdown", currentWorldBlockBreakdown);
        }

        JsonObject serverPlayerBlockBreakdowns = ServerPlayerBlockBreakdownScanner.scan(client, worldInfo);
        if (serverPlayerBlockBreakdowns != null)
        {
            payload.add("server_player_block_breakdowns", serverPlayerBlockBreakdowns);
        }

        JsonObject sourceScan = buildSourceScan(client, worldInfo);
        if (sourceScan != null)
        {
            payload.add("source_scan", sourceScan);
        }

        JsonObject sourceLeaderboard = buildSourceLeaderboard();
        if (sourceLeaderboard != null)
        {
            payload.add("source_leaderboard", sourceLeaderboard);
        }

        payload.add("projects", buildProjects());
        payload.add("daily_goal", buildDailyGoal(dailyGoal));
        payload.add("synced_stats", buildSyncedStats(projectProgress, dailyGoal));
        payload.add("session_state", buildSessionState());
        payload.add("validation", buildValidationTelemetry(client, worldInfo, session));

        if (session != null && sessionStatus != null)
        {
            payload.add("session", buildSession(session, sessionStatus));
        }

        debugPayloadSource(worldInfo, payload);

        return payload;
    }

    private static JsonObject buildSavedSessionPayload(SessionHistory.WorldHistory history, SessionData session)
    {
        MinecraftClient client = MinecraftClient.getInstance();
        MiningStats.GoalProgress dailyGoal = MiningStats.getDailyGoalProgress();
        MiningStats.ProjectProgress projectProgress = MiningStats.getActiveProjectProgress();

        JsonObject payload = new JsonObject();
        payload.addProperty("client_id", Configs.cloudClientId);
        payload.addProperty("minecraft_uuid", client != null && client.player != null ? client.player.getUuidAsString() : null);
        payload.addProperty("username", resolveUsername(client));
        payload.addProperty("mod_version", Reference.MOD_VERSION);
        payload.addProperty("minecraft_version", client != null ? client.getGameVersion() : null);
        payload.add("world", buildWorld(history.worldId(), history.displayName()));
        payload.add("lifetime_totals", buildLifetimeTotals());
        payload.add("mining_records", buildMiningRecords());
        payload.add("projects", buildProjects());
        payload.add("daily_goal", buildDailyGoal(dailyGoal));
        payload.add("synced_stats", buildSyncedStats(projectProgress, dailyGoal));
        payload.add("session_state", buildSessionState());
        payload.add("validation", buildValidationTelemetry(client, new WorldSessionContext.WorldInfo(history.worldId(), history.displayName(), "unknown", ""), session));
        payload.add("session", buildSession(session, "ended"));
        return payload;
    }

    private static JsonObject buildSessionState()
    {
        JsonObject state = new JsonObject();
        state.addProperty("session_active", MiningStats.isSessionActive());
        state.addProperty("session_paused", MiningStats.isSessionPaused());
        state.addProperty("session_total_blocks", MiningStats.getSessionBlocksMined());
        state.addProperty("session_duration_seconds", Math.max(0L, MiningStats.getSessionDurationMs() / 1000L));
        return state;
    }

    private static JsonObject buildValidationTelemetry(MinecraftClient client, WorldSessionContext.WorldInfo worldInfo, SessionData session)
    {
        long sessionStart = session == null ? 0L : session.startTimeMs;
        long sessionEnd = session == null ? 0L : session.endTimeMs;
        long blocksDelta = session == null ? MiningStats.getSessionBlocksMined() : session.totalBlocks;
        String uuid = client != null && client.player != null ? client.player.getUuidAsString() : "";
        return MiningValidationTracker.buildPayload(resolveUsername(client), uuid, worldInfo, blocksDelta, sessionStart, sessionEnd);
    }

    private static JsonObject buildLifetimeTotals()
    {
        JsonObject totals = new JsonObject();
        totals.addProperty("total_blocks", Configs.totalBlocksMined);
        totals.addProperty("daily_blocks_mined", MiningStats.getDailyBlocksMined());
        totals.addProperty("weekly_blocks_mined", MiningStats.getWeeklyBlocksMined());
        totals.addProperty("personal_record_daily_blocks", MiningStats.getPersonalRecordDailyBlocks());
        totals.addProperty("personal_record_weekly_blocks", MiningStats.getPersonalRecordWeeklyBlocks());
        totals.addProperty("fastest_100k_seconds", MiningStats.getFastest100kSeconds());
        return totals;
    }

    private static JsonObject buildMiningRecords()
    {
        JsonObject records = new JsonObject();
        records.addProperty("captured_at", toIso(System.currentTimeMillis()));
        records.addProperty("daily_blocks_date", MiningStats.getDailyBlocksDate());
        records.addProperty("weekly_blocks_week", MiningStats.getWeeklyBlocksWeek());
        records.addProperty("daily_blocks_mined", MiningStats.getDailyBlocksMined());
        records.addProperty("weekly_blocks_mined", MiningStats.getWeeklyBlocksMined());
        records.addProperty("personal_record_daily_blocks", MiningStats.getPersonalRecordDailyBlocks());
        records.addProperty("personal_record_weekly_blocks", MiningStats.getPersonalRecordWeeklyBlocks());
        records.addProperty("fastest_100k_seconds", MiningStats.getFastest100kSeconds());
        records.addProperty("fastest_100k_started_at", Configs.fastest100kStartedAtMs > 0L ? toIso(Configs.fastest100kStartedAtMs) : null);
        records.addProperty("fastest_100k_finished_at", Configs.fastest100kFinishedAtMs > 0L ? toIso(Configs.fastest100kFinishedAtMs) : null);
        return records;
    }

    private static JsonObject buildCurrentWorldTotals(WorldSessionContext.WorldInfo worldInfo)
    {
        Configs.WorldStatsEntry worldStats = Configs.getOrCreateWorldStats(
                worldInfo.id(),
                worldInfo.displayName(),
                worldInfo.kind(),
                worldInfo.host());

        JsonObject totals = new JsonObject();
        totals.addProperty("world_key", worldStats.worldId);
        totals.addProperty("display_name", worldStats.displayName);
        totals.addProperty("kind", normaliseWorldKind(worldStats.kind));
        totals.addProperty("host", (String) null);
        totals.addProperty("total_blocks", worldStats.totalBlocks);
        totals.addProperty("last_seen_at", toIso(Math.max(worldStats.lastSeenAt, System.currentTimeMillis())));
        return totals;
    }

    private static JsonObject buildSourceLeaderboard()
    {
        if (latestLeaderboardSnapshot == null || latestLeaderboardSnapshot.isValid() == false)
        {
            return null;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        Set<String> fakeUsernames = CarpetFakePlayerDetector.findLikelyFakeUsernames(client, latestLeaderboardSnapshot.entries());

        List<SourceLeaderboardEntry> realEntries = latestLeaderboardSnapshot.entries().stream()
                .filter(entry -> entry.isValid())
                .filter(entry -> fakeUsernames.contains(entry.username().toLowerCase(Locale.ROOT)) == false)
                .sorted(Comparator.comparingInt(SourceLeaderboardEntry::rank))
                .toList();

        if (realEntries.isEmpty())
        {
            return null;
        }

        JsonObject leaderboard = new JsonObject();
        leaderboard.addProperty("server_name", latestLeaderboardSnapshot.serverName());
        leaderboard.addProperty("objective_title", latestLeaderboardSnapshot.objectiveTitle());
        leaderboard.addProperty("captured_at", toIso(latestLeaderboardSnapshot.capturedAtMs()));
        leaderboard.addProperty("source_type", "scoreboard");

        long snapshotTotalDigs = Math.max(0L, latestLeaderboardSnapshot.totalDigs());
        long filteredTotalDigs = realEntries.stream().mapToLong(SourceLeaderboardEntry::digs).sum();
        long payloadTotalDigs = snapshotTotalDigs > 0L ? snapshotTotalDigs : filteredTotalDigs;
        if (payloadTotalDigs > 0L)
        {
            leaderboard.addProperty("total_digs", payloadTotalDigs);
        }

        JsonArray entries = new JsonArray();
        for (SourceLeaderboardEntry entry : realEntries)
        {
            JsonObject row = new JsonObject();
            row.addProperty("username", entry.username());
            row.addProperty("digs", entry.digs());
            row.addProperty("rank", entry.rank());
            row.addProperty("source_server", latestLeaderboardSnapshot.serverName());
            entries.add(row);
        }

        if (fakeUsernames.isEmpty() == false)
        {
            JsonArray filtered = new JsonArray();
            fakeUsernames.stream().sorted().forEach(filtered::add);
            leaderboard.add("filtered_fake_usernames", filtered);
        }

        leaderboard.add("entries", entries);
        return leaderboard;
    }

    private static JsonObject buildSourceScan(MinecraftClient client, WorldSessionContext.WorldInfo worldInfo)
    {
        SourceScanResult scan = SourceScanManager.scan(client);
        if (scan == null || scan.hasMeaningfulEvidence() == false)
        {
            return null;
        }

        JsonObject object = new JsonObject();
        object.addProperty("compatible", scan.compatible());
        object.addProperty("confidence", scan.confidence());
        object.addProperty("scoreboard_title", scan.scoreboardTitle());

        if (scan.totalDigs() > 0L)
        {
            object.addProperty("total_digs", scan.totalDigs());
        }

        if (scan.playerTotalDigs() > 0L)
        {
            object.addProperty("player_total_digs", scan.playerTotalDigs());
        }

        object.addProperty("server_name", scan.sourceName());
        object.addProperty("source_key", ScoreboardSourceResolver.sourceKey(
                worldInfo != null ? worldInfo.displayName() : "",
                worldInfo));
        object.addProperty("icon_url", scan.iconUrl());
        object.addProperty("scan_fingerprint", scan.scanFingerprint());

        JsonArray sampleLines = new JsonArray();
        for (String line : scan.sampleSidebarLines())
        {
            sampleLines.add(line);
        }
        object.add("sample_sidebar_lines", sampleLines);

        JsonArray detectedFields = new JsonArray();
        for (String field : scan.detectedStatFields())
        {
            detectedFields.add(field);
        }
        object.add("detected_stat_fields", detectedFields);

        JsonObject evidence = new JsonObject();
        evidence.addProperty("source_name", scan.sourceName());
        evidence.addProperty("source_key", ScoreboardSourceResolver.sourceKey(
                worldInfo != null ? worldInfo.displayName() : "",
                worldInfo));
        evidence.addProperty("source_kind", worldInfo.kind());
        evidence.addProperty("source_host", (String) null);
        evidence.addProperty("icon_url", scan.iconUrl());
        evidence.addProperty("scoreboard_title", scan.scoreboardTitle());
        evidence.addProperty("total_digs", scan.totalDigs());
        evidence.addProperty("player_total_digs", scan.playerTotalDigs());
        evidence.addProperty("compatible", scan.compatible());
        evidence.addProperty("confidence", scan.confidence());
        evidence.add("sample_sidebar_lines", sampleLines.deepCopy());
        evidence.add("detected_stat_fields", detectedFields.deepCopy());
        object.add("raw_scan_evidence", evidence);

        return object;
    }

    private static JsonObject buildWorld(WorldSessionContext.WorldInfo worldInfo)
    {
        JsonObject world = new JsonObject();
        world.addProperty("key", worldInfo.id());
        world.addProperty("display_name", worldInfo.displayName());
        world.addProperty("kind", normaliseWorldKind(worldInfo.kind()));
        world.addProperty("host", (String) null);
        world.addProperty("source_key", ScoreboardSourceResolver.sourceKey(worldInfo.displayName(), worldInfo));
        world.addProperty("source_name", ScoreboardSourceResolver.displayName(worldInfo.displayName(), worldInfo));
        return world;
    }

    private static JsonObject buildWorld(String worldId, String displayName)
    {
        Configs.WorldStatsEntry worldStats = findWorldStats(worldId);
        String resolvedWorldId = worldId == null || worldId.isBlank() ? "default" : worldId;
        String resolvedDisplayName = displayName != null && displayName.isBlank() == false
                ? displayName
                : worldStats != null && worldStats.displayName != null && worldStats.displayName.isBlank() == false
                ? worldStats.displayName
                : resolvedWorldId;

        JsonObject world = new JsonObject();
        world.addProperty("key", resolvedWorldId);
        world.addProperty("display_name", resolvedDisplayName);
        world.addProperty("kind", normaliseWorldKind(worldStats == null ? "unknown" : worldStats.kind));
        world.addProperty("host", (String) null);
        world.addProperty("source_key", resolvedWorldId);
        world.addProperty("source_name", resolvedDisplayName);
        return world;
    }

    private static Configs.WorldStatsEntry findWorldStats(String worldId)
    {
        if (worldId == null || worldId.isBlank())
        {
            return null;
        }

        for (Configs.WorldStatsEntry entry : Configs.WORLD_STATS)
        {
            if (worldId.equals(entry.worldId))
            {
                return entry;
            }
        }

        return null;
    }

    private static JsonArray buildProjects()
    {
        JsonArray projects = new JsonArray();
        ProjectEntry activeProject = Configs.getActiveProject();

        for (ProjectEntry project : Configs.PROJECTS)
        {
            JsonObject entry = new JsonObject();
            entry.addProperty("project_key", project.id);
            entry.addProperty("name", project.name);
            entry.addProperty("progress", project.progress);
            entry.addProperty("goal", (String) null);
            entry.addProperty("is_active", activeProject != null && activeProject.id.equals(project.id));
            projects.add(entry);
        }

        return projects;
    }

    private static JsonObject buildDailyGoal(MiningStats.GoalProgress dailyGoal)
    {
        JsonObject goal = new JsonObject();
        goal.addProperty("goal_date", PeriodKeys.currentDailyKey(System.currentTimeMillis()));
        goal.addProperty("target", dailyGoal.target());
        goal.addProperty("progress", dailyGoal.current());
        goal.addProperty("completed", dailyGoal.target() > 0L && dailyGoal.current() >= dailyGoal.target());
        return goal;
    }

    private static JsonObject buildSyncedStats(MiningStats.ProjectProgress projectProgress,
                                               MiningStats.GoalProgress dailyGoal)
    {
        JsonObject syncedStats = new JsonObject();
        syncedStats.addProperty("blocks_per_hour", MiningStats.getEstimatedBlocksPerHour());
        syncedStats.addProperty("estimated_finish_seconds", (String) null);
        syncedStats.addProperty("current_project_name", projectProgress.name());
        syncedStats.addProperty("current_project_progress", projectProgress.blocksMined());
        syncedStats.addProperty("current_project_goal", (String) null);
        syncedStats.addProperty("daily_progress", dailyGoal.current());
        syncedStats.addProperty("daily_target", dailyGoal.target());
        syncedStats.addProperty("daily_blocks_mined", MiningStats.getDailyBlocksMined());
        syncedStats.addProperty("weekly_blocks_mined", MiningStats.getWeeklyBlocksMined());
        syncedStats.addProperty("personal_record_daily_blocks", MiningStats.getPersonalRecordDailyBlocks());
        syncedStats.addProperty("personal_record_weekly_blocks", MiningStats.getPersonalRecordWeeklyBlocks());
        syncedStats.addProperty("fastest_100k_seconds", MiningStats.getFastest100kSeconds());
        return syncedStats;
    }

    private static JsonObject buildSession(SessionData session, String status)
    {
        JsonObject sessionObject = new JsonObject();
        sessionObject.addProperty("session_key", sessionKey(session));
        sessionObject.addProperty("started_at", toIso(session.startTimeMs));
        sessionObject.addProperty("ended_at", "ended".equals(status) ? toIso(session.endTimeMs) : null);
        sessionObject.addProperty("active_seconds", session.getDurationMs() / 1000L);
        sessionObject.addProperty("total_blocks", session.totalBlocks);
        sessionObject.addProperty("average_bph", session.getAverageBlocksPerHour());
        sessionObject.addProperty("peak_bph", session.getPeakBlocksPerHour());
        sessionObject.addProperty("best_streak_seconds", session.bestStreakSeconds);
        Map<String, Long> sanitizedBreakdown = Configs.sanitizeBlockBreakdown(session.blockBreakdown);
        sessionObject.addProperty("top_block", getTopBlock(sanitizedBreakdown));
        sessionObject.addProperty("status", status);
        sessionObject.add("block_breakdown", buildBreakdown(sanitizedBreakdown));
        sessionObject.add("rate_points", buildRatePoints(session.miningRateBuckets));
        return sessionObject;
    }

    private static String sessionKey(SessionData session)
    {
        return "sess_" + session.startTimeMs;
    }

    private static void markSyncedSessions(JsonObject payload, String responseBody)
    {
        if (payload == null)
        {
            return;
        }

        if (payload.has("session") && payload.get("session").isJsonObject())
        {
            markSyncedSession(payload.getAsJsonObject("session"), responseBody);
        }

        if (payload.has("sessions") && payload.get("sessions").isJsonArray())
        {
            JsonArray sessions = payload.getAsJsonArray("sessions");
            for (JsonElement element : sessions)
            {
                if (element != null && element.isJsonObject())
                {
                    markSyncedSession(element.getAsJsonObject(), responseBody);
                }
            }
        }
    }

    private static void markSyncedSession(JsonObject session, String responseBody)
    {
        if (session.has("status") == false
                || session.get("status").isJsonPrimitive() == false
                || "ended".equals(session.get("status").getAsString()) == false)
        {
            return;
        }

        if (session.has("session_key") && session.get("session_key").isJsonPrimitive())
        {
            String sessionKey = session.get("session_key").getAsString();
            if (serverStoredSession(responseBody, sessionKey))
            {
                SessionSyncState.markSynced(sessionKey);
            }
        }
    }

    private static boolean serverStoredSession(String responseBody, String sessionKey)
    {
        if (responseBody == null || responseBody.isBlank())
        {
            return false;
        }

        try
        {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            if (root.has("sessions") && root.get("sessions").isJsonArray())
            {
                JsonArray sessions = root.getAsJsonArray("sessions");
                for (JsonElement element : sessions)
                {
                    if (element != null && element.isJsonObject()
                            && sessionAckStored(element.getAsJsonObject(), sessionKey))
                    {
                        return true;
                    }
                }
            }

            if (root.has("session") && root.get("session").isJsonObject())
            {
                return sessionAckStored(root.getAsJsonObject("session"), sessionKey);
            }

            return false;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private static boolean sessionAckStored(JsonObject syncSession, String sessionKey)
    {
        if (syncSession == null)
        {
            return false;
        }

        try
        {
            if (sessionKey == null
                    || syncSession.has("session_key") == false
                    || syncSession.get("session_key").isJsonPrimitive() == false)
            {
                return false;
            }

            if (sessionKey.equals(syncSession.get("session_key").getAsString()) == false)
            {
                return false;
            }

            return syncSession.has("stored")
                    && syncSession.get("stored").isJsonPrimitive()
                    && syncSession.get("stored").getAsBoolean();
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private static JsonArray buildBreakdown(Map<String, Long> breakdown)
    {
        JsonArray array = new JsonArray();
        breakdown.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .forEach(entry -> {
                    JsonObject item = new JsonObject();
                    item.addProperty("block_id", entry.getKey());
                    item.addProperty("count", entry.getValue());
                    array.add(item);
                });
        return array;
    }

    private static JsonArray buildRatePoints(List<Integer> rateBuckets)
    {
        JsonArray array = new JsonArray();
        List<Integer> buckets = rateBuckets == null ? List.of() : new ArrayList<>(rateBuckets);

        for (int index = 0; index < buckets.size(); index++)
        {
            JsonObject point = new JsonObject();
            point.addProperty("point_index", index);
            point.addProperty("blocks_per_hour", Math.max(0, buckets.get(index)) * 60);
            point.addProperty("elapsed_seconds", (index + 1) * 60);
            array.add(point);
        }

        return array;
    }

    private static String getTopBlock(Map<String, Long> breakdown)
    {
        return breakdown.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private static String normaliseWorldKind(String kind)
    {
        if ("singleplayer".equals(kind) || "multiplayer".equals(kind) || "realm".equals(kind))
        {
            return kind;
        }

        return "unknown";
    }

    private static String resolveUsername(MinecraftClient client)
    {
        if (client == null)
        {
            return "Player";
        }

        try
        {
            String username = client.getSession().getUsername();
            if (username != null && username.isBlank() == false)
            {
                return username;
            }
        }
        catch (Exception ignored)
        {
        }

        if (client.player != null)
        {
            return client.player.getName().getString();
        }

        return "Player";
    }

    private static boolean hasSessionToken(MinecraftClient client)
    {
        if (client == null || client.getSession() == null)
        {
            return false;
        }

        try
        {
            String token = client.getSession().getAccessToken();
            return token != null && token.isBlank() == false;
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    private static void maybeBootstrapFromLeaderboardSnapshot(MinecraftClient client, long now)
    {
        if (latestLeaderboardSnapshot == null || latestLeaderboardSnapshot.isValid() == false || client == null || client.player == null)
        {
            return;
        }

        String username = client.player.getGameProfile().getName();
        long localPlayerDigs = latestLeaderboardSnapshot.entries().stream()
                .filter(entry -> entry.username().equalsIgnoreCase(username))
                .mapToLong(SourceLeaderboardEntry::digs)
                .max()
                .orElse(0L);
        if (localPlayerDigs <= 0L)
        {
            return;
        }

        MiningStats.bootstrapSourceTotalFromScoreboard(localPlayerDigs, latestLeaderboardSnapshot.serverName(), now);
    }

    private static String toIso(long timeMs)
    {
        return Instant.ofEpochMilli(timeMs).toString();
    }

    private static String livePayloadFingerprint(JsonObject payload)
    {
        if (payload == null)
        {
            return null;
        }

        JsonObject minimal = new JsonObject();

        if (payload.has("world"))
        {
            minimal.add("world", payload.get("world"));
        }

        if (payload.has("current_world_totals"))
        {
            minimal.add("current_world_totals", payload.get("current_world_totals"));
        }

        if (payload.has("mining_records"))
        {
            minimal.add("mining_records", payload.get("mining_records"));
        }

        if (payload.has("current_world_block_breakdown"))
        {
            minimal.addProperty("current_world_block_breakdown", BlockBreakdownPayloads.fingerprint(payload.getAsJsonObject("current_world_block_breakdown")));
        }

        if (payload.has("server_player_block_breakdowns"))
        {
            minimal.addProperty("server_player_block_breakdowns", ServerPlayerBlockBreakdownScanner.fingerprint(payload.getAsJsonObject("server_player_block_breakdowns")));
        }

        if (payload.has("source_scan"))
        {
            minimal.add("source_scan", payload.get("source_scan"));
        }

        if (payload.has("source_leaderboard"))
        {
            minimal.add("source_leaderboard", payload.get("source_leaderboard"));
        }

        if (payload.has("session"))
        {
            minimal.add("session", payload.get("session"));
        }

        return GSON.toJson(minimal);
    }

    private static String leaderboardFingerprint(SourceLeaderboardSnapshot snapshot)
    {
        if (snapshot == null || snapshot.isValid() == false)
        {
            return "";
        }

        JsonObject object = new JsonObject();
        object.addProperty("server_name", snapshot.serverName());
        object.addProperty("objective_title", snapshot.objectiveTitle());
        object.addProperty("total_digs", snapshot.totalDigs());

        JsonArray entries = new JsonArray();
        snapshot.entries().stream()
                .sorted(Comparator.comparingInt(SourceLeaderboardEntry::rank))
                .forEach(entry -> {
                    JsonObject row = new JsonObject();
                    row.addProperty("username", entry.username());
                    row.addProperty("rank", entry.rank());
                    row.addProperty("digs", entry.digs());
                    entries.add(row);
                });

        object.add("entries", entries);
        return GSON.toJson(object);
    }

    private static void debugPayloadSource(WorldSessionContext.WorldInfo worldInfo, JsonObject payload)
    {
        JsonObject world = payload.has("world") ? payload.getAsJsonObject("world") : null;
        String sourceKey = world != null && world.has("source_key") ? world.get("source_key").getAsString() : "";
        String sourceName = world != null && world.has("source_name") ? world.get("source_name").getAsString() : "";
        lastPayloadSourceKey = sourceKey;
        lastPayloadSourceName = sourceName;

        if (MmmDebugLogger.shouldLog("cloud-sync-payload-created", 5_000L) == false)
        {
            return;
        }

        MMM.LOGGER.info(
                "[MMM_DEBUG] sync-payload-created worldKey={} worldName={} sourceKey={} sourceName={} sessionActive={}",
                worldInfo.id(),
                worldInfo.displayName(),
                sourceKey,
                sourceName,
                MiningStats.isSessionActive()
        );
    }

    private enum SyncStatus
    {
        CONNECTED,
        QUEUED,
        SYNCING,
        SYNCED,
        FAILED
    }
}
