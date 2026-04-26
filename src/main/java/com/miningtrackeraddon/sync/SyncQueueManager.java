package com.miningtrackeraddon.sync;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningtrackeraddon.MiningTrackerAddon;
import com.miningtrackeraddon.Reference;
import com.miningtrackeraddon.config.Configs;

import fi.dy.masa.malilib.util.FileUtils;

public final class SyncQueueManager
{
    private static final String LOG_PREFIX = "[MMM_SYNC]";
    private static final long PERIODIC_FLUSH_INTERVAL_MS = 5_000L;
    private static final String LINK_ENDPOINT = "https://mmm-mu-six.vercel.app/api/auth/link-code/claim";

    private static PendingSyncQueue queue;
    private static long lastPeriodicFlushMs;

    private SyncQueueManager()
    {
    }

    public static synchronized void initialize()
    {
        if (queue != null)
        {
            return;
        }

        Path queuePath = FileUtils.getConfigDirectory().toPath().resolve(Reference.STORAGE_ID + "-sync-queue.json");
        queue = new PendingSyncQueue(queuePath, SyncQueueManager::send, new QueueListener());
        queue.initialize();
        MiningTrackerAddon.LOGGER.info("{} queue-initialized endpoint={} linkEndpoint={}",
                LOG_PREFIX,
                Configs.cloudSyncEndpoint,
                LINK_ENDPOINT);
        forceFlush("mod startup");
    }

    public static void onClientTick(long now)
    {
        if (queue == null)
        {
            return;
        }

        if (now - lastPeriodicFlushMs >= PERIODIC_FLUSH_INTERVAL_MS)
        {
            lastPeriodicFlushMs = now;
            requestFlush("periodic timer");
        }
    }

    public static void requestFlush(String reason)
    {
        if (queue == null)
        {
            MiningTrackerAddon.LOGGER.info("{} send-skipped-flush-guard reason={} detail=queue_not_initialized",
                    LOG_PREFIX,
                    reason == null || reason.isBlank() ? "manual" : reason);
            return;
        }

        queue.requestFlush(reason);
    }

    public static void forceFlush(String reason)
    {
        if (queue == null)
        {
            return;
        }

        queue.forceFlush(reason);
    }

    public static void enqueueCloudLiveState(JsonObject payload)
    {
        initialize();
        queue.enqueue(SyncItemType.CLOUD_LIVE_STATE, "cloud-live-state", payload, true);
        requestFlush("enqueue cloud live state");
    }

    public static void enqueueCloudFinishedSession(String sessionKey, JsonObject payload)
    {
        initialize();
        queue.enqueue(SyncItemType.CLOUD_FINISHED_SESSION, sessionKey == null ? "" : sessionKey, payload, false);
        requestFlush("enqueue finished session");
    }

    public static void enqueuePlayerTotalDigs(String dedupeKey, JsonObject payload)
    {
        initialize();
        queue.enqueue(SyncItemType.PLAYER_TOTAL_DIGS, dedupeKey == null ? "" : dedupeKey, payload, true);
        requestFlush("enqueue player total digs");
    }

    public static void enqueueWebsiteLinkClaim(String dedupeKey, JsonObject payload)
    {
        initialize();
        queue.enqueue(SyncItemType.WEBSITE_LINK_CLAIM, dedupeKey == null ? "" : dedupeKey, payload, true);
        requestFlush("enqueue website link");
    }

    public static PendingSyncQueue.Snapshot getSnapshot()
    {
        initialize();
        return queue.snapshot();
    }

