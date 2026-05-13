package com.mmm.sync;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mmm.MMM;
import com.mmm.config.Configs;
import com.mmm.util.MmmDebugLogger;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.MinecraftClient;

public final class WebsiteLinkManager
{
    private static final long JSON_PARSE_DEBUG_LOG_INTERVAL_MS = 30_000L;
    private static final AtomicReference<LinkState> STATE = new AtomicReference<>(LinkState.idle());
    private static final DateTimeFormatter RETRY_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private WebsiteLinkManager()
    {
    }

    public static LinkState getState()
    {
        return STATE.get();
    }

    public static void reset()
    {
        STATE.set(LinkState.idle());
    }

    public static boolean hasPersistedLink()
    {
        return Configs.websiteLinkedMinecraftUuid != null && Configs.websiteLinkedMinecraftUuid.isBlank() == false;
    }

    public static String getPersistedUsername()
    {
        return Configs.websiteLinkedMinecraftUsername == null ? "" : Configs.websiteLinkedMinecraftUsername;
    }

    public static long getPersistedLinkedAtMs()
    {
        return Configs.websiteLinkedAtMs;
    }

    public static boolean isCurrentPlayerLinked()
    {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || hasPersistedLink() == false)
        {
            return false;
        }

        return client.player.getUuidAsString().equalsIgnoreCase(Configs.websiteLinkedMinecraftUuid);
    }

    public static void claimCode(String rawCode)
    {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null)
        {
            STATE.set(LinkState.error("Join a world or server before linking."));
            return;
        }

        String code = sanitizeCode(rawCode);
        if (code.length() < 6)
        {
            STATE.set(LinkState.error("Enter a valid link code from the website."));
            return;
        }

        JsonObject payload = buildPayload(client, code);
        STATE.set(LinkState.submitting(code));
        SyncQueueManager.enqueueWebsiteLinkClaim("website-link|" + client.player.getUuidAsString() + "|" + code, payload);
        SyncQueueManager.forceFlush("website link request");
    }

    static void onQueued(JsonObject payload)
    {
        STATE.set(LinkState.queued(extractCode(payload), "Link request saved locally. MMM will retry automatically."));
    }

    static void onQueueSuccess(JsonObject payload, String responseBody)
    {
        String linkedUsername = extractUsername(responseBody);
        persistLink(linkedUsername);
        STATE.set(LinkState.success(linkedUsername));
    }

    static void onQueueRetry(String detail, long nextRetryAtMs)
    {
        String retryLabel = nextRetryAtMs > 0L ? RETRY_TIME_FORMATTER.format(Instant.ofEpochMilli(nextRetryAtMs)) : "soon";
        STATE.set(LinkState.queued("", "Link request queued. Next retry at " + retryLabel + "."));
    }

    static void onQueueDropped(String detail)
    {
        STATE.set(LinkState.error(detail == null || detail.isBlank() ? "Could not claim link code." : detail));
    }

    private static JsonObject buildPayload(MinecraftClient client, String code)
    {
        String username = resolveUsername(client);
        String uuid = client.player.getUuidAsString();

        JsonObject payload = new JsonObject();
        payload.addProperty("code", code);
        payload.addProperty("minecraftUuid", uuid);
        payload.addProperty("minecraft_uuid", uuid);
        payload.addProperty("username", username);
        payload.addProperty("clientId", Configs.cloudClientId);
        payload.addProperty("client_id", Configs.cloudClientId);
        return payload;
    }

    private static String extractCode(JsonObject payload)
    {
        return payload != null && payload.has("code") ? payload.get("code").getAsString() : "";
    }

    private static String extractUsername(String body)
    {
        try
        {
            JsonObject object = JsonParser.parseString(body).getAsJsonObject();
            if (object.has("username") && object.get("username").isJsonPrimitive())
            {
                return object.get("username").getAsString();
            }
        }
        catch (Exception e)
        {
            MMM.LOGGER.warn("[MMM_SYNC] failed to parse website link response username: {}", e.getMessage());
        }

        return "";
    }

    private static String resolveUsername(MinecraftClient client)
    {
        try
        {
            String username = client.getSession().getUsername();
            if (username != null && username.isBlank() == false)
            {
                return username;
            }
        }
        catch (Exception e)
        {
            MmmDebugLogger.debug(
                    "website-link-username",
                    JSON_PARSE_DEBUG_LOG_INTERVAL_MS,
                    "[MMM_SYNC] failed to resolve website link username: {}",
                    e.getMessage());
        }

        return client.player == null ? "Player" : client.player.getName().getString();
    }

    private static String sanitizeCode(String rawCode)
    {
        return rawCode == null ? "" : rawCode.toUpperCase().replaceAll("[^A-Z0-9]", "");
    }

    private static void persistLink(String linkedUsername)
    {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null)
        {
            return;
        }

        String nextUuid = client.player.getUuidAsString();
        String nextUsername = linkedUsername == null || linkedUsername.isBlank() ? resolveUsername(client) : linkedUsername;
        boolean identityChanged = nextUuid.equalsIgnoreCase(Configs.websiteLinkedMinecraftUuid) == false
                || nextUsername.equalsIgnoreCase(Configs.websiteLinkedMinecraftUsername) == false;
        Configs.websiteLinkedMinecraftUuid = nextUuid;
        Configs.websiteLinkedMinecraftUsername = nextUsername;
        Configs.websiteLinkedAtMs = System.currentTimeMillis();
        if (identityChanged)
        {
            Configs.websiteGlobalTotalBlocks = 0L;
            Configs.websiteGlobalTotalUpdatedAtMs = 0L;
        }
        Configs.saveToFile();
    }

    public record LinkState(Status status, String code, String detail, String linkedUsername)
    {
        public static LinkState idle()
        {
            return new LinkState(Status.IDLE, "", "", "");
        }

        public static LinkState submitting(String code)
        {
            return new LinkState(Status.SUBMITTING, code, "Saving link request and attempting claim...", "");
        }

        public static LinkState queued(String code, String detail)
        {
            return new LinkState(Status.QUEUED, code == null ? "" : code, detail == null ? "" : detail, "");
        }

        public static LinkState success(String linkedUsername)
        {
            String detail = linkedUsername == null || linkedUsername.isBlank()
                    ? "Website link completed."
                    : "Website link completed for " + linkedUsername + ".";
            return new LinkState(Status.SUCCESS, "", detail, linkedUsername == null ? "" : linkedUsername);
        }

        public static LinkState error(String detail)
        {
            return new LinkState(Status.ERROR, "", detail, "");
        }
    }

    public enum Status
    {
        IDLE,
        SUBMITTING,
        QUEUED,
        SUCCESS,
        ERROR
    }
}
