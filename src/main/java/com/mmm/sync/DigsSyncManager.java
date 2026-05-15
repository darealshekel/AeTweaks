package com.mmm.sync;

import com.google.gson.JsonObject;
import com.mmm.MMM;
import com.mmm.Reference;
import com.mmm.config.Configs;
import com.mmm.storage.SessionData;
import com.mmm.storage.WorldSessionContext;
import com.mmm.tracker.MiningStats;
import com.mmm.tracker.MiningValidationTracker;
import java.time.Instant;
import java.util.Locale;
import net.minecraft.client.MinecraftClient;

public final class DigsSyncManager
{
    private static final String LOG_PREFIX = "[MMM_SYNC]";
    private static final long HUD_FAILURE_GRACE_MS = 12_000L;
    private static final long HUD_HEALTH_STALE_MS = 90_000L;
    private static final long AUTHORITATIVE_MODEL_STALE_MS = 15_000L;
    private static final long SYNC_UNAVAILABLE_LOG_INTERVAL_MS = 30_000L;

    private static PlayerDigsModel latestModel;
    private static String lastQueuedFingerprint;
    private static String lastSuccessfulFingerprint;
    private static long lastQueueAttemptMs;
    private static volatile SyncStatus status = SyncStatus.CONNECTED;
    private static volatile long lastHealthySignalMs;
    private static volatile long lastFailureSignalMs;

    private static volatile long debugSidebarTotal;
    private static volatile long debugTabTotal;
    private static volatile long debugChosenTotal;
    private static volatile String debugChosenSource = "none";
    private static volatile String debugSidebarSample = "";
    private static volatile String debugTabSample = "";
    private static volatile String debugSidebarObjective = "";
    private static volatile String debugTabObjective = "";
    private static volatile String debugSidebarMatchedUser = "";
    private static volatile String debugTabMatchedUser = "";
    private static volatile long debugSidebarRawScore;
    private static volatile long debugTabRawScore;
    private static volatile String debugSkipReason = "";
    private static volatile long lastSyncUnavailableLogMs;
    private static volatile String lastSyncUnavailableReason = "";

    private DigsSyncManager()
    {
    }

    public static void onClientTick(long now)
    {
        clearStaleModel(now);

        MinecraftClient client = MinecraftClient.getInstance();
        PersonalTotalDetector.Detection detection = PersonalTotalDetector.detect(client);
        PlayerDigsModel parserModel = PlayerDigsParser.parse(client);
        TotalSelection selection = selectAuthoritativeTotal(client, detection, parserModel, now);
        applyDetectionDebug(detection, selection);

        if (selection.model() != null)
        {
            latestModel = selection.model();
            MiningStats.bootstrapSourceTotalFromScoreboard(latestModel.totalDigs(), latestModel.server(), now);
            MiningStats.applyScoreboardTotalMined(latestModel.totalDigs(), now);
            if (status != SyncStatus.SYNCED)
            {
                status = SyncStatus.CONNECTED;
            }
            touchHealthy();
        }

        if (latestModel == null || latestModel.isValid() == false)
        {
            return;
        }

        if (Configs.Generic.TOTAL_DIGS_SYNC_ENABLED.getBooleanValue() == false)
        {
            logSyncUnavailable("totalDigsSyncEnabled_false");
            return;
        }

        if (canSync() == false)
        {
            return;
        }

        WorldSessionContext.WorldInfo worldInfo = WorldSessionContext.getCurrentWorldInfo();
        String fingerprint = fingerprint(latestModel, BlockBreakdownPayloads.fingerprintCurrentWorldBlockBreakdown(worldInfo));
        boolean cadenceDue = lastQueueAttemptMs <= 0L || now - lastQueueAttemptMs >= CloudSyncManager.getSyncIntervalMs();
        if (fingerprint.equals(lastSuccessfulFingerprint) && status == SyncStatus.SYNCED)
        {
            return;
        }

        if (fingerprint.equals(lastQueuedFingerprint) && status == SyncStatus.QUEUED)
        {
            return;
        }

        if (cadenceDue == false)
        {
            return;
        }

        lastQueueAttemptMs = now;
        lastQueuedFingerprint = fingerprint;
        SyncQueueManager.enqueuePlayerTotalDigs(dedupeKey(latestModel), buildPayload(latestModel));
    }

