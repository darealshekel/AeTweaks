package com.miningtrackeraddon.sync;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.miningtrackeraddon.MiningTrackerAddon;
import com.miningtrackeraddon.Reference;
import com.miningtrackeraddon.config.Configs;
import com.miningtrackeraddon.config.FeatureToggle;
import com.miningtrackeraddon.tracker.MiningStats;

import net.minecraft.client.MinecraftClient;
import net.minecraft.SharedConstants;

public final class MmmSyncManager
{
    private static final long FLUSH_INTERVAL_MS = 15_000L;
    private static final long RETRY_INTERVAL_MS = 30_000L;
    private static final long BATCH_SIZE = 25L;
    private static final long STATUS_SUCCESS_WINDOW_MS = 20_000L;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Deque<PendingSyncBatch> QUEUE = new ArrayDeque<>();

    private static PendingSyncBatch currentBatch;
    private static boolean requestInFlight;
    private static long lastFlushAttemptMs;
    private static volatile long lastSuccessAtMs;
    private static volatile long lastSuccessDelta;
    private static volatile long lastFailureAtMs;
    private static volatile String lastFailureReason = "";

    private MmmSyncManager()
    {
    }

    public static void onWorldJoin(MinecraftClient client)
    {
        ensureCurrentBatch(client);
    }

    public static void onWorldLeave()
    {
        enqueueCurrentBatch();
        sendNextIfReady();
    }

    public static void onClientTick(MinecraftClient client)
    {
        if (!isEnabled())
        {
            return;
        }

        ensureCurrentBatch(client);
        long now = System.currentTimeMillis();

        if (currentBatch != null && currentBatch.deltaBlocks > 0 && now - currentBatch.lastUpdatedAtMs >= FLUSH_INTERVAL_MS)
        {
            enqueueCurrentBatch();
        }

        if (!requestInFlight && !QUEUE.isEmpty() && now - lastFlushAttemptMs >= RETRY_INTERVAL_MS)
        {
            sendNextIfReady();
        }
    }