    private static SyncSendResult send(QueuedSyncItem item)
    {
        try
        {
            if (item == null || item.isValid() == false)
            {
                MiningTrackerAddon.LOGGER.warn("{} send-skipped-item-invalid reason=invalid_queue_item", LOG_PREFIX);
                return SyncSendResult.drop(-1, "Invalid queue item.", "");
            }

            String endpoint = resolveEndpoint(item.type);
            if (endpoint == null || endpoint.isBlank())
            {
                MiningTrackerAddon.LOGGER.warn("{} send-skipped-no-endpoint id={} type={}", LOG_PREFIX, item.id, item.type);
                return SyncSendResult.retry(-1, "No sync endpoint configured.", "");
            }

            boolean hasClientId = item.payload != null && item.payload.has("client_id")
                    && item.payload.get("client_id").isJsonPrimitive()
                    && item.payload.get("client_id").getAsString().isBlank() == false;
            boolean hasUsername = item.payload != null && item.payload.has("username")
                    && item.payload.get("username").isJsonPrimitive()
                    && item.payload.get("username").getAsString().isBlank() == false;
            boolean hasSessionToken = item.payload != null && item.payload.has("minecraft_uuid")
                    && item.payload.get("minecraft_uuid").isJsonPrimitive()
                    && item.payload.get("minecraft_uuid").getAsString().isBlank() == false;
            boolean hasLinkedIdentity = Configs.websiteLinkedMinecraftUuid != null
                    && Configs.websiteLinkedMinecraftUuid.isBlank() == false;

            if (hasClientId == false || hasUsername == false)
            {
                MiningTrackerAddon.LOGGER.warn(
                        "{} send-skipped-no-auth id={} type={} hasClientId={} hasUsername={}",
                        LOG_PREFIX,
                        item.id,
                        item.type,
                        hasClientId,
                        hasUsername
                );
                return SyncSendResult.drop(400, "Missing client_id/username.", "");
            }

            String secret = null;
            if (hasSessionToken == false)
            {
                MiningTrackerAddon.LOGGER.warn("{} send-skipped-no-session-token id={} type={} note=payload_missing_minecraft_uuid",
                        LOG_PREFIX,
                        item.id,
                        item.type);
            }
            if (hasLinkedIdentity == false)
            {
                MiningTrackerAddon.LOGGER.warn("{} send-skipped-no-linked-identity id={} type={} note=config_not_linked",
                        LOG_PREFIX,
                        item.id,
                        item.type);
            }

            Map<String, String> headers = Map.of(
                    "x-mmm-sync-item-id", item.id,
                    "x-mmm-sync-item-type", item.type.name());

            MiningTrackerAddon.LOGGER.info(
                    "{} request-sent id={} type={} endpoint={} payloadSummary={}",
                    LOG_PREFIX,
                    item.id,
                    item.type,
                    endpoint,
                    summarizePayload(item.payload)
            );
            MiningTrackerAddon.LOGGER.info("[SYNC_DEBUG] request sent id={} type={} endpoint={} payload={}",
                    item.id,
                    item.type,
                    endpoint,
                    summarizePayload(item.payload));
            HttpResponse<String> response = ApiClient.postJsonBlocking(endpoint, secret, item.payload.toString(), headers);
            int statusCode = response.statusCode();
            String body = response.body() == null ? "" : response.body();
            MiningTrackerAddon.LOGGER.info("{} response-received id={} type={} status={} body={}",
                    LOG_PREFIX,
                    item.id,
                    item.type,
                    statusCode,
                    summarizeResponseBody(body));
            MiningTrackerAddon.LOGGER.info("[SYNC_DEBUG] response status={} id={} type={} detail={} body={}",
                    statusCode,
                    item.id,
                    item.type,
                    extractError(body, statusCode >= 200 && statusCode < 300 ? "ok" : "http_error"),
                    summarizeResponseBody(body));

            if (statusCode >= 200 && statusCode < 300)
            {
                return SyncSendResult.success(statusCode, body);
            }

            if (item.type == SyncItemType.WEBSITE_LINK_CLAIM
                    && (isPermanentLinkFailure(statusCode, body) || statusCode == 401 || statusCode == 403))
            {
                return SyncSendResult.drop(statusCode, extractError(body, "Could not claim link code."), body);
            }

            String detail = extractError(body, "HTTP " + statusCode);
            return SyncSendResult.retry(statusCode, detail, body);
        }
        catch (Exception exception)
        {
            String detail = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            MiningTrackerAddon.LOGGER.warn("{} request-failed id={} type={} error={}", LOG_PREFIX, item.id, item.type, detail);
            return SyncSendResult.retry(-1, detail, "");
        }
    }

    private static String resolveEndpoint(SyncItemType type)
    {
        return switch (type)
        {
            case CLOUD_LIVE_STATE, CLOUD_FINISHED_SESSION, PLAYER_TOTAL_DIGS -> Configs.cloudSyncEndpoint;
            case WEBSITE_LINK_CLAIM -> LINK_ENDPOINT;
        };
    }

