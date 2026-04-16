package com.miningtrackeraddon.sync;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.BiConsumer;
import com.miningtrackeraddon.MiningTrackerAddon;
import com.miningtrackeraddon.config.Configs;

final class ApiClient
{
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10L))
            .build();

    private ApiClient()
    {
    }

    static void postJson(String endpoint, String secret, String jsonBody, BiConsumer<HttpResponse<String>, Throwable> callback)
    {
        try
        {
            debugSend(endpoint, jsonBody);
            HTTP_CLIENT.sendAsync(buildPostJsonRequest(endpoint, secret, jsonBody, Map.of()), HttpResponse.BodyHandlers.ofString())
                    .whenComplete(callback);
        }
        catch (Exception exception)
        {
            callback.accept(null, exception);
        }
    }

    static HttpResponse<String> postJsonBlocking(String endpoint, String secret, String jsonBody, Map<String, String> extraHeaders) throws Exception
    {
        debugSend(endpoint, jsonBody);
        return HTTP_CLIENT.send(buildPostJsonRequest(endpoint, secret, jsonBody, extraHeaders), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest buildPostJsonRequest(String endpoint, String secret, String jsonBody, Map<String, String> extraHeaders)
    {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(15L))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

        if (secret != null && secret.isBlank() == false)
        {
            builder.header("x-sync-secret", secret);
        }

        if (extraHeaders != null)
        {
            for (Map.Entry<String, String> entry : extraHeaders.entrySet())
            {
                if (entry.getKey() != null && entry.getValue() != null && entry.getValue().isBlank() == false)
                {
                    builder.header(entry.getKey(), entry.getValue());
                }
            }
        }

        return builder.build();
    }

    private static void debugSend(String endpoint, String jsonBody)
    {
        if (Configs.Generic.WEBSITE_SYNC_DEBUG.getBooleanValue() == false)
        {
            return;
        }

        int bodyLength = jsonBody == null ? 0 : jsonBody.length();
        MiningTrackerAddon.LOGGER.info("[AET_DEBUG] sync-request-send endpoint={} bodyLength={}", endpoint, bodyLength);
        MiningTrackerAddon.LOGGER.info("[SYNC_DEBUG] sending payload endpoint={} bodyLength={}", endpoint, bodyLength);
    }
}