    public static void onBlockMined()
    {
        if (!isEnabled())
        {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        ensureCurrentBatch(client);
        if (currentBatch == null)
        {
            return;
        }

        currentBatch.deltaBlocks++;
        currentBatch.sessionBlocks = MiningStats.getTotalMined();
        currentBatch.lastUpdatedAtMs = System.currentTimeMillis();

        if (currentBatch.deltaBlocks >= BATCH_SIZE)
        {
            enqueueCurrentBatch();
            sendNextIfReady();
        }
    }

    public static String getHudStatusLine()
    {
        if (!FeatureToggle.TWEAK_MMM_SYNC.getBooleanValue())
        {
            return "MMM Sync: Off";
        }

        if (Configs.Generic.MMM_SYNC_URL.getStringValue().trim().isEmpty())
        {
            return "MMM Sync: No URL";
        }

        long now = System.currentTimeMillis();
        if (requestInFlight)
        {
            return "MMM Sync: Syncing...";
        }

        if (lastSuccessAtMs > 0L && now - lastSuccessAtMs <= STATUS_SUCCESS_WINDOW_MS)
        {
            return "Last Sync: +" + lastSuccessDelta;
        }

        if (lastFailureAtMs > 0L && now - lastFailureAtMs <= STATUS_SUCCESS_WINDOW_MS)
        {
            return "MMM Sync: Retry queued";
        }

        if (lastSuccessAtMs > 0L)
        {
            return "MMM Sync: Connected";
        }

        return "MMM Sync: Waiting";
    }

    private static boolean isEnabled()
    {
        return FeatureToggle.TWEAK_MMM_SYNC.getBooleanValue() && !Configs.Generic.MMM_SYNC_URL.getStringValue().trim().isEmpty();
    }

    private static void ensureCurrentBatch(MinecraftClient client)
    {
        if (!isEnabled() || client == null || client.player == null)
        {
            return;
        }

        String username = client.player.getGameProfile().getName();
        String uuid = client.player.getUuidAsString();
        String worldId = MiningStats.getCurrentWorldId();
        String worldName = com.miningtrackeraddon.storage.WorldSessionContext.getCurrentWorldName();
        boolean multiplayer = client.getCurrentServerEntry() != null;
        String serverAddress = multiplayer ? client.getCurrentServerEntry().address : "";

        if (currentBatch == null || !currentBatch.matches(username, uuid, worldId, worldName, serverAddress, multiplayer))
        {
            enqueueCurrentBatch();
            currentBatch = new PendingSyncBatch(username, uuid, worldId, worldName, serverAddress, multiplayer);
        }

        currentBatch.sessionBlocks = MiningStats.getTotalMined();
        currentBatch.lastUpdatedAtMs = System.currentTimeMillis();
    }

    private static void enqueueCurrentBatch()
    {
        if (currentBatch != null && currentBatch.deltaBlocks > 0)
        {
            QUEUE.addLast(currentBatch);
        }
        currentBatch = null;
    }

    private static void sendNextIfReady()
    {
        if (!isEnabled() || requestInFlight || QUEUE.isEmpty())
        {
            return;
        }

        PendingSyncBatch batch = QUEUE.peekFirst();
        if (batch == null)
        {
            return;
        }

        lastFlushAttemptMs = System.currentTimeMillis();
        requestInFlight = true;

        JsonObject payload = new JsonObject();
        payload.addProperty("username", batch.username);
        payload.addProperty("batchId", batch.batchId);
        payload.addProperty("uuid", batch.uuid);
        payload.addProperty("worldId", batch.worldId);
        payload.addProperty("worldName", batch.worldName);
        payload.addProperty("serverAddress", batch.serverAddress);
        payload.addProperty("multiplayer", batch.multiplayer);
        payload.addProperty("deltaBlocks", batch.deltaBlocks);
        payload.addProperty("sessionBlocks", batch.sessionBlocks);
        payload.addProperty("sentAt", System.currentTimeMillis());
        payload.addProperty("modId", Reference.MOD_ID);
        payload.addProperty("modName", Reference.MOD_NAME);
        payload.addProperty("modVersion", Reference.MOD_VERSION);
        payload.addProperty("minecraftVersion", SharedConstants.getGameVersion().getName());

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(Configs.Generic.MMM_SYNC_URL.getStringValue().trim()))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8));

        String apiKey = Configs.Generic.MMM_SYNC_API_KEY.getStringValue().trim();
        if (!apiKey.isEmpty())
        {
            requestBuilder.header("X-AeTweaks-Key", apiKey);
        }

        HTTP_CLIENT.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.discarding())
                .whenComplete((response, throwable) -> {
                    if (throwable != null)
                    {
                        MiningTrackerAddon.LOGGER.warn("MMM sync failed", throwable);
                        lastFailureAtMs = System.currentTimeMillis();
                        lastFailureReason = throwable.getMessage() == null ? "request failed" : throwable.getMessage();
                    }
                    else if (response.statusCode() >= 200 && response.statusCode() < 300)
                    {
                        QUEUE.pollFirst();
                        lastSuccessAtMs = System.currentTimeMillis();
                        lastSuccessDelta = batch.deltaBlocks;
                        lastFailureAtMs = 0L;
                        lastFailureReason = "";
                    }
                    else
                    {
                        MiningTrackerAddon.LOGGER.warn("MMM sync returned HTTP {}", response.statusCode());
                        lastFailureAtMs = System.currentTimeMillis();
                        lastFailureReason = "HTTP " + response.statusCode();
                    }

                    requestInFlight = false;
                });
    }

    private static final class PendingSyncBatch
    {
        private final String username;
        private final String batchId;
        private final String uuid;
        private final String worldId;
        private final String worldName;
        private final String serverAddress;
        private final boolean multiplayer;
        private long deltaBlocks;
        private long sessionBlocks;
        private long lastUpdatedAtMs;

        private PendingSyncBatch(String username, String uuid, String worldId, String worldName, String serverAddress, boolean multiplayer)
        {
            this.username = username;
            this.batchId = UUID.randomUUID().toString();
            this.uuid = uuid;
            this.worldId = worldId;
            this.worldName = worldName;
            this.serverAddress = serverAddress;
            this.multiplayer = multiplayer;
            this.lastUpdatedAtMs = System.currentTimeMillis();
        }

        private boolean matches(String username, String uuid, String worldId, String worldName, String serverAddress, boolean multiplayer)
        {
            return this.multiplayer == multiplayer &&
                    this.username.equals(username) &&
                    this.uuid.equals(uuid) &&
                    this.worldId.equals(worldId) &&
                    this.worldName.equals(worldName) &&
                    this.serverAddress.equals(serverAddress);
        }
    }
}
