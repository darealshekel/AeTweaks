package com.miningtrackeraddon.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miningtrackeraddon.Reference;
import com.miningtrackeraddon.MiningTrackerAddon;
import com.miningtrackeraddon.config.Configs;
import com.miningtrackeraddon.config.Configs.ProjectEntry;
import com.miningtrackeraddon.storage.SessionData;
import com.miningtrackeraddon.storage.WorldSessionContext;
import com.miningtrackeraddon.tracker.MiningStats;
import com.miningtrackeraddon.util.UiFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.MinecraftClient;

public final class CloudSyncManager
{
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final long HEARTBEAT_INTERVAL_MS = 30_000L;
    private static final long LIVE_BLOCK_SYNC_INTERVAL_MS = 3_000L;
    private static final long IDLE_BLOCK_SYNC_INTERVAL_MS = 1_000L;
    private static final long AETERNUM_SCOREBOARD_SYNC_INTERVAL_MS = 3_000L;
    private static final long HUD_FAILURE_GRACE_MS = 12_000L;
    private static final long HUD_HEALTH_STALE_MS = 90_000L;

    private static long lastHeartbeatMs;
    private static long lastLiveBlockSyncMs;
    private static long lastAeternumScoreboardSyncMs;
    private static volatile SyncStatus syncStatus = SyncStatus.CONNECTED;
    private static volatile String syncStatusDetail = "";
    private static volatile long lastHealthySignalMs;
    private static volatile long lastFailureSignalMs;
    private static AeternumLeaderboardSnapshot latestLeaderboardSnapshot;
    private static String lastQueuedLiveFingerprint;
    private static String lastSuccessfulLiveFingerprint;
    private static String lastSuccessfulLeaderboardFingerprint;
    private static volatile String lastPayloadSourceKey = "";
    private static volatile String lastPayloadSourceName = "";

    private CloudSyncManager()
    {
    }

    public static void onClientTick(long now)
    {
        if (canSync() == false || hasLiveContext() == false)
        {
            return;
        }

        if (now - lastHeartbeatMs >= HEARTBEAT_INTERVAL_MS)
        {
            syncHeartbeat();
        }

        MinecraftClient client = MinecraftClient.getInstance();
        latestLeaderboardSnapshot = AeternumLeaderboardReader.read(client);
        maybeBootstrapFromLeaderboardSnapshot(client, now);

        if (latestLeaderboardSnapshot != null && syncStatus != SyncStatus.SYNCING && syncStatus != SyncStatus.SYNCED)
        {
            syncStatus = SyncStatus.CONNECTED;
            syncStatusDetail = "Leaderboard detected";
            touchHealthy();
        }

        String currentLeaderboardFingerprint = leaderboardFingerprint(latestLeaderboardSnapshot);
        if (latestLeaderboardSnapshot != null
                && now - lastAeternumScoreboardSyncMs >= AETERNUM_SCOREBOARD_SYNC_INTERVAL_MS
                && currentLeaderboardFingerprint.equals(lastSuccessfulLeaderboardFingerprint) == false)
        {
            lastAeternumScoreboardSyncMs = now;
            SessionData liveSession = MiningStats.isSessionActive() ? MiningStats.getCurrentSession() : null;
            queueLivePayload(buildPayload(liveSession, liveSession == null ? null : getCurrentSessionStatus()));
        }
    }

    public static void syncHeartbeat()
    {
        if (canSync() == false || hasLiveContext() == false)
        {
            return;
        }

        lastHeartbeatMs = System.currentTimeMillis();
        SessionData liveSession = MiningStats.isSessionActive() ? MiningStats.getCurrentSession() : null;
        queueLivePayload(buildPayload(liveSession, liveSession == null ? null : getCurrentSessionStatus()));
    }

    public static void syncFinishedSession(SessionData session)
    {
        if (canSync() == false || session == null)
        {
            return;
        }

        JsonObject payload = buildPayload(session, "ended");
        SyncQueueManager.enqueueCloudFinishedSession("sess_" + session.startTimeMs, payload);
    }

