package com.mmm.sync;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mmm.MMM;
import com.mmm.config.Configs;
import com.mmm.util.MmmDebugLogger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.MinecraftClient;

public final class WebsiteProfileTotals
{
    private static final String PROFILE_API = "https://www.mmmaniacs.com/api/player-detail?slug=%s&refreshCache=1&liveTs=%d";
    private static final long MIN_REFRESH_INTERVAL_MS = 30_000L;
    private static final long REFRESH_LOG_INTERVAL_MS = 30_000L;
    private static final long JSON_PARSE_DEBUG_LOG_INTERVAL_MS = 30_000L;
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8L))
            .build();
    private static final AtomicBoolean REFRESH_IN_FLIGHT = new AtomicBoolean(false);
    private static volatile long lastRefreshAttemptMs;

    private WebsiteProfileTotals()
    {
    }

    public static void refresh(boolean force)
    {
        long now = System.currentTimeMillis();
        if (!force && now - lastRefreshAttemptMs < MIN_REFRESH_INTERVAL_MS)
        {
            return;
        }

        String username = resolveUsername();
        if (username.isBlank())
        {
            return;
        }

        if (!REFRESH_IN_FLIGHT.compareAndSet(false, true))
        {
            return;
        }

        lastRefreshAttemptMs = now;
        String encodedUsername = URLEncoder.encode(username.toLowerCase(), StandardCharsets.UTF_8);
        String url = String.format(PROFILE_API, encodedUsername, now);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10L))
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .GET()
                .build();

        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, throwable) -> {
                    try
                    {
                        if (throwable != null || response == null || response.statusCode() < 200 || response.statusCode() >= 300)
                        {
                            MmmDebugLogger.info(
                                    "website-profile-total-refresh-failed",
                                    REFRESH_LOG_INTERVAL_MS,
                                    "[MMM_SYNC] profile-total-refresh-failed username={} status={} error={}",
                                    username,
                                    response == null ? -1 : response.statusCode(),
                                    throwable == null ? "" : throwable.getMessage());
                            return;
                        }

                        long blocksNum = extractBlocksNum(response.body());
                        if (blocksNum <= 0L)
                        {
                            return;
                        }

                        long currentTotal = Math.max(0L, Configs.websiteGlobalTotalBlocks);
                        if (currentTotal > 0L && blocksNum < currentTotal)
                        {
                            MmmDebugLogger.info(
                                    "website-profile-total-refresh-stale",
                                    REFRESH_LOG_INTERVAL_MS,
                                    "[MMM_SYNC] profile-total-refresh-stale username={} blocksNum={} currentGlobalTotal={}",
                                    username,
                                    blocksNum,
                                    currentTotal);
                            return;
                        }

                        if (blocksNum != currentTotal)
                        {
                            Configs.websiteGlobalTotalBlocks = blocksNum;
                            Configs.websiteGlobalTotalUpdatedAtMs = System.currentTimeMillis();
                            Configs.saveToFile();
                        }
                        MmmDebugLogger.info(
                                "website-profile-total-refresh-ok",
                                REFRESH_LOG_INTERVAL_MS,
                                "[MMM_SYNC] profile-total-refresh-ok username={} blocksNum={}",
                                username,
                                blocksNum);
                    }
                    finally
                    {
                        REFRESH_IN_FLIGHT.set(false);
                    }
                });
    }

    private static long extractBlocksNum(String body)
    {
        try
        {
            JsonObject object = JsonParser.parseString(body).getAsJsonObject();
            return getLong(object, "blocksNum", getLong(object, "blocksMined", 0L));
        }
        catch (Exception e)
        {
            MMM.LOGGER.warn("[MMM_SYNC] failed to parse profile total response: {}", e.getMessage());
            return 0L;
        }
    }

    private static long getLong(JsonObject object, String key, long fallback)
    {
        if (object == null || !object.has(key))
        {
            return fallback;
        }

        try
        {
            JsonElement element = object.get(key);
            return element != null && element.isJsonPrimitive() ? element.getAsLong() : fallback;
        }
        catch (Exception e)
        {
            MmmDebugLogger.debug(
                    "website-profile-total-" + key,
                    JSON_PARSE_DEBUG_LOG_INTERVAL_MS,
                    "[MMM_SYNC] failed to parse profile total field '{}': {}",
                    key,
                    e.getMessage());
            return fallback;
        }
    }

    private static String resolveUsername()
    {
        if (Configs.websiteLinkedMinecraftUsername != null && !Configs.websiteLinkedMinecraftUsername.isBlank())
        {
            return Configs.websiteLinkedMinecraftUsername.trim();
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getSession() == null)
        {
            return "";
        }

        try
        {
            String username = client.getSession().getUsername();
            return username == null ? "" : username.trim();
        }
        catch (Exception e)
        {
            MmmDebugLogger.debug(
                    "website-profile-username",
                    JSON_PARSE_DEBUG_LOG_INTERVAL_MS,
                    "[MMM_SYNC] failed to resolve profile username: {}",
                    e.getMessage());
            return "";
        }
    }
}
