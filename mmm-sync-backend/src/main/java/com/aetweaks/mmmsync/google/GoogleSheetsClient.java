package com.aetweaks.mmmsync.google;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.aetweaks.mmmsync.config.BackendConfig;
import com.aetweaks.mmmsync.model.SyncRequest;
import com.aetweaks.mmmsync.storage.WorldColumnMap;
import com.aetweaks.mmmsync.util.ColumnUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class GoogleSheetsClient
{
    private final Gson gson;
    private final BackendConfig config;
    private final GoogleAccessTokenService tokenService;
    private final WorldColumnMap worldColumnMap;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private String cachedSheetTitle;

    public GoogleSheetsClient(Gson gson, BackendConfig config, GoogleAccessTokenService tokenService, WorldColumnMap worldColumnMap)
    {
        this.gson = gson;
        this.config = config;
        this.tokenService = tokenService;
        this.worldColumnMap = worldColumnMap;
    }

    public synchronized UpdateResult applyDigUpdate(SyncRequest request) throws Exception
    {
        String sheetTitle = resolveSheetTitle();
        String range = "'" + sheetTitle + "'!A" + this.config.dataStartRow() + ":ZZ";
        JsonArray rows = getValues(range);

        int playerColIndex = ColumnUtil.columnToIndex(this.config.playerColumn());
        int totalColIndex = ColumnUtil.columnToIndex(this.config.totalColumn());
        String worldColumn = this.worldColumnMap.resolveColumn(request.worldId(), request.worldName(), request.serverAddress());
        if (worldColumn == null && this.config.requireWorldMapping())
        {
            throw new IllegalStateException("No world column mapping found for " + request.worldName() + " (" + request.worldId() + ")");
        }

        int worldColIndex = worldColumn == null ? -1 : ColumnUtil.columnToIndex(worldColumn);

        int matchedRowNumber = -1;
        long currentTotal = 0L;
        long currentWorld = 0L;
        int caseInsensitiveMatches = 0;
        int caseInsensitiveRow = -1;
        long caseInsensitiveTotal = 0L;
        long caseInsensitiveWorld = 0L;

        for (int i = 0; i < rows.size(); i++)
        {
            JsonArray row = rows.get(i).getAsJsonArray();
            String username = getString(row, playerColIndex);
            if (username.isBlank())
            {
                continue;
            }

            int actualRowNumber = this.config.dataStartRow() + i;
            if (username.equals(request.username()))
            {
                matchedRowNumber = actualRowNumber;
                currentTotal = getLong(row, totalColIndex);
                currentWorld = worldColIndex >= 0 ? getLong(row, worldColIndex) : 0L;
                break;
            }

            if (username.equalsIgnoreCase(request.username()))
            {
                caseInsensitiveMatches++;
                caseInsensitiveRow = actualRowNumber;
                caseInsensitiveTotal = getLong(row, totalColIndex);
                caseInsensitiveWorld = worldColIndex >= 0 ? getLong(row, worldColIndex) : 0L;
            }
        }

        if (matchedRowNumber < 0)
        {
            if (caseInsensitiveMatches == 1)
            {
                matchedRowNumber = caseInsensitiveRow;
                currentTotal = caseInsensitiveTotal;
                currentWorld = caseInsensitiveWorld;
            }
            else
            {
                throw new IllegalStateException("No unique MMM row found for " + request.username());
            }
        }

        List<ValueUpdate> updates = new ArrayList<>();
        updates.add(new ValueUpdate("'" + sheetTitle + "'!" + this.config.totalColumn() + matchedRowNumber, currentTotal + request.deltaBlocks()));
        if (worldColumn != null)
        {
            updates.add(new ValueUpdate("'" + sheetTitle + "'!" + worldColumn + matchedRowNumber, currentWorld + request.deltaBlocks()));
        }

        batchUpdateValues(updates);
        return new UpdateResult(matchedRowNumber, currentTotal + request.deltaBlocks(), worldColumn, currentWorld + request.deltaBlocks());
    }

    private String resolveSheetTitle() throws Exception
    {
        if (this.cachedSheetTitle != null)
        {
            return this.cachedSheetTitle;
        }

        HttpRequest request = baseRequest("https://sheets.googleapis.com/v4/spreadsheets/" + this.config.sheetId()).GET().build();
        HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300)
        {
            throw new IOException("Failed to load spreadsheet metadata: " + response.statusCode() + " " + response.body());
        }

        JsonObject root = this.gson.fromJson(response.body(), JsonObject.class);
        JsonArray sheets = root.getAsJsonArray("sheets");
        for (int i = 0; i < sheets.size(); i++)
        {
            JsonObject properties = sheets.get(i).getAsJsonObject().getAsJsonObject("properties");
            if (properties.get("sheetId").getAsInt() == this.config.sheetGid())
            {
                this.cachedSheetTitle = properties.get("title").getAsString();
                return this.cachedSheetTitle;
            }
        }

        throw new IllegalStateException("Could not find sheet title for gid " + this.config.sheetGid());
    }

    private JsonArray getValues(String range) throws Exception
    {
        String encodedRange = URLEncoder.encode(range, StandardCharsets.UTF_8);
        HttpRequest request = baseRequest("https://sheets.googleapis.com/v4/spreadsheets/" + this.config.sheetId() + "/values/" + encodedRange).GET().build();
        HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300)
        {
            throw new IOException("Failed to read sheet values: " + response.statusCode() + " " + response.body());
        }

        JsonObject root = this.gson.fromJson(response.body(), JsonObject.class);
        JsonArray values = root.getAsJsonArray("values");
        return values == null ? new JsonArray() : values;
    }

    private void batchUpdateValues(List<ValueUpdate> updates) throws Exception
    {
        JsonArray data = new JsonArray();
        for (ValueUpdate update : updates)
        {
            JsonObject entry = new JsonObject();
            entry.addProperty("range", update.range());
            JsonArray rows = new JsonArray();
            JsonArray row = new JsonArray();
            row.add(update.value());
            rows.add(row);
            entry.add("values", rows);
            data.add(entry);
        }

        JsonObject body = new JsonObject();
        body.addProperty("valueInputOption", "USER_ENTERED");
        body.add("data", data);

        HttpRequest request = baseRequest("https://sheets.googleapis.com/v4/spreadsheets/" + this.config.sheetId() + "/values:batchUpdate")
                .POST(HttpRequest.BodyPublishers.ofString(this.gson.toJson(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300)
        {
            throw new IOException("Failed to update sheet values: " + response.statusCode() + " " + response.body());
        }
    }

    private HttpRequest.Builder baseRequest(String url) throws Exception
    {
        return HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + this.tokenService.getAccessToken())
                .header("Content-Type", "application/json");
    }

    private String getString(JsonArray row, int index)
    {
        if (index < 0 || index >= row.size())
        {
            return "";
        }
        return row.get(index).getAsString().trim();
    }

    private long getLong(JsonArray row, int index)
    {
        String value = getString(row, index);
        if (value.isBlank())
        {
            return 0L;
        }

        try
        {
            return Math.round(Double.parseDouble(value.replace(",", "")));
        }
        catch (NumberFormatException ignored)
        {
            return 0L;
        }
    }

    public record UpdateResult(int rowNumber, long totalDigs, String worldColumn, long worldDigs) {}

    private record ValueUpdate(String range, long value) {}
}