    public static void onBlockMined(long now)
    {
        if (canSync() == false || hasLiveContext() == false)
        {
            return;
        }

        long interval = MiningStats.isSessionActive() ? LIVE_BLOCK_SYNC_INTERVAL_MS : IDLE_BLOCK_SYNC_INTERVAL_MS;
        if (now - lastLiveBlockSyncMs >= interval)
        {
            lastLiveBlockSyncMs = now;
            lastHeartbeatMs = now;
            SessionData liveSession = MiningStats.isSessionActive() ? MiningStats.getCurrentSession() : null;
            queueLivePayload(buildPayload(liveSession, liveSession == null ? null : getCurrentSessionStatus()));
        }
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
        lastAeternumScoreboardSyncMs = 0L;
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

    private static void queueLivePayload(JsonObject payload)
    {
        String fingerprint = livePayloadFingerprint(payload);

        if (fingerprint != null
                && fingerprint.equals(lastSuccessfulLiveFingerprint)
                && syncStatus == SyncStatus.SYNCED)
        {
            return;
        }

        if (fingerprint != null
                && fingerprint.equals(lastQueuedLiveFingerprint)
                && syncStatus == SyncStatus.QUEUED)
        {
            return;
        }

        lastQueuedLiveFingerprint = fingerprint;
        SyncQueueManager.enqueueCloudLiveState(payload);
    }

    private static void touchHealthy()
    {
        lastHealthySignalMs = System.currentTimeMillis();
        lastFailureSignalMs = 0L;
    }

    private static boolean canSync()
    {
        return Configs.Generic.WEBSITE_SYNC_ENABLED.getBooleanValue()
                && Configs.cloudSyncEndpoint != null
                && Configs.cloudSyncEndpoint.isBlank() == false;
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

    private static JsonObject buildPayload(SessionData session, String sessionStatus)
    {
        MinecraftClient client = MinecraftClient.getInstance();
        WorldSessionContext.WorldInfo worldInfo = WorldSessionContext.getCurrentWorldInfo();
        MiningStats.GoalProgress dailyGoal = MiningStats.getDailyGoalProgress();
        MiningStats.ProjectProgress projectProgress = MiningStats.getActiveProjectProgress();
        MiningStats.PredictionSnapshot prediction = MiningStats.getPredictionSnapshot();

        JsonObject payload = new JsonObject();
        payload.addProperty("client_id", Configs.cloudClientId);
        payload.addProperty("minecraft_uuid", client != null && client.player != null ? client.player.getUuidAsString() : null);
        payload.addProperty("username", resolveUsername(client));
        payload.addProperty("mod_version", Reference.MOD_VERSION);
        payload.addProperty("minecraft_version", client != null ? client.getGameVersion() : null);
        payload.add("world", buildWorld(worldInfo));
        payload.add("lifetime_totals", buildLifetimeTotals());
        payload.add("current_world_totals", buildCurrentWorldTotals(worldInfo));

        JsonObject sourceScan = buildSourceScan(client, worldInfo);
        if (sourceScan != null)
        {
            payload.add("source_scan", sourceScan);
        }

        JsonObject aeternumLeaderboard = buildAeternumLeaderboard();
        if (aeternumLeaderboard != null)
        {
            payload.add("aeternum_leaderboard", aeternumLeaderboard);
        }

        payload.add("projects", buildProjects());
        payload.add("daily_goal", buildDailyGoal(dailyGoal));
        payload.add("synced_stats", buildSyncedStats(projectProgress, dailyGoal, prediction));

        if (session != null && sessionStatus != null)
        {
            payload.add("session", buildSession(session, sessionStatus));
        }

        debugPayloadSource(worldInfo, payload);

        return payload;
    }

    private static JsonObject buildLifetimeTotals()
    {
        JsonObject totals = new JsonObject();
        totals.addProperty("total_blocks", Configs.totalBlocksMined);
        return totals;
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
        totals.addProperty("host", worldStats.host == null || worldStats.host.isBlank() ? null : worldStats.host);
        totals.addProperty("total_blocks", worldStats.totalBlocks);
        totals.addProperty("last_seen_at", toIso(Math.max(worldStats.lastSeenAt, System.currentTimeMillis())));
        return totals;
    }

    private static JsonObject buildAeternumLeaderboard()
    {
        if (latestLeaderboardSnapshot == null || latestLeaderboardSnapshot.isValid() == false)
        {
            return null;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        Set<String> fakeUsernames = CarpetFakePlayerDetector.findLikelyFakeUsernames(client, latestLeaderboardSnapshot.entries());

        List<AeternumLeaderboardEntry> realEntries = latestLeaderboardSnapshot.entries().stream()
                .filter(entry -> entry.isValid())
                .filter(entry -> fakeUsernames.contains(entry.username().toLowerCase(Locale.ROOT)) == false)
                .sorted(Comparator.comparingInt(AeternumLeaderboardEntry::rank))
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

        long filteredTotalDigs = realEntries.stream().mapToLong(AeternumLeaderboardEntry::digs).sum();
        if (filteredTotalDigs > 0L)
        {
            leaderboard.addProperty("total_digs", filteredTotalDigs);
        }

        JsonArray entries = new JsonArray();
        for (AeternumLeaderboardEntry entry : realEntries)
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
        evidence.addProperty("source_host", worldInfo.host().isBlank() ? null : worldInfo.host());
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
        world.addProperty("host", worldInfo.host().isBlank() ? null : worldInfo.host());
        world.addProperty("source_key", ScoreboardSourceResolver.sourceKey(worldInfo.displayName(), worldInfo));
        world.addProperty("source_name", ScoreboardSourceResolver.displayName(worldInfo.displayName(), worldInfo));
        return world;
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
        goal.addProperty("goal_date", LocalDate.now(ZoneId.systemDefault()).toString());
        goal.addProperty("target", dailyGoal.target());
        goal.addProperty("progress", dailyGoal.current());
        goal.addProperty("completed", dailyGoal.target() > 0L && dailyGoal.current() >= dailyGoal.target());
        return goal;
    }

    private static JsonObject buildSyncedStats(MiningStats.ProjectProgress projectProgress,
                                               MiningStats.GoalProgress dailyGoal,
                                               MiningStats.PredictionSnapshot prediction)
    {
        JsonObject syncedStats = new JsonObject();
        syncedStats.addProperty("blocks_per_hour", (int) Math.round(prediction.blocksPerHour()));
        syncedStats.addProperty("estimated_finish_seconds", (String) null);
        syncedStats.addProperty("current_project_name", projectProgress.name());
        syncedStats.addProperty("current_project_progress", projectProgress.blocksMined());
        syncedStats.addProperty("current_project_goal", (String) null);
        syncedStats.addProperty("daily_progress", dailyGoal.current());
        syncedStats.addProperty("daily_target", dailyGoal.target());
        return syncedStats;
    }

    private static JsonObject buildSession(SessionData session, String status)
    {
        JsonObject sessionObject = new JsonObject();
        sessionObject.addProperty("session_key", "sess_" + session.startTimeMs);
        sessionObject.addProperty("started_at", toIso(session.startTimeMs));
        sessionObject.addProperty("ended_at", "ended".equals(status) ? toIso(session.endTimeMs) : null);
        sessionObject.addProperty("active_seconds", session.getDurationMs() / 1000L);
        sessionObject.addProperty("total_blocks", session.totalBlocks);
        sessionObject.addProperty("average_bph", session.getAverageBlocksPerHour());
        sessionObject.addProperty("peak_bph", session.peakBlocksPerHour);
        sessionObject.addProperty("best_streak_seconds", session.bestStreakSeconds);
        sessionObject.addProperty("top_block", getTopBlock(session.blockBreakdown));
        sessionObject.addProperty("status", status);
        sessionObject.add("block_breakdown", buildBreakdown(session.blockBreakdown));
        sessionObject.add("rate_points", buildRatePoints(session.miningRateBuckets));
        return sessionObject;
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

    private static void maybeBootstrapFromLeaderboardSnapshot(MinecraftClient client, long now)
    {
        if (latestLeaderboardSnapshot == null || latestLeaderboardSnapshot.isValid() == false || client == null || client.player == null)
        {
            return;
        }

        String username = client.player.getGameProfile().getName();
        long localPlayerDigs = latestLeaderboardSnapshot.entries().stream()
                .filter(entry -> entry.username().equalsIgnoreCase(username))
                .mapToLong(AeternumLeaderboardEntry::digs)
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

        if (payload.has("source_scan"))
        {
            minimal.add("source_scan", payload.get("source_scan"));
        }

        if (payload.has("aeternum_leaderboard"))
        {
            minimal.add("aeternum_leaderboard", payload.get("aeternum_leaderboard"));
        }

        if (payload.has("session"))
        {
            minimal.add("session", payload.get("session"));
        }

        return GSON.toJson(minimal);
    }

    private static String leaderboardFingerprint(AeternumLeaderboardSnapshot snapshot)
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
                .sorted(Comparator.comparingInt(AeternumLeaderboardEntry::rank))
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

        if (Configs.Generic.WEBSITE_SYNC_DEBUG.getBooleanValue() == false)
        {
            return;
        }

        MiningTrackerAddon.LOGGER.info(
                "[AET_DEBUG] sync-payload-created worldKey={} worldName={} sourceKey={} sourceName={} sessionActive={}",
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
