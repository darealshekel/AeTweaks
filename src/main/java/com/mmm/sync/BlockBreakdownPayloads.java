package com.mmm.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mmm.config.Configs;
import com.mmm.storage.WorldSessionContext;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;

public final class BlockBreakdownPayloads
{
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private BlockBreakdownPayloads()
    {
    }

    public static JsonObject buildCurrentWorldBlockBreakdown(WorldSessionContext.WorldInfo worldInfo)
    {
        if (worldInfo == null)
        {
            return null;
        }

        Configs.WorldStatsEntry worldStats = Configs.getOrCreateWorldStats(
                worldInfo.id(),
                worldInfo.displayName(),
                worldInfo.kind(),
                worldInfo.host());
        Map<String, Long> breakdown = Configs.sanitizeBlockBreakdown(worldStats.blockBreakdown);
        if (breakdown.isEmpty())
        {
            return null;
        }

        long capturedAt = worldStats.blockBreakdownUpdatedAtMs > 0L ? worldStats.blockBreakdownUpdatedAtMs : Math.max(worldStats.lastSeenAt, System.currentTimeMillis());
        JsonObject object = new JsonObject();
        object.addProperty("source_key", ScoreboardSourceResolver.sourceKey(worldInfo.displayName(), worldInfo));
        object.addProperty("source_name", ScoreboardSourceResolver.displayName(worldInfo.displayName(), worldInfo));
        object.addProperty("captured_at", Instant.ofEpochMilli(capturedAt).toString());
        object.addProperty("scope", "local_player");
        object.addProperty("source", resolveSource(worldStats));
        object.addProperty("total_count", breakdown.values().stream().mapToLong(Long::longValue).sum());
        object.add("items", buildItems(breakdown));
        return object;
    }

    public static String fingerprintCurrentWorldBlockBreakdown(WorldSessionContext.WorldInfo worldInfo)
    {
        return fingerprint(buildCurrentWorldBlockBreakdown(worldInfo));
    }

    public static String fingerprint(JsonObject object)
    {
        if (object == null)
        {
            return "";
        }

        JsonObject minimal = new JsonObject();
        copyIfPresent(object, minimal, "source_key");
        copyIfPresent(object, minimal, "source_name");
        copyIfPresent(object, minimal, "scope");
        copyIfPresent(object, minimal, "source");
        copyIfPresent(object, minimal, "total_count");
        if (object.has("items"))
        {
            minimal.add("items", object.get("items"));
        }
        return GSON.toJson(minimal);
    }

    private static JsonArray buildItems(Map<String, Long> breakdown)
    {
        JsonArray items = new JsonArray();
        breakdown.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> {
                    JsonObject item = new JsonObject();
                    item.addProperty("block_id", entry.getKey());
                    item.addProperty("count", entry.getValue());
                    items.add(item);
                });
        return items;
    }

    private static String resolveSource(Configs.WorldStatsEntry worldStats)
    {
        String source = Configs.sanitizeBlockBreakdownSource(worldStats.blockBreakdownSource);
        return source.isBlank() ? Configs.BLOCK_BREAKDOWN_SOURCE_LOCAL_OBSERVED : source;
    }

    private static void copyIfPresent(JsonObject from, JsonObject to, String key)
    {
        if (from.has(key))
        {
            to.add(key, from.get(key));
        }
    }
}
