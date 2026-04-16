package com.miningtrackeraddon.sync;

import com.google.gson.JsonObject;
import com.miningtrackeraddon.Reference;
import com.miningtrackeraddon.config.Configs;
import com.miningtrackeraddon.storage.WorldSessionContext;
import com.miningtrackeraddon.tracker.MiningStats;
import java.time.Instant;
import java.util.Locale;
import net.minecraft.client.MinecraftClient;

public final class DigsSyncManager
{
    private static final long MIN_SYNC_INTERVAL_MS = 7_500L;
    private static final long HUD_FAILURE_GRACE_MS = 12_000L;
    private static final long HUD_HEALTH_STALE_MS = 90_000L;
    private static final long AUTHORITATIVE_MODEL_STALE_MS = 15_000L;

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
            MiningStats.applyAeternumTotalMined(latestModel.totalDigs(), now);
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

        if (Configs.Generic.TOTAL_DIGS_SYNC_ENABLED.getBooleanValue() == false || canSync() == false)
        {
            return;
        }

        String fingerprint = fingerprint(latestModel);
        if (fingerprint.equals(lastSuccessfulFingerprint) && status == SyncStatus.SYNCED)
        {
            return;
        }

        if (fingerprint.equals(lastQueuedFingerprint) && status == SyncStatus.QUEUED)
        {
            return;
        }

        if (now - lastQueueAttemptMs < MIN_SYNC_INTERVAL_MS)
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
        lastSuccessfulFingerprint = fingerprint(payload);
        lastQueuedFingerprint = lastSuccessfulFingerprint;
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
        if (Configs.Generic.TOTAL_DIGS_SYNC_ENABLED.getBooleanValue() == false
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

        if (tab.valid()) return tab;
        if (sidebar.valid()) return sidebar;
        if (parser.valid()) return parser;
        if (cached.valid()) return cached;

        return new Candidate("none", 0L, false, "no-valid-total");
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

        JsonObject digs = new JsonObject();
        digs.addProperty("username", model.username());
        digs.addProperty("total_digs", model.totalDigs());
        digs.addProperty("server", model.server());
        digs.addProperty("timestamp", Instant.ofEpochMilli(model.capturedAtMs()).toString());
        digs.addProperty("objective_title", model.objectiveTitle());
        payload.add("player_total_digs", digs);

        return payload;
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
        return Configs.cloudSyncEndpoint != null && Configs.cloudSyncEndpoint.isBlank() == false;
    }

    private static String dedupeKey(PlayerDigsModel model)
    {
        return model.username().toLowerCase(Locale.ROOT) + "|" + model.server().toLowerCase(Locale.ROOT) + "|" + model.totalDigs();
    }

    private static String fingerprint(PlayerDigsModel model)
    {
        return dedupeKey(model);
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
        return username.toLowerCase(Locale.ROOT) + "|" + server.toLowerCase(Locale.ROOT) + "|" + total;
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
