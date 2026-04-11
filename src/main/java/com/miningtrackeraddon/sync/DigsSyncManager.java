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
    private static String lastDetectionFingerprint = "";

    private DigsSyncManager()
    {
    }

    public static void onClientTick(long now)
    {
        clearStaleModel(now);

        MinecraftClient client = MinecraftClient.getInstance();
        PersonalTotalDetector.Detection detection = PersonalTotalDetector.detect(client);
        PlayerDigsModel parsed = PlayerDigsParser.parse(client);
        TotalSelection selection = selectAuthoritativeTotal(client, detection, parsed);
        applyDetectionDebug(detection);
        debugDetection(detection);

        if (selection != null && selection.chosenTotal() > 0L)
        {
            debug("final-authoritative-total sourceName={} parserTotal={} detectorTotal={} tabTotal={} sidebarTotal={} cachedTotal={} chosenTotal={} chosenSourceType={} reason={}",
                    selection.sourceName(),
                    selection.parserTotal(),
                    selection.detectorTotal(),
                    selection.tabTotal(),
                    selection.sidebarTotal(),
                    selection.cachedTotal(),
                    selection.chosenTotal(),
                    selection.chosenSourceType(),
                    selection.reason());
            MiningStats.bootstrapSourceTotalFromScoreboard(selection.chosenTotal(), selection.sourceName(), now);
            MiningStats.applyAeternumTotalMined(selection.chosenTotal(), now);
            latestModel = selection.model();
            debug("scoreboard-player-total-detected username={} total={} sourceName={} detector={} parserTotal={} detectorTotal={} tabTotal={} sidebarTotal={} cachedTotal={} sidebarObjective={} tabObjective={} final=true",
                    latestModel.username(),
                    latestModel.totalDigs(),
                    latestModel.server(),
                    selection.chosenSourceType(),
                    selection.parserTotal(),
                    selection.detectorTotal(),
                    selection.tabTotal(),
                    selection.sidebarTotal(),
                    selection.cachedTotal(),
                    detection.sidebarObjectiveTitle(),
                    detection.tabObjectiveTitle());
            debug("Parsed player total digs username={} total={} objective={}", latestModel.username(), latestModel.totalDigs(), latestModel.objectiveTitle());
            if (status != SyncStatus.SYNCED)
            {
                status = SyncStatus.CONNECTED;
            }
            touchHealthy();
        }
        else if (detection.skipReason().isBlank() == false)
        {
            debug("bootstrap-skipped reason={} sidebarObjective={} sidebarUser={} sidebarRaw={} sidebarRendered={} tabObjective={} tabUser={} tabRaw={} tabRendered={}",
                    detection.skipReason(),
                    detection.sidebarObjectiveTitle(),
                    detection.sidebarMatchedUsername(),
                    detection.sidebarRawScore(),
                    detection.sidebarRenderedText(),
                    detection.tabObjectiveTitle(),
                    detection.tabMatchedUsername(),
                    detection.tabRawScore(),
                    detection.tabRenderedText());
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

        if (latestModel != null
        && latestModel.isValid()
        && recentHealthyMs > 0L
        && now - recentHealthyMs <= HUD_HEALTH_STALE_MS)
        {
        return true;
        }

        return recentHealthyMs > 0L && now - recentHealthyMs <= HUD_HEALTH_STALE_MS;
    }

    private static JsonObject buildPayload(PlayerDigsModel model)
    {
        MinecraftClient client = MinecraftClient.getInstance();

        JsonObject payload = new JsonObject();
        payload.addProperty("client_id", Configs.cloudClientId);
        payload.addProperty("username", model.username());
        payload.addProperty("minecraft_uuid", client != null && client.player != null ? client.player.getUuidAsString() : null);
        payload.addProperty("mod_version", Reference.MOD_VERSION);
        payload.addProperty("minecraft_version", client != null ? client.getGameVersion() : null);

        JsonObject digs = new JsonObject();
        digs.addProperty("username", model.username());
        digs.addProperty("total_digs", model.totalDigs());
        digs.addProperty("server", model.server());
        digs.addProperty("timestamp", Instant.ofEpochMilli(model.capturedAtMs()).toString());
        digs.addProperty("objective_title", model.objectiveTitle());
        payload.add("player_total_digs", digs);

        return payload;
    }

    private static boolean canSync()
    {
        return Configs.cloudSyncEndpoint != null && Configs.cloudSyncEndpoint.isBlank() == false;
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
        resetDebugDetection();
    }

    public static void resetForWorldChange(String worldId)
    {
        latestModel = null;
        lastQueuedFingerprint = null;
        lastSuccessfulFingerprint = null;
        lastQueueAttemptMs = 0L;
        status = SyncStatus.CONNECTED;
        lastFailureSignalMs = 0L;
        resetDebugDetection();
        debug("Reset digs sync state for world switch worldId={}", worldId);
    }

    public static long getDebugSidebarTotal()
    {
        return debugSidebarTotal;
    }

    public static long getDebugTabTotal()
    {
        return debugTabTotal;
    }

    public static long getDebugChosenTotal()
    {
        return debugChosenTotal;
    }

    public static String getDebugChosenSource()
    {
        return debugChosenSource;
    }

    public static String getDebugSidebarSample()
    {
        return debugSidebarSample;
    }

    public static String getDebugTabSample()
    {
        return debugTabSample;
    }

    public static String getDebugSkipReason()
    {
        return debugSkipReason;
    }

    public static String getDebugSidebarObjective()
    {
        return debugSidebarObjective;
    }

    public static String getDebugTabObjective()
    {
        return debugTabObjective;
    }

    public static String getDebugSidebarMatchedUser()
    {
        return debugSidebarMatchedUser;
    }

    public static String getDebugTabMatchedUser()
    {
        return debugTabMatchedUser;
    }

    public static long getDebugSidebarRawScore()
    {
        return debugSidebarRawScore;
    }

    public static long getDebugTabRawScore()
    {
        return debugTabRawScore;
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
        String currentSourceName = ScoreboardSourceResolver.displayName(
                worldInfo != null ? worldInfo.displayName() : "",
                worldInfo
        );

        return currentSourceName.equalsIgnoreCase(latestModel.server());
    }

    private static TotalSelection selectAuthoritativeTotal(MinecraftClient client,
                                                           PersonalTotalDetector.Detection detection,
                                                           PlayerDigsModel parsed)
    {
        WorldSessionContext.WorldInfo worldInfo = WorldSessionContext.getCurrentWorldInfo();
        String sourceName = ScoreboardSourceResolver.displayName(
                worldInfo != null ? worldInfo.displayName() : "",
                worldInfo
        );
        long tabTotal = Math.max(0L, detection.tabTotal());
        long sidebarTotal = Math.max(0L, detection.sidebarTotal());
        long detectorTotal = Math.max(0L, detection.chosenTotal());
        long parserTotal = parsed != null && parsed.isValid() ? Math.max(0L, parsed.totalDigs()) : 0L;
        long currentSourceTotal = Math.max(0L, MiningStats.getCurrentSourceTotalMined());
        long higherKnown = Math.max(Math.max(Math.max(tabTotal, sidebarTotal), detectorTotal), currentSourceTotal);

        Candidate tabCandidate = candidate("tab", tabTotal, tabTotal > 0L, tabTotal > 0L ? "valid" : "missing");
        Candidate sidebarCandidate = candidate("sidebar", sidebarTotal, sidebarTotal > 0L, sidebarTotal > 0L ? "valid" : "missing");
        Candidate detectorCandidate = candidate("detector", detectorTotal, detectorTotal > 0L, detectorTotal > 0L ? "valid" : "missing");
        Candidate cachedCandidate = candidate("cached", currentSourceTotal, currentSourceTotal > 0L, currentSourceTotal > 0L ? "valid" : "missing");

        boolean parserValid = parserTotal > 0L;
        String parserReason = parserValid ? "valid" : "missing";
        if (parserValid && parserTotal <= 1000L)
        {
            parserValid = false;
            parserReason = "low_confidence";
        }
        if (parserValid && tabTotal > 0L && parserTotal * 50L < tabTotal)
        {
            parserValid = false;
            parserReason = "overridden_by_tab";
        }
        if (parserValid && sidebarTotal > 0L && parserTotal * 50L < sidebarTotal)
        {
            parserValid = false;
            parserReason = "overridden_by_sidebar";
        }
        if (parserValid && tabTotal <= 0L && detectorTotal > 0L && parserTotal * 50L < detectorTotal)
        {
            parserValid = false;
            parserReason = "overridden_by_detector";
        }
        if (parserValid && higherKnown > 0L && parserTotal * 200L < higherKnown)
        {
            parserValid = false;
            parserReason = "overridden_by_known_total";
        }
        Candidate parserCandidate = candidate("parser", parserTotal, parserValid, parserReason);
        if (!parserCandidate.valid() && parserTotal > 0L)
        {
            debug("parser-total-rejected total={} reason={}", parserTotal, parserCandidate.reason());
        }

        long chosenTotal = 0L;
        String chosenSourceType = "none";
        String reason = "none";
        if (tabCandidate.valid())
        {
            chosenTotal = tabCandidate.total();
            chosenSourceType = tabCandidate.sourceType();
            reason = "preferred-order";
        }
        else if (sidebarCandidate.valid())
        {
            chosenTotal = sidebarCandidate.total();
            chosenSourceType = sidebarCandidate.sourceType();
            reason = "preferred-order";
        }
        else if (detectorCandidate.valid())
        {
            chosenTotal = detectorCandidate.total();
            chosenSourceType = detectorCandidate.sourceType();
            reason = "preferred-order";
        }
        else if (parserCandidate.valid())
        {
            chosenTotal = parserCandidate.total();
            chosenSourceType = parserCandidate.sourceType();
            reason = "fallback-parser";
        }
        else if (cachedCandidate.valid())
        {
            chosenTotal = cachedCandidate.total();
            chosenSourceType = cachedCandidate.sourceType();
            reason = "fallback-cached";
        }

        String username = resolveUsername(client, parsed);
        String objectiveTitle = resolveObjectiveTitle(parsed, detection);
        PlayerDigsModel model = chosenTotal > 0L
                ? new PlayerDigsModel(username, chosenTotal, System.currentTimeMillis(), sourceName, objectiveTitle)
                : null;
        return new TotalSelection(sourceName, parserTotal, detectorTotal, tabTotal, sidebarTotal, currentSourceTotal, chosenTotal, chosenSourceType, reason, model);
    }

    private static Candidate candidate(String sourceType, long total, boolean valid, String reason)
    {
        debug("total-candidate sourceType={} total={} valid={} reason={}", sourceType, total, valid, reason);
        return new Candidate(sourceType, total, valid, reason);
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

    private static String dedupeKey(PlayerDigsModel model)
{
    return model.username().toLowerCase(Locale.ROOT)
            + "|" + model.server().toLowerCase(Locale.ROOT)
            + "|" + model.totalDigs();
}

   
    private static String fingerprint(PlayerDigsModel model)
    {
    return model.username().toLowerCase(Locale.ROOT)
            + "|" + model.server().toLowerCase(Locale.ROOT)
            + "|" + model.totalDigs();
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
        return username.toLowerCase(Locale.ROOT)
        + "|" + server.toLowerCase(Locale.ROOT)
        + "|" + total;
    }

    private static void debug(String message, Object... args)
    {
        if (Configs.Generic.WEBSITE_SYNC_DEBUG.getBooleanValue() == false)
        {
            return;
        }

        com.miningtrackeraddon.MiningTrackerAddon.LOGGER.info("[AET_DEBUG] " + message, args);
    }

    private static void clearStaleModel(long now)
    {
        if (latestModel == null)
        {
            return;
        }

        if (now - latestModel.capturedAtMs() <= AUTHORITATIVE_MODEL_STALE_MS)
        {
            return;
        }

        debug("Clearing stale authoritative digs model server={} ageMs={}",
                latestModel.server(),
                now - latestModel.capturedAtMs());
        latestModel = null;
    }

    private static void applyDetectionDebug(PersonalTotalDetector.Detection detection)
    {
        debugSidebarTotal = detection.sidebarTotal();
        debugTabTotal = detection.tabTotal();
        debugChosenTotal = detection.chosenTotal();
        debugChosenSource = detection.chosenSource();
        debugSidebarObjective = trimSample(detection.sidebarObjectiveTitle());
        debugTabObjective = trimSample(detection.tabObjectiveTitle());
        debugSidebarMatchedUser = trimSample(detection.sidebarMatchedUsername());
        debugTabMatchedUser = trimSample(detection.tabMatchedUsername());
        debugSidebarRawScore = detection.sidebarRawScore();
        debugTabRawScore = detection.tabRawScore();
        debugSidebarSample = trimSample(detection.sidebarRenderedText());
        debugTabSample = trimSample(detection.tabRenderedText());
        debugSkipReason = detection.skipReason();
    }

    private static void resetDebugDetection()
    {
        debugSidebarTotal = 0L;
        debugTabTotal = 0L;
        debugChosenTotal = 0L;
        debugChosenSource = "none";
        debugSidebarObjective = "";
        debugTabObjective = "";
        debugSidebarMatchedUser = "";
        debugTabMatchedUser = "";
        debugSidebarRawScore = 0L;
        debugTabRawScore = 0L;
        debugSidebarSample = "";
        debugTabSample = "";
        debugSkipReason = "";
        lastDetectionFingerprint = "";
    }

    private static String trimSample(String sample)
    {
        if (sample == null)
        {
            return "";
        }

        String trimmed = sample.trim();
        if (trimmed.length() <= 80)
        {
            return trimmed;
        }
        return trimmed.substring(0, 80) + "...";
    }

    private static void debugDetection(PersonalTotalDetector.Detection detection)
    {
        if (Configs.Generic.WEBSITE_SYNC_DEBUG.getBooleanValue() == false)
        {
            return;
        }

        String fingerprint = detection.sidebarTotal()
                + "|" + detection.tabTotal()
                + "|" + detection.chosenTotal()
                + "|" + detection.chosenSource()
                + "|" + detection.sidebarRawScore()
                + "|" + detection.tabRawScore()
                + "|" + detection.skipReason();
        if (fingerprint.equals(lastDetectionFingerprint))
        {
            return;
        }
        lastDetectionFingerprint = fingerprint;

        debug("sidebar-detected objective={} user={} rawScore={} rendered={} total={}",
                detection.sidebarObjectiveTitle(),
                detection.sidebarMatchedUsername(),
                detection.sidebarRawScore(),
                detection.sidebarRenderedText(),
                detection.sidebarTotal());
        debug("tab-detected objective={} user={} rawScore={} rendered={} total={}",
                detection.tabObjectiveTitle(),
                detection.tabMatchedUsername(),
                detection.tabRawScore(),
                detection.tabRenderedText(),
                detection.tabTotal());
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

    private record TotalSelection(
            String sourceName,
            long parserTotal,
            long detectorTotal,
            long tabTotal,
            long sidebarTotal,
            long cachedTotal,
            long chosenTotal,
            String chosenSourceType,
            String reason,
            PlayerDigsModel model)
    {
    }
}