    static void onQueued(JsonObject payload)
    {
        status = SyncStatus.QUEUED;
    }

    static void onQueueSuccess(JsonObject payload, String responseBody)
    {
        status = SyncStatus.SYNCED;
        touchHealthy();
        CloudSyncManager.applySuccessfulSyncResponse(responseBody);
        SyncDeltaStore.markPayloadSynced(payload);
        lastSuccessfulFingerprint = latestModel == null ? fingerprint(payload) : fingerprint(latestModel);
        lastQueuedFingerprint = lastSuccessfulFingerprint;
    }

    public static void syncNow(String reason)
    {
        long now = System.currentTimeMillis();
        if (latestModel == null
                || latestModel.isValid() == false
                || now - latestModel.capturedAtMs() > AUTHORITATIVE_MODEL_STALE_MS
                || canSync() == false)
        {
            return;
        }

        lastQueueAttemptMs = now;
        lastQueuedFingerprint = fingerprint(latestModel);
        SyncQueueManager.enqueuePlayerTotalDigs(dedupeKey(latestModel), buildPayload(latestModel));
        SyncQueueManager.forceFlush(reason == null || reason.isBlank() ? "total digs sync" : reason);
    }

    static void onQueueRetry(String detail, long nextRetryAtMs)
    {
        status = SyncStatus.FAILED;
        lastFailureSignalMs = System.currentTimeMillis();
    }

    static void onQueueDropped(String detail)
    {
        status = SyncStatus.FAILED;
        lastFailureSignalMs = System.currentTimeMillis();
    }

    public static boolean isHudHealthy(long now)
    {
        if (Configs.Generic.WEBSITE_SYNC_ENABLED.getBooleanValue() == false
                || Configs.Generic.TOTAL_DIGS_SYNC_ENABLED.getBooleanValue() == false
                || Configs.cloudSyncEndpoint == null
                || Configs.cloudSyncEndpoint.isBlank())
        {
            return false;
        }

        PendingSyncQueue.Snapshot snapshot = SyncQueueManager.getSnapshot();
        int pending = snapshot.countFor(SyncItemType.PLAYER_TOTAL_DIGS);
        long recentHealthyMs = Math.max(lastHealthySignalMs, snapshot.lastSuccessfulSyncAtMs());

        if (snapshot.flushActive() && pending > 0)
        {
            return true;
        }

        if (status == SyncStatus.FAILED)
        {
            return recentHealthyMs > 0L && now - recentHealthyMs <= HUD_FAILURE_GRACE_MS;
        }

        if (pending > 0 && recentHealthyMs > 0L && now - recentHealthyMs > HUD_FAILURE_GRACE_MS)
        {
            return false;
        }

        return recentHealthyMs > 0L && now - recentHealthyMs <= HUD_HEALTH_STALE_MS;
    }

