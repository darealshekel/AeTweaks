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
    private static final long PERIODIC_FLUSH_INTERVAL_MS = 5_000L;
    private static final String LINK_ENDPOINT = "https://aewt-sync-pro.vercel.app/api/auth/link-code/claim";

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
    }

    public static void enqueueCloudFinishedSession(String sessionKey, JsonObject payload)
    {
        initialize();
        queue.enqueue(SyncItemType.CLOUD_FINISHED_SESSION, sessionKey == null ? "" : sessionKey, payload, false);
    }

    public static void enqueuePlayerTotalDigs(String dedupeKey, JsonObject payload)
    {
        initialize();
        queue.enqueue(SyncItemType.PLAYER_TOTAL_DIGS, dedupeKey == null ? "" : dedupeKey, payload, true);
    }

    public static void enqueueWebsiteLinkClaim(String dedupeKey, JsonObject payload)
    {
        initialize();
        queue.enqueue(SyncItemType.WEBSITE_LINK_CLAIM, dedupeKey == null ? "" : dedupeKey, payload, true);
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
            String endpoint = resolveEndpoint(item.type);
            if (endpoint == null || endpoint.isBlank())
            {
                return SyncSendResult.retry(-1, "No sync endpoint configured.", "");
            }

            String secret = usesSyncSecret(item.type) ? Configs.cloudSyncSecret : null;
            Map<String, String> headers = Map.of(
                    "x-aetweaks-sync-item-id", item.id,
                    "x-aetweaks-sync-item-type", item.type.name());

            HttpResponse<String> response = ApiClient.postJsonBlocking(endpoint, secret, item.payload.toString(), headers);
            int statusCode = response.statusCode();
            String body = response.body() == null ? "" : response.body();

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

    private static boolean usesSyncSecret(SyncItemType type)
    {
        return type == SyncItemType.CLOUD_LIVE_STATE
                || type == SyncItemType.CLOUD_FINISHED_SESSION
                || type == SyncItemType.PLAYER_TOTAL_DIGS;
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

    private static final class QueueListener implements PendingSyncQueue.Listener
    {
        @Override
        public void onLoaded(PendingSyncQueue.Snapshot snapshot)
        {
            MiningTrackerAddon.LOGGER.info("AeTweaks sync queue loaded: size={} lastSuccess={} flushActive={}",
                    snapshot.queueSize(),
                    PendingSyncQueue.formatInstant(snapshot.lastSuccessfulSyncAtMs()),
                    snapshot.flushActive());
        }

        @Override
        public void onLoadFailed(String detail)
        {
            MiningTrackerAddon.LOGGER.warn("AeTweaks sync queue failed to load: {}", detail);
        }

        @Override
        public void onItemQueued(QueuedSyncItem item, boolean replaced, PendingSyncQueue.Snapshot snapshot)
        {
            MiningTrackerAddon.LOGGER.info("AeTweaks sync item queued: id={} type={} replaced={} queueSize={}",
                    item.id,
                    item.type,
                    replaced,
                    snapshot.queueSize());
            notifyQueued(item);
        }

        @Override
        public void onFlushStarted(String reason, PendingSyncQueue.Snapshot snapshot)
        {
            MiningTrackerAddon.LOGGER.info("AeTweaks sync flush started: reason={} queueSize={} lastSuccess={}",
                    reason,
                    snapshot.queueSize(),
                    PendingSyncQueue.formatInstant(snapshot.lastSuccessfulSyncAtMs()));
        }

        @Override
        public void onFlushFinished(String reason, PendingSyncQueue.Snapshot snapshot)
        {
            MiningTrackerAddon.LOGGER.info("AeTweaks sync flush finished: reason={} queueSize={} flushActive={}",
                    reason,
                    snapshot.queueSize(),
                    snapshot.flushActive());
        }

        @Override
        public void onItemSucceeded(QueuedSyncItem item, SyncSendResult result, PendingSyncQueue.Snapshot snapshot)
        {
            MiningTrackerAddon.LOGGER.info("AeTweaks sync flush succeeded: id={} type={} status={} queueSize={}",
                    item.id,
                    item.type,
                    result.statusCode(),
                    snapshot.queueSize());
            notifySuccess(item, result);
        }

        @Override
        public void onRetryScheduled(QueuedSyncItem item, SyncSendResult result, long nextRetryAtMs, PendingSyncQueue.Snapshot snapshot)
        {
            MiningTrackerAddon.LOGGER.warn("AeTweaks sync flush failed: id={} type={} status={} retryCount={} nextRetry={} queueSize={} detail={}",
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
            MiningTrackerAddon.LOGGER.warn("AeTweaks sync item dropped: id={} type={} status={} queueSize={} detail={}",
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
            MiningTrackerAddon.LOGGER.warn("AeTweaks sync queue persistence failed: queueSize={} detail={}",
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