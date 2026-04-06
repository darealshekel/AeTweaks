package com.aetweaks.mmmsync;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

import com.aetweaks.mmmsync.config.BackendConfig;
import com.aetweaks.mmmsync.google.GoogleAccessTokenService;
import com.aetweaks.mmmsync.google.GoogleSheetsClient;
import com.aetweaks.mmmsync.model.SyncRequest;
import com.aetweaks.mmmsync.storage.ProcessedBatchStore;
import com.aetweaks.mmmsync.storage.WorldColumnMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public class MmmSyncBackend
{
    public static void main(String[] args) throws Exception
    {
        Gson gson = new GsonBuilder().disableHtmlEscaping().create();
        BackendConfig config = BackendConfig.load();
        ProcessedBatchStore processedBatchStore = new ProcessedBatchStore(gson, config.processedBatchFile());
        WorldColumnMap worldColumnMap = WorldColumnMap.load(gson, config.worldMapFile());
        GoogleAccessTokenService tokenService = new GoogleAccessTokenService(gson, config);
        GoogleSheetsClient sheetsClient = new GoogleSheetsClient(gson, config, tokenService, worldColumnMap);

        HttpServer server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        server.createContext("/health", exchange -> writeJson(exchange, 200, gson.toJson(Map.of("ok", true))));
        server.createContext("/mmm/update", exchange -> handleUpdate(exchange, gson, config, processedBatchStore, sheetsClient));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("MMM sync backend listening on port " + config.port());
    }

    private static void handleUpdate(
            HttpExchange exchange,
            Gson gson,
            BackendConfig config,
            ProcessedBatchStore processedBatchStore,
            GoogleSheetsClient sheetsClient) throws IOException
    {
        try (exchange)
        {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod()))
            {
                writeJson(exchange, 405, gson.toJson(Map.of("ok", false, "error", "Method not allowed")));
                return;
            }

            if (!config.apiKey().isBlank())
            {
                String provided = exchange.getRequestHeaders().getFirst("X-AeTweaks-Key");
                if (!config.apiKey().equals(provided))
                {
                    writeJson(exchange, 401, gson.toJson(Map.of("ok", false, "error", "Unauthorized")));
                    return;
                }
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            SyncRequest request = gson.fromJson(body, SyncRequest.class);
            request.validate();
            System.out.println("MMM sync request: user=" + request.username() +
                    ", world=" + request.worldName() +
                    ", address=" + blankIfMissing(request.serverAddress()) +
                    ", delta=" + request.deltaBlocks());

            if (processedBatchStore.has(request.batchId()))
            {
                writeJson(exchange, 200, gson.toJson(Map.of("ok", true, "duplicate", true)));
                return;
            }

            GoogleSheetsClient.UpdateResult result = sheetsClient.applyDigUpdate(request);
            processedBatchStore.remember(request.batchId());
            System.out.println("MMM sync applied: user=" + request.username() +
                    ", world=" + request.worldName() +
                    ", address=" + blankIfMissing(request.serverAddress()) +
                    ", delta=" + request.deltaBlocks() +
                    ", total=" + result.totalDigs() +
                    ", worldColumn=" + blankIfMissing(result.worldColumn()) +
                    ", worldDigs=" + result.worldDigs());
            writeJson(exchange, 200, gson.toJson(Map.of(
                    "ok", true,
                    "row", result.rowNumber(),
                    "totalDigs", result.totalDigs(),
                    "worldColumn", blankIfMissing(result.worldColumn()),
                    "worldDigs", result.worldDigs()
            )));
        }
        catch (IllegalArgumentException exception)
        {
            writeJson(exchange, 400, errorJson(gson, exception.getMessage()));
        }
        catch (Exception exception)
        {
            exception.printStackTrace();
            writeJson(exchange, 500, errorJson(gson, exception.getMessage()));
        }
    }

    private static String errorJson(Gson gson, String message)
    {
        JsonObject object = new JsonObject();
        object.addProperty("ok", false);
        object.addProperty("error", message == null ? "Unknown error" : message);
        return gson.toJson(object);
    }

    private static String blankIfMissing(String value)
    {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static void writeJson(HttpExchange exchange, int status, String json) throws IOException
    {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody())
        {
            outputStream.write(bytes);
        }
    }
}