    private static TotalSelection selectAuthoritativeTotal(MinecraftClient client,
                                                           PersonalTotalDetector.Detection detection,
                                                           PlayerDigsModel parserModel,
                                                           long now)
    {
        WorldSessionContext.WorldInfo worldInfo = WorldSessionContext.getCurrentWorldInfo();
        String sourceName = ScoreboardSourceResolver.displayName(worldInfo != null ? worldInfo.displayName() : "", worldInfo);

        long tabTotal = Math.max(0L, detection.tabTotal());
        long sidebarTotal = Math.max(0L, detection.sidebarTotal());
        long parserTotal = parserModel != null && parserModel.isValid() ? Math.max(0L, parserModel.totalDigs()) : 0L;
        long cachedTotal = Math.max(0L, MiningStats.getCurrentSourceTotalMined());

        Candidate chosen = chooseBestCandidate(tabTotal, sidebarTotal, parserTotal, cachedTotal);
        if (!chosen.valid())
        {
            return new TotalSelection(sourceName, null, chosen.reason());
        }

        String username = resolveUsername(client, parserModel);
        String objectiveTitle = resolveObjectiveTitle(parserModel, detection);
        PlayerDigsModel model = new PlayerDigsModel(username, chosen.total(), now, sourceName, objectiveTitle);
        return new TotalSelection(sourceName, model, chosen.reason());
    }

    private static Candidate chooseBestCandidate(long tabTotal, long sidebarTotal, long parserTotal, long cachedTotal)
    {
        // Reject tiny ambiguous totals when stronger evidence exists.
        long strongest = Math.max(Math.max(tabTotal, sidebarTotal), Math.max(parserTotal, cachedTotal));

        Candidate tab = validate("tab", tabTotal, strongest);
        Candidate sidebar = validate("sidebar", sidebarTotal, strongest);
        Candidate parser = validate("parser", parserTotal, strongest);
        Candidate cached = validate("cached", cachedTotal, strongest);

        Candidate live = strongestValid(tab, sidebar, parser);
        if (live.valid()) return live;
        if (cached.valid()) return cached;

        return new Candidate("none", 0L, false, "no-valid-total");
    }

    private static Candidate strongestValid(Candidate... candidates)
    {
        Candidate best = new Candidate("none", 0L, false, "no-valid-live-total");
        for (Candidate candidate : candidates)
        {
            if (candidate.valid() == false)
            {
                continue;
            }

            if (best.valid() == false || candidate.total() > best.total())
            {
                best = candidate;
            }
        }
        return best;
    }

    private static Candidate validate(String source, long total, long strongest)
    {
        if (total <= 0L)
        {
            return new Candidate(source, 0L, false, "missing");
        }

        if (total < 50L && strongest >= 1_000L)
        {
            return new Candidate(source, total, false, "tiny-rejected");
        }

        return new Candidate(source, total, true, "accepted");
    }

    private static JsonObject buildPayload(PlayerDigsModel model)
    {
        MinecraftClient client = MinecraftClient.getInstance();
        WorldSessionContext.WorldInfo worldInfo = WorldSessionContext.getCurrentWorldInfo();

        JsonObject payload = new JsonObject();
        payload.addProperty("client_id", Configs.cloudClientId);
        payload.addProperty("username", model.username());
        payload.addProperty("minecraft_uuid", client != null && client.player != null ? client.player.getUuidAsString() : null);
        payload.addProperty("mod_version", Reference.MOD_VERSION);
        payload.addProperty("minecraft_version", client != null ? client.getGameVersion() : null);

        JsonObject world = new JsonObject();
        world.addProperty("key", worldInfo.id());
        world.addProperty("display_name", worldInfo.displayName());
        world.addProperty("kind", normaliseWorldKind(worldInfo.kind()));
        world.addProperty("host", (String) null);
        world.addProperty("source_key", ScoreboardSourceResolver.sourceKey(worldInfo.displayName(), worldInfo));
        world.addProperty("source_name", ScoreboardSourceResolver.displayName(worldInfo.displayName(), worldInfo));
        payload.add("world", world);
        payload.add("current_world_totals", buildCurrentWorldTotals(worldInfo, model.totalDigs()));
        payload.add("mining_records", buildMiningRecords());

        JsonObject currentWorldBlockBreakdown = BlockBreakdownPayloads.buildCurrentWorldBlockBreakdown(worldInfo);
        if (currentWorldBlockBreakdown != null)
        {
            JsonObject syncBreakdown = SyncDeltaStore.currentWorldBlockBreakdownForSync(currentWorldBlockBreakdown);
            if (syncBreakdown != null)
            {
                payload.add("current_world_block_breakdown", syncBreakdown);
            }
        }

        JsonObject digs = new JsonObject();
        digs.addProperty("username", model.username());
        digs.addProperty("total_digs", model.totalDigs());
        digs.addProperty("server", model.server());
        digs.addProperty("timestamp", Instant.ofEpochMilli(model.capturedAtMs()).toString());
        digs.addProperty("objective_title", model.objectiveTitle());
        payload.add("player_total_digs", digs);

        SessionData currentSession = MiningStats.getCurrentSession();
        long sessionStartMs = currentSession == null ? 0L : currentSession.startTimeMs;
        long sessionEndMs = currentSession == null ? 0L : currentSession.endTimeMs;
        payload.add("validation", MiningValidationTracker.buildPayload(
                model.username(),
                client != null && client.player != null ? client.player.getUuidAsString() : "",
                worldInfo,
                MiningStats.getSessionBlocksMined(),
                sessionStartMs,
                sessionEndMs));

        return payload;
    }