    private static boolean isPermanentLinkFailure(int statusCode, String body)
    {
        if (statusCode != 400 && statusCode != 404 && statusCode != 410)
        {
            return false;
        }

        String error = extractError(body, "");
        String normalized = error.toLowerCase(Locale.ROOT);
        return normalized.contains("invalid") || normalized.contains("expired") || normalized.contains("already");
    }

    private static String extractError(String body, String fallback)
    {
        try
        {
            JsonObject object = JsonParser.parseString(body).getAsJsonObject();
            if (object.has("error") && object.get("error").isJsonPrimitive())
            {
                return object.get("error").getAsString();
            }
        }
        catch (Exception ignored)
        {
        }

        return fallback;
    }

    private static String summarizePayload(JsonObject payload)
    {
        if (payload == null)
        {
            return "null";
        }

        String username = payload.has("username") && payload.get("username").isJsonPrimitive()
                ? payload.get("username").getAsString()
                : "";
        String source = "";
        long total = -1L;
        if (payload.has("world") && payload.get("world").isJsonObject())
        {
            JsonObject world = payload.getAsJsonObject("world");
            if (world.has("display_name") && world.get("display_name").isJsonPrimitive())
            {
                source = world.get("display_name").getAsString();
            }
            else if (world.has("key") && world.get("key").isJsonPrimitive())
            {
                source = world.get("key").getAsString();
            }
        }
        if (payload.has("current_world_totals") && payload.get("current_world_totals").isJsonObject())
        {
            JsonObject totals = payload.getAsJsonObject("current_world_totals");
            if (totals.has("total_blocks") && totals.get("total_blocks").isJsonPrimitive())
            {
                total = totals.get("total_blocks").getAsLong();
            }
        }
        else if (payload.has("player_total_digs") && payload.get("player_total_digs").isJsonObject())
        {
            JsonObject digs = payload.getAsJsonObject("player_total_digs");
            if (digs.has("total_digs") && digs.get("total_digs").isJsonPrimitive())
            {
                total = digs.get("total_digs").getAsLong();
            }
            if (source.isBlank() && digs.has("server") && digs.get("server").isJsonPrimitive())
            {
                source = digs.get("server").getAsString();
            }
        }

        return "username=" + username + " source=" + source + " total=" + total;
    }

    private static String summarizeResponseBody(String body)
    {
        if (body == null || body.isBlank())
        {
            return "<empty>";
        }

        String compact = body.replace('\n', ' ').replace('\r', ' ').trim();
        int maxLength = 700;
        return compact.length() <= maxLength ? compact : compact.substring(0, maxLength) + "...";
    }

    private static final class QueueListener implements PendingSyncQueue.Listener
    {
        @Override
        public void onLoaded(PendingSyncQueue.Snapshot snapshot)
        {
            MiningTrackerAddon.LOGGER.info("{} queue-loaded size={} lastSuccess={} flushActive={}",
                    LOG_PREFIX,
                    snapshot.queueSize(),
                    PendingSyncQueue.formatInstant(snapshot.lastSuccessfulSyncAtMs()),
                    snapshot.flushActive());
        }

        @Override
        public void onLoadFailed(String detail)
        {
            MiningTrackerAddon.LOGGER.warn("{} queue-load-failed detail={}", LOG_PREFIX, detail);
        }

        @Override
        public void onItemQueued(QueuedSyncItem item, boolean replaced, PendingSyncQueue.Snapshot snapshot)
        {
            MiningTrackerAddon.LOGGER.info("{} item-enqueued id={} type={} replaced={} queueSize={}",
                    LOG_PREFIX,
                    item.id,
                    item.type,
                    replaced,
                    snapshot.queueSize());
            notifyQueued(item);
        }

        @Override
        public void onFlushStarted(String reason, PendingSyncQueue.Snapshot snapshot)
        {
            MiningTrackerAddon.LOGGER.info("{} flush-started reason={} queueSize={} lastSuccess={}",
                    LOG_PREFIX,
                    reason,
                    snapshot.queueSize(),
                    PendingSyncQueue.formatInstant(snapshot.lastSuccessfulSyncAtMs()));
        }

