package com.miningtrackeraddon.sync;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.BiConsumer;
import com.miningtrackeraddon.MiningTrackerAddon;

final class ApiClient
{
    private static final String MMM_SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJIUzI1NiIsInJlZiI6Imptc3BvaXJ5emZpbHBwaW92aG1mIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzY5NjE3NjYsImV4cCI6MjA5MjUzNzc2Nn0.YHMWM0T37BHFU6m2vpw9s-GlmY6NBitE4Ku-6hpA-uQ";
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
            logSend(endpoint, jsonBody, Map.of(), secret);
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
        logSend(endpoint, jsonBody, extraHeaders, secret);
        return HTTP_CLIENT.send(buildPostJsonRequest(endpoint, secret, jsonBody, extraHeaders), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest buildPostJsonRequest(String endpoint, String secret, String jsonBody, Map<String, String> extraHeaders)
    {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(15L))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

        if (isSupabaseFunctionEndpoint(endpoint))
        {
            builder.header("apikey", MMM_SUPABASE_ANON_KEY);
            builder.header("Authorization", "Bearer " + MMM_SUPABASE_ANON_KEY);
        }

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

    private static boolean isSupabaseFunctionEndpoint(String endpoint)
    {
        return endpoint != null
                && endpoint.startsWith("https://jmspoiryzfilppiovhmf.supabase.co/functions/v1/");
    }

    private static void logSend(String endpoint, String jsonBody, Map<String, String> extraHeaders, String secret)
    {
        int bodyLength = jsonBody == null ? 0 : jsonBody.length();
        boolean supabaseAuth = isSupabaseFunctionEndpoint(endpoint);
        boolean syncSecret = secret != null && secret.isBlank() == false;
        int extraHeaderCount = extraHeaders == null ? 0 : extraHeaders.size();

        MiningTrackerAddon.LOGGER.info(
                "[MMM_SYNC] http-request endpoint={} bodyLength={} supabaseAnonAuth={} syncSecretHeader={} extraHeaderCount={}",
                endpoint,
                bodyLength,
                supabaseAuth,
                syncSecret,
                extraHeaderCount
        );
    }
}