    private static JsonObject buildCurrentWorldTotals(WorldSessionContext.WorldInfo worldInfo, long authoritativeTotal)
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
        totals.addProperty("total_blocks", Math.max(0L, authoritativeTotal));
        totals.addProperty("last_seen_at", Instant.ofEpochMilli(Math.max(worldStats.lastSeenAt, System.currentTimeMillis())).toString());
        return totals;
    }

    private static JsonObject buildMiningRecords()
    {
        JsonObject records = new JsonObject();
        records.addProperty("captured_at", Instant.ofEpochMilli(System.currentTimeMillis()).toString());
        records.addProperty("daily_blocks_date", MiningStats.getDailyBlocksDate());
        records.addProperty("weekly_blocks_week", MiningStats.getWeeklyBlocksWeek());
        records.addProperty("daily_blocks_mined", MiningStats.getDailyBlocksMined());
        records.addProperty("weekly_blocks_mined", MiningStats.getWeeklyBlocksMined());
        records.addProperty("personal_record_daily_blocks", MiningStats.getPersonalRecordDailyBlocks());
        records.addProperty("personal_record_weekly_blocks", MiningStats.getPersonalRecordWeeklyBlocks());
        records.addProperty("fastest_100k_seconds", MiningStats.getFastest100kSeconds());
        return records;
    }

    private static String normaliseWorldKind(String kind)
    {
        if ("singleplayer".equals(kind) || "multiplayer".equals(kind) || "realm".equals(kind))
        {
            return kind;
        }

        return "unknown";
    }

    private static String resolveUsername(MinecraftClient client, PlayerDigsModel parsed)
    {
        if (client != null && client.player != null)
        {
            return client.player.getGameProfile().getName();
        }
        if (parsed != null && parsed.username() != null && parsed.username().isBlank() == false)
        {
            return parsed.username();
        }
        return "Player";
    }

    private static String resolveObjectiveTitle(PlayerDigsModel parsed, PersonalTotalDetector.Detection detection)
    {
        if (parsed != null && parsed.objectiveTitle() != null && parsed.objectiveTitle().isBlank() == false)
        {
            return parsed.objectiveTitle();
        }
        if (detection.tabObjectiveTitle() != null && detection.tabObjectiveTitle().isBlank() == false)
        {
            return detection.tabObjectiveTitle();
        }
        if (detection.sidebarObjectiveTitle() != null && detection.sidebarObjectiveTitle().isBlank() == false)
        {
            return detection.sidebarObjectiveTitle();
        }
        return "Scoreboard";
    }

    private static boolean canSync()
    {
        if (Configs.Generic.WEBSITE_SYNC_ENABLED.getBooleanValue() == false)
        {
            logSyncUnavailable("websiteSyncEnabled_false");
            return false;
        }

        if (Configs.Generic.TOTAL_DIGS_SYNC_ENABLED.getBooleanValue() == false)
        {
            logSyncUnavailable("totalDigsSyncEnabled_false");
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
        if (reason.equals(lastSyncUnavailableReason) && now - lastSyncUnavailableLogMs < SYNC_UNAVAILABLE_LOG_INTERVAL_MS)
        {
            return;
        }

        lastSyncUnavailableReason = reason;
        lastSyncUnavailableLogMs = now;
        MMM.LOGGER.warn("{} total-digs-sync-disabled reason={} endpoint={}",
                LOG_PREFIX,
                reason,
                Configs.cloudSyncEndpoint == null ? "" : Configs.cloudSyncEndpoint);
    }

    private static String dedupeKey(PlayerDigsModel model)
    {
        return model.username().toLowerCase(Locale.ROOT) + "|" + model.server().toLowerCase(Locale.ROOT) + "|" + model.totalDigs();
    }

    private static String fingerprint(PlayerDigsModel model)
    {
        return fingerprint(model, BlockBreakdownPayloads.fingerprintCurrentWorldBlockBreakdown(WorldSessionContext.getCurrentWorldInfo()));
    }

    private static String fingerprint(PlayerDigsModel model, String blockBreakdownFingerprint)
    {
        return dedupeKey(model) + "|" + currentWorldTotalFingerprint(WorldSessionContext.getCurrentWorldInfo()) + "|"
                + (blockBreakdownFingerprint == null ? "" : blockBreakdownFingerprint);
    }

    private static String fingerprint(JsonObject payload)
    {
        if (payload == null || payload.has("player_total_digs") == false)
        {
            return "";
        }

        JsonObject digs = payload.getAsJsonObject("player_total_digs");
        String username = digs.has("username") ? digs.get("username").getAsString() : "";
        String server = digs.has("server") ? digs.get("server").getAsString() : "";
        long total = digs.has("total_digs") ? digs.get("total_digs").getAsLong() : 0L;
        long worldTotal = payload.has("current_world_totals") && payload.get("current_world_totals").isJsonObject()
                ? payload.getAsJsonObject("current_world_totals").has("total_blocks")
                    ? payload.getAsJsonObject("current_world_totals").get("total_blocks").getAsLong()
                    : -1L
                : -1L;
        String blockBreakdownFingerprint = payload.has("current_world_block_breakdown")
                ? BlockBreakdownPayloads.fingerprint(payload.getAsJsonObject("current_world_block_breakdown"))
                : "";
        return username.toLowerCase(Locale.ROOT) + "|" + server.toLowerCase(Locale.ROOT) + "|" + total + "|"
                + worldTotal + "|" + blockBreakdownFingerprint;
    }

    private static long currentWorldTotalFingerprint(WorldSessionContext.WorldInfo worldInfo)
    {
        Configs.WorldStatsEntry worldStats = Configs.getOrCreateWorldStats(
                worldInfo.id(),
                worldInfo.displayName(),
                worldInfo.kind(),
                worldInfo.host());
        return Math.max(0L, worldStats.totalBlocks);
    }

    private static void clearStaleModel(long now)
    {
        if (latestModel == null)
        {
            return;
        }

        if (now - latestModel.capturedAtMs() > AUTHORITATIVE_MODEL_STALE_MS)
        {
            latestModel = null;
        }
    }

    private static void applyDetectionDebug(PersonalTotalDetector.Detection detection, TotalSelection selection)
    {
        debugSidebarTotal = detection.sidebarTotal();
        debugTabTotal = detection.tabTotal();
        debugChosenTotal = selection.model() == null ? 0L : selection.model().totalDigs();
        debugChosenSource = selection.model() == null ? "none" : selection.reason();
        debugSidebarObjective = detection.sidebarObjectiveTitle();
        debugTabObjective = detection.tabObjectiveTitle();
        debugSidebarMatchedUser = detection.sidebarMatchedUsername();
        debugTabMatchedUser = detection.tabMatchedUsername();
        debugSidebarRawScore = detection.sidebarRawScore();
        debugTabRawScore = detection.tabRawScore();
        debugSidebarSample = detection.sidebarRenderedText();
        debugTabSample = detection.tabRenderedText();
        debugSkipReason = selection.model() == null ? selection.reason() : "";
    }

    public static String getStatusLabel()
    {
        PendingSyncQueue.Snapshot snapshot = SyncQueueManager.getSnapshot();
        int pending = snapshot.countFor(SyncItemType.PLAYER_TOTAL_DIGS);
        if (snapshot.flushActive() && pending > 0)
        {
            return "Syncing";
        }
        if (pending > 0)
        {
            return "Queued";
        }

        return switch (status)
        {
            case SYNCED -> "Synced";
            case FAILED -> "Retrying";
            default -> "Connected";
        };
    }

    public static void resetForDisconnect()
    {
        latestModel = null;
        lastQueueAttemptMs = 0L;
        status = SyncStatus.CONNECTED;
        lastFailureSignalMs = 0L;
        lastQueuedFingerprint = null;
        lastSuccessfulFingerprint = null;
        clearDebug();
    }

    public static void resetForWorldChange(String worldId)
    {
        resetForDisconnect();
    }

    public static boolean hasAuthoritativeTotalDigs()
    {
        if (latestModel == null || latestModel.isValid() == false)
        {
            return false;
        }

        long now = System.currentTimeMillis();
        if (now - latestModel.capturedAtMs() > AUTHORITATIVE_MODEL_STALE_MS)
        {
            return false;
        }

        WorldSessionContext.WorldInfo worldInfo = WorldSessionContext.getCurrentWorldInfo();
        String currentSourceName = ScoreboardSourceResolver.displayName(worldInfo != null ? worldInfo.displayName() : "", worldInfo);
        return currentSourceName.equalsIgnoreCase(latestModel.server());
    }

    public static long getDebugSidebarTotal() { return debugSidebarTotal; }
    public static long getDebugTabTotal() { return debugTabTotal; }
    public static long getDebugChosenTotal() { return debugChosenTotal; }
    public static String getDebugChosenSource() { return debugChosenSource; }
    public static String getDebugSidebarSample() { return debugSidebarSample; }
    public static String getDebugTabSample() { return debugTabSample; }
    public static String getDebugSkipReason() { return debugSkipReason; }
    public static String getDebugSidebarObjective() { return debugSidebarObjective; }
    public static String getDebugTabObjective() { return debugTabObjective; }
    public static String getDebugSidebarMatchedUser() { return debugSidebarMatchedUser; }
    public static String getDebugTabMatchedUser() { return debugTabMatchedUser; }
    public static long getDebugSidebarRawScore() { return debugSidebarRawScore; }
    public static long getDebugTabRawScore() { return debugTabRawScore; }

    private static void clearDebug()
    {
        debugSidebarTotal = 0L;
        debugTabTotal = 0L;
        debugChosenTotal = 0L;
        debugChosenSource = "none";
        debugSidebarSample = "";
        debugTabSample = "";
        debugSidebarObjective = "";
        debugTabObjective = "";
        debugSidebarMatchedUser = "";
        debugTabMatchedUser = "";
        debugSidebarRawScore = 0L;
        debugTabRawScore = 0L;
        debugSkipReason = "";
    }

    private static void touchHealthy()
    {
        lastHealthySignalMs = System.currentTimeMillis();
        lastFailureSignalMs = 0L;
    }

    private enum SyncStatus
    {
        CONNECTED,
        QUEUED,
        SYNCED,
        FAILED
    }

    private record Candidate(String sourceType, long total, boolean valid, String reason)
    {
    }

    private record TotalSelection(String sourceName, PlayerDigsModel model, String reason)
    {
    }
}
