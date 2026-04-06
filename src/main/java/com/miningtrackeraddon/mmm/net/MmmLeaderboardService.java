package com.miningtrackeraddon.mmm.net;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningtrackeraddon.Reference;
import com.miningtrackeraddon.config.Configs;
import com.miningtrackeraddon.mmm.data.MmmDigBreakdown;
import com.miningtrackeraddon.mmm.data.MmmLeaderboardEntry;

public class MmmLeaderboardService
{
    private static final String MMM_GVIZ_URL = "https://docs.google.com/spreadsheets/d/1ZYHywH13pjoUGUx-BZU_kncINbcxAnRGNW2MAZliiyA/gviz/tq?tqx=out:json&gid=450768443";
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public List<MmmLeaderboardEntry> fetchEntries() throws IOException, InterruptedException
    {
        HttpRequest request = HttpRequest.newBuilder(URI.create(MMM_GVIZ_URL))
                .header("User-Agent", Reference.MOD_NAME + "/" + Reference.MOD_VERSION)
                .timeout(Duration.ofSeconds(12))
                .GET()
                .build();

        HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300)
        {
            throw new IOException("Unexpected MMM HTTP status " + response.statusCode());
        }

        return parseEntries(response.body());
    }

    private List<MmmLeaderboardEntry> parseEntries(String rawResponse) throws IOException
    {
        String jsonPayload = unwrapGvizResponse(rawResponse);
        JsonObject root = JsonParser.parseString(jsonPayload).getAsJsonObject();
        JsonObject table = root.getAsJsonObject("table");
        JsonArray rows = table.getAsJsonArray("rows");
        List<String> sourceLabels = Configs.getMmmBreakdownLabels();
        if (rows == null || rows.isEmpty())
        {
            throw new IOException("MMM sheet parser found no rows");
        }

        List<MmmLeaderboardEntry> entries = new ArrayList<>();
        for (JsonElement rowElement : rows)
        {
            JsonArray cells = rowElement.getAsJsonObject().getAsJsonArray("c");
            if (cells == null || cells.size() < 10)
            {
                continue;
            }

            Integer rank = getInteger(cells, 5);
            String username = getString(cells, 8);
            Long totalDigs = getLong(cells, 9);
            if (rank == null || username == null || username.isBlank() || totalDigs == null)
            {
                continue;
            }

            List<MmmDigBreakdown> breakdown = new ArrayList<>();
            int breakdownIndex = 0;
            for (int valueColumn = 11; valueColumn < cells.size() && breakdownIndex < sourceLabels.size(); valueColumn += 2)
            {
                Long digs = getLong(cells, valueColumn);
                if (digs == null || digs <= 0L)
                {
                    breakdownIndex++;
                    continue;
                }

                String sourceName = sourceLabels.get(breakdownIndex);
                boolean singleplayer = sourceName.endsWith("LAB") || sourceName.equals("TTLAB") || sourceName.equals("E ndLAB");
                breakdown.add(new MmmDigBreakdown(sourceName, digs, singleplayer));
                breakdownIndex++;
            }

            entries.add(new MmmLeaderboardEntry(rank, username, totalDigs, "", "", breakdown));
        }

        if (entries.isEmpty())
        {
            throw new IOException("MMM sheet parser found no leaderboard rows");
        }

        entries.sort(Comparator.comparingInt(MmmLeaderboardEntry::rank));
        return entries;
    }

    private String unwrapGvizResponse(String rawResponse) throws IOException
    {
        int start = rawResponse.indexOf('{');
        int end = rawResponse.lastIndexOf('}');
        if (start < 0 || end < start)
        {
            throw new IOException("Invalid MMM GViz response");
        }
        return rawResponse.substring(start, end + 1);
    }

    private String getString(JsonArray cells, int index)
    {
        JsonObject cell = getCell(cells, index);
        if (cell == null)
        {
            return null;
        }

        JsonElement value = cell.get("v");
        if (value != null && value.isJsonPrimitive())
        {
            return value.getAsString().trim();
        }

        return null;
    }

    private Integer getInteger(JsonArray cells, int index)
    {
        Long value = getLong(cells, index);
        return value == null ? null : Math.toIntExact(value);
    }

    private Long getLong(JsonArray cells, int index)
    {
        JsonObject cell = getCell(cells, index);
        if (cell == null)
        {
            return null;
        }

        JsonElement value = cell.get("v");
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber())
        {
            return Math.round(value.getAsDouble());
        }

        JsonElement formatted = cell.get("f");
        if (formatted != null && formatted.isJsonPrimitive())
        {
            String text = formatted.getAsString().trim();
            if (!text.isEmpty())
            {
                try
                {
                    return Long.parseLong(text.replace(",", ""));
                }
                catch (NumberFormatException ignored)
                {
                    return null;
                }
            }
        }

        return null;
    }

    private JsonObject getCell(JsonArray cells, int index)
    {
        if (index < 0 || index >= cells.size())
        {
            return null;
        }

        JsonElement cell = cells.get(index);
        return cell != null && cell.isJsonObject() ? cell.getAsJsonObject() : null;
    }
}