        @Override
        public void onFlushFinished(String reason, PendingSyncQueue.Snapshot snapshot)
        {
            MiningTrackerAddon.LOGGER.info("{} flush-finished reason={} queueSize={} flushActive={}",
                    LOG_PREFIX,
                    reason,
                    snapshot.queueSize(),
                    snapshot.flushActive());
        }

        @Override
        public void onItemSucceeded(QueuedSyncItem item, SyncSendResult result, PendingSyncQueue.Snapshot snapshot)
        {
            MiningTrackerAddon.LOGGER.info("{} item-removed-success id={} type={} status={} queueSize={}",
                    LOG_PREFIX,
                    item.id,
                    item.type,
                    result.statusCode(),
                    snapshot.queueSize());
            notifySuccess(item, result);
        }

        @Override
        public void onRetryScheduled(QueuedSyncItem item, SyncSendResult result, long nextRetryAtMs, PendingSyncQueue.Snapshot snapshot)
        {
            MiningTrackerAddon.LOGGER.warn("{} item-retained-retry id={} type={} status={} retryCount={} nextRetry={} queueSize={} detail={}",
                    LOG_PREFIX,
                    item.id,
                    item.type,
                    result.statusCode(),
                    item.retryCount,
                    PendingSyncQueue.formatInstant(nextRetryAtMs),
                    snapshot.queueSize(),
                    result.detail());
            notifyRetry(item, result, nextRetryAtMs);
        }

        @Override
        public void onItemDropped(QueuedSyncItem item, SyncSendResult result, PendingSyncQueue.Snapshot snapshot)
        {
            MiningTrackerAddon.LOGGER.warn("{} item-dropped id={} type={} status={} queueSize={} detail={}",
                    LOG_PREFIX,
                    item.id,
                    item.type,
                    result.statusCode(),
                    snapshot.queueSize(),
                    result.detail());
            notifyDropped(item, result);
        }

        @Override
        public void onPersistenceFailed(String detail, PendingSyncQueue.Snapshot snapshot)
        {
            MiningTrackerAddon.LOGGER.warn("{} queue-persist-failed queueSize={} detail={}",
                    LOG_PREFIX,
                    snapshot.queueSize(),
                    detail);
        }

        private void notifyQueued(QueuedSyncItem item)
        {
            switch (item.type)
            {
                case CLOUD_LIVE_STATE, CLOUD_FINISHED_SESSION -> CloudSyncManager.onQueued(item.type, item.payload);
                case PLAYER_TOTAL_DIGS -> DigsSyncManager.onQueued(item.payload);
                case WEBSITE_LINK_CLAIM -> WebsiteLinkManager.onQueued(item.payload);
            }
        }

        private void notifySuccess(QueuedSyncItem item, SyncSendResult result)
        {
            switch (item.type)
            {
                case CLOUD_LIVE_STATE, CLOUD_FINISHED_SESSION -> CloudSyncManager.onQueueSuccess(item.type, item.payload, result.responseBody());
                case PLAYER_TOTAL_DIGS -> DigsSyncManager.onQueueSuccess(item.payload, result.responseBody());
                case WEBSITE_LINK_CLAIM -> {
                    WebsiteLinkManager.onQueueSuccess(item.payload, result.responseBody());
                    forceFlush("successful website link");
                }
            }
        }

        private void notifyRetry(QueuedSyncItem item, SyncSendResult result, long nextRetryAtMs)
        {
            switch (item.type)
            {
                case CLOUD_LIVE_STATE, CLOUD_FINISHED_SESSION -> CloudSyncManager.onQueueRetry(item.type, result.detail(), nextRetryAtMs);
                case PLAYER_TOTAL_DIGS -> DigsSyncManager.onQueueRetry(result.detail(), nextRetryAtMs);
                case WEBSITE_LINK_CLAIM -> WebsiteLinkManager.onQueueRetry(result.detail(), nextRetryAtMs);
            }
        }

        private void notifyDropped(QueuedSyncItem item, SyncSendResult result)
        {
            switch (item.type)
            {
                case CLOUD_LIVE_STATE, CLOUD_FINISHED_SESSION -> CloudSyncManager.onQueueDropped(item.type, result.detail());
                case PLAYER_TOTAL_DIGS -> DigsSyncManager.onQueueDropped(result.detail());
                case WEBSITE_LINK_CLAIM -> WebsiteLinkManager.onQueueDropped(result.detail());
            }
        }
    }
}
