package com.mmm.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mmm.MMM;
import com.mmm.Reference;
import com.mmm.util.MmmDebugLogger;
import fi.dy.masa.malilib.util.FileUtils;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

final class SyncDeltaStore
{
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final String STATE_FILE_NAME = Reference.STORAGE_ID + "-sync-state.json";
    private static final Map<String, String> SECTION_FINGERPRINTS = new LinkedHashMap<>();
    private static final Map<String, Map<String, Long>> CURRENT_WORLD_BREAKDOWNS = new LinkedHashMap<>();
    private static final Map<String, JsonObject> AETERNUM_LEADERBOARDS = new LinkedHashMap<>();
    private static final long JSON_PARSE_DEBUG_LOG_INTERVAL_MS = 30_000L;
    private static boolean loaded;

    private SyncDeltaStore()
    {
    }

    static synchronized JsonObject currentWorldBlockBreakdownForSync(JsonObject fullBreakdown)
    {
        loadIfNeeded();
        if (fullBreakdown == null || fullBreakdown.has("items") == false || fullBreakdown.get("items").isJsonArray() == false)
        {
            return null;
        }

        String key = sectionSourceKey(fullBreakdown, "current-world");
        Map<String, Long> current = readItemCounts(fullBreakdown);
        if (current.isEmpty())
        {
            return null;
        }

        Map<String, Long> previous = CURRENT_WORLD_BREAKDOWNS.get(key);
        if (previous == null || previous.isEmpty())
        {
            return withMode(fullBreakdown, "full");
        }

        Map<String, Long> deltas = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : current.entrySet())
        {
            long oldValue = Math.max(0L, previous.getOrDefault(entry.getKey(), 0L));
            long newValue = Math.max(0L, entry.getValue());
            if (newValue < oldValue)
            {
                return withMode(fullBreakdown, "full");
            }
            if (newValue > oldValue)
            {
                deltas.put(entry.getKey(), newValue - oldValue);
            }
        }

        for (String oldBlockId : previous.keySet())
        {
            if (current.containsKey(oldBlockId) == false)
            {
                return withMode(fullBreakdown, "full");
            }
        }

        if (deltas.isEmpty())
        {
            return null;
        }

        JsonObject delta = copyBreakdownEnvelope(fullBreakdown);
        delta.addProperty("mode", "delta");
        delta.add("items", buildDeltaItems(deltas, previous, current));
        return delta;
    }

    static synchronized boolean shouldSendServerPlayerBlockBreakdowns(JsonObject payload)
    {
        return shouldSendSection("server-player-breakdowns:" + sectionSourceKey(payload, "unknown"),
                ServerPlayerBlockBreakdownScanner.fingerprint(payload));
    }

    static synchronized boolean shouldSendSourceScan(JsonObject payload)
    {
        String fingerprint = stringValue(payload, "scan_fingerprint");
        if (fingerprint.isBlank())
        {
            fingerprint = GSON.toJson(payload);
        }
        return shouldSendSection("source-scan:" + fingerprintSourceKey(payload), fingerprint);
    }

    static synchronized JsonObject aeternumLeaderboardForSync(JsonObject fullPayload)
    {
        loadIfNeeded();
        if (fullPayload == null || fullPayload.has("entries") == false || fullPayload.get("entries").isJsonArray() == false)
        {
            return null;
        }

        String key = aeternumLeaderboardKey(fullPayload);
        String fingerprint = GSON.toJson(fullPayload);
        if (fingerprint.equals(SECTION_FINGERPRINTS.get(key)))
        {
            return null;
        }

        JsonObject previous = AETERNUM_LEADERBOARDS.get(key);
        if (previous == null || previous.has("entries") == false || previous.get("entries").isJsonArray() == false)
        {
            JsonObject full = fullPayload.deepCopy();
            full.addProperty("mode", "full");
            return full;
        }

        JsonObject delta = copyLeaderboardEnvelope(fullPayload);
        delta.addProperty("mode", "delta");

        Map<String, JsonObject> previousRows = leaderboardRowsByUsername(previous);
        JsonArray changedRows = new JsonArray();
        for (JsonElement element : fullPayload.getAsJsonArray("entries"))
        {
            if (element == null || element.isJsonObject() == false)
            {
                continue;
            }

            JsonObject row = element.getAsJsonObject();
            String usernameKey = stringValue(row, "username").toLowerCase(java.util.Locale.ROOT);
            if (usernameKey.isBlank())
            {
                continue;
            }

            JsonObject previousRow = previousRows.get(usernameKey);
            if (previousRow == null || GSON.toJson(previousRow).equals(GSON.toJson(row)) == false)
            {
                changedRows.add(row.deepCopy());
            }
        }

        delta.add("entries", changedRows);
        return delta;
    }

    static synchronized void markPayloadSynced(JsonObject payload)
    {
        loadIfNeeded();
        if (payload == null)
        {
            return;
        }

        if (payload.has("current_world_block_breakdown") && payload.get("current_world_block_breakdown").isJsonObject())
        {
            markCurrentWorldBreakdownSynced(payload.getAsJsonObject("current_world_block_breakdown"));
        }
        if (payload.has("server_player_block_breakdowns") && payload.get("server_player_block_breakdowns").isJsonObject())
        {
            JsonObject section = payload.getAsJsonObject("server_player_block_breakdowns");
            markSectionSynced("server-player-breakdowns:" + sectionSourceKey(section, "unknown"),
                    ServerPlayerBlockBreakdownScanner.fingerprint(section));
        }
        if (payload.has("source_scan") && payload.get("source_scan").isJsonObject())
        {
            JsonObject section = payload.getAsJsonObject("source_scan");
            String fingerprint = stringValue(section, "scan_fingerprint");
            if (fingerprint.isBlank())
            {
                fingerprint = GSON.toJson(section);
            }
            markSectionSynced("source-scan:" + fingerprintSourceKey(section), fingerprint);
        }
        if (payload.has("aeternum_leaderboard") && payload.get("aeternum_leaderboard").isJsonObject())
        {
            JsonObject section = payload.getAsJsonObject("aeternum_leaderboard");
            markAeternumLeaderboardSynced(section);
        }
        save();
    }

    private static boolean shouldSendSection(String key, String fingerprint)
    {
        loadIfNeeded();
        if (fingerprint == null || fingerprint.isBlank())
        {
            return false;
        }
        return fingerprint.equals(SECTION_FINGERPRINTS.get(key)) == false;
    }

    private static void markSectionSynced(String key, String fingerprint)
    {
        if (key == null || key.isBlank() || fingerprint == null || fingerprint.isBlank())
        {
            return;
        }
        SECTION_FINGERPRINTS.put(key, fingerprint);
    }

    private static void markCurrentWorldBreakdownSynced(JsonObject payload)
    {
        String key = sectionSourceKey(payload, "current-world");
        String mode = stringValue(payload, "mode");
        Map<String, Long> current = readItemCounts(payload);
        if (current.isEmpty())
        {
            return;
        }

        if ("delta".equals(mode))
        {
            Map<String, Long> merged = new LinkedHashMap<>(CURRENT_WORLD_BREAKDOWNS.getOrDefault(key, Map.of()));
            for (Map.Entry<String, Long> entry : current.entrySet())
            {
                merged.put(entry.getKey(), Math.max(0L, merged.getOrDefault(entry.getKey(), 0L)) + Math.max(0L, entry.getValue()));
            }
            CURRENT_WORLD_BREAKDOWNS.put(key, merged);
            return;
        }

        CURRENT_WORLD_BREAKDOWNS.put(key, current);
    }

    private static void markAeternumLeaderboardSynced(JsonObject payload)
    {
        String key = aeternumLeaderboardKey(payload);
        String mode = stringValue(payload, "mode");
        JsonObject next = "delta".equals(mode)
                ? mergeAeternumLeaderboardSnapshot(AETERNUM_LEADERBOARDS.get(key), payload)
                : payload.deepCopy();
        next.remove("mode");
        AETERNUM_LEADERBOARDS.put(key, next);
        markSectionSynced(key, GSON.toJson(next));
    }

    private static JsonObject mergeAeternumLeaderboardSnapshot(JsonObject previous, JsonObject delta)
    {
        JsonObject next = previous == null ? copyLeaderboardEnvelope(delta) : previous.deepCopy();
        copyIfPresent(delta, next, "server_name");
        copyIfPresent(delta, next, "objective_title");
        copyIfPresent(delta, next, "captured_at");
        copyIfPresent(delta, next, "source_type");
        copyIfPresent(delta, next, "total_digs");
        copyIfPresent(delta, next, "filtered_fake_usernames");

        Map<String, JsonObject> rows = leaderboardRowsByUsername(next);
        if (delta.has("entries") && delta.get("entries").isJsonArray())
        {
            for (JsonElement element : delta.getAsJsonArray("entries"))
            {
                if (element == null || element.isJsonObject() == false)
                {
                    continue;
                }

                JsonObject row = element.getAsJsonObject();
                String usernameKey = stringValue(row, "username").toLowerCase(java.util.Locale.ROOT);
                if (usernameKey.isBlank() == false)
                {
                    rows.put(usernameKey, row.deepCopy());
                }
            }
        }

        JsonArray entries = new JsonArray();
        rows.values().stream()
                .sorted((left, right) -> {
                    int leftRank = intValue(left, "rank", Integer.MAX_VALUE);
                    int rightRank = intValue(right, "rank", Integer.MAX_VALUE);
                    if (leftRank != rightRank)
                    {
                        return Integer.compare(leftRank, rightRank);
                    }
                    return stringValue(left, "username").compareToIgnoreCase(stringValue(right, "username"));
                })
                .forEach(entries::add);
        next.add("entries", entries);
        return next;
    }

    private static JsonObject copyLeaderboardEnvelope(JsonObject source)
    {
        JsonObject target = new JsonObject();
        copyIfPresent(source, target, "server_name");
        copyIfPresent(source, target, "objective_title");
        copyIfPresent(source, target, "captured_at");
        copyIfPresent(source, target, "source_type");
        copyIfPresent(source, target, "total_digs");
        copyIfPresent(source, target, "filtered_fake_usernames");
        return target;
    }

    private static Map<String, JsonObject> leaderboardRowsByUsername(JsonObject payload)
    {
        Map<String, JsonObject> rows = new LinkedHashMap<>();
        if (payload == null || payload.has("entries") == false || payload.get("entries").isJsonArray() == false)
        {
            return rows;
        }

        for (JsonElement element : payload.getAsJsonArray("entries"))
        {
            if (element == null || element.isJsonObject() == false)
            {
                continue;
            }

            JsonObject row = element.getAsJsonObject();
            String usernameKey = stringValue(row, "username").toLowerCase(java.util.Locale.ROOT);
            if (usernameKey.isBlank() == false)
            {
                rows.put(usernameKey, row.deepCopy());
            }
        }
        return rows;
    }

    private static String aeternumLeaderboardKey(JsonObject payload)
    {
        return "aeternum-leaderboard:" + stringValue(payload, "server_name").toLowerCase(java.util.Locale.ROOT);
    }

    private static JsonObject withMode(JsonObject fullBreakdown, String mode)
    {
        JsonObject copy = fullBreakdown.deepCopy();
        copy.addProperty("mode", mode);
        return copy;
    }

    private static JsonObject copyBreakdownEnvelope(JsonObject source)
    {
        JsonObject target = new JsonObject();
        copyIfPresent(source, target, "source_key");
        copyIfPresent(source, target, "source_name");
        copyIfPresent(source, target, "captured_at");
        copyIfPresent(source, target, "scope");
        copyIfPresent(source, target, "source");
        copyIfPresent(source, target, "total_count");
        return target;
    }

    private static JsonArray buildItems(Map<String, Long> counts)
    {
        JsonArray items = new JsonArray();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> {
                    JsonObject item = new JsonObject();
                    item.addProperty("block_id", entry.getKey());
                    item.addProperty("count", entry.getValue());
                    items.add(item);
                });
        return items;
    }

    private static JsonArray buildDeltaItems(Map<String, Long> deltas, Map<String, Long> previous, Map<String, Long> current)
    {
        JsonArray items = new JsonArray();
        deltas.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> {
                    String blockId = entry.getKey();
                    JsonObject item = new JsonObject();
                    item.addProperty("block_id", blockId);
                    item.addProperty("count", entry.getValue());
                    item.addProperty("previous_count", Math.max(0L, previous.getOrDefault(blockId, 0L)));
                    item.addProperty("current_count", Math.max(0L, current.getOrDefault(blockId, 0L)));
                    items.add(item);
                });
        return items;
    }

    private static Map<String, Long> readItemCounts(JsonObject payload)
    {
        Map<String, Long> counts = new LinkedHashMap<>();
        if (payload == null || payload.has("items") == false || payload.get("items").isJsonArray() == false)
        {
            return counts;
        }
        for (JsonElement element : payload.getAsJsonArray("items"))
        {
            if (element == null || element.isJsonObject() == false)
            {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            String blockId = stringValue(item, "block_id");
            if (blockId.isBlank() || item.has("count") == false || item.get("count").isJsonPrimitive() == false)
            {
                continue;
            }
            try
            {
                long count = Math.max(0L, item.get("count").getAsLong());
                if (count > 0L)
                {
                    counts.put(blockId, count);
                }
            }
            catch (Exception e)
            {
                MmmDebugLogger.debug(
                        "sync-delta-item-count-" + blockId,
                        JSON_PARSE_DEBUG_LOG_INTERVAL_MS,
                        "[MMM_SYNC] failed to parse sync delta item count blockId={}: {}",
                        blockId,
                        e.getMessage());
            }
        }
        return counts;
    }

    private static String sectionSourceKey(JsonObject payload, String fallback)
    {
        String sourceKey = stringValue(payload, "source_key");
        if (sourceKey.isBlank())
        {
            sourceKey = stringValue(payload, "source_name");
        }
        return sourceKey.isBlank() ? fallback : sourceKey;
    }

    private static String fingerprintSourceKey(JsonObject payload)
    {
        String serverName = stringValue(payload, "server_name");
        if (serverName.isBlank())
        {
            serverName = stringValue(payload, "scoreboard_title");
        }
        return serverName.isBlank() ? "unknown" : serverName;
    }

    private static String stringValue(JsonObject object, String key)
    {
        if (object != null && object.has(key) && object.get(key).isJsonPrimitive())
        {
            try
            {
                return object.get(key).getAsString().trim();
            }
            catch (Exception e)
            {
                MmmDebugLogger.debug(
                        "sync-delta-string-" + key,
                        JSON_PARSE_DEBUG_LOG_INTERVAL_MS,
                        "[MMM_SYNC] failed to parse sync delta string field '{}': {}",
                        key,
                        e.getMessage());
            }
        }
        return "";
    }

    private static int intValue(JsonObject object, String key, int fallback)
    {
        if (object != null && object.has(key) && object.get(key).isJsonPrimitive())
        {
            try
            {
                return object.get(key).getAsInt();
            }
            catch (Exception e)
            {
                MmmDebugLogger.debug(
                        "sync-delta-int-" + key,
                        JSON_PARSE_DEBUG_LOG_INTERVAL_MS,
                        "[MMM_SYNC] failed to parse sync delta int field '{}': {}",
                        key,
                        e.getMessage());
            }
        }
        return fallback;
    }

    private static void copyIfPresent(JsonObject from, JsonObject to, String key)
    {
        if (from.has(key))
        {
            to.add(key, from.get(key).deepCopy());
        }
    }

    private static void loadIfNeeded()
    {
        if (loaded)
        {
            return;
        }
        loaded = true;
        File file = stateFile();
        if (file.isFile() == false)
        {
            return;
        }
        try (FileReader reader = new FileReader(file))
        {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.has("sectionFingerprints") && root.get("sectionFingerprints").isJsonObject())
            {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("sectionFingerprints").entrySet())
                {
                    if (entry.getValue().isJsonPrimitive())
                    {
                        SECTION_FINGERPRINTS.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }
            if (root.has("currentWorldBreakdowns") && root.get("currentWorldBreakdowns").isJsonObject())
            {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("currentWorldBreakdowns").entrySet())
                {
                    if (entry.getValue().isJsonObject())
                    {
                        CURRENT_WORLD_BREAKDOWNS.put(entry.getKey(), readCountsObject(entry.getValue().getAsJsonObject()));
                    }
                }
            }
            if (root.has("aeternumLeaderboards") && root.get("aeternumLeaderboards").isJsonObject())
            {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("aeternumLeaderboards").entrySet())
                {
                    if (entry.getValue().isJsonObject())
                    {
                        AETERNUM_LEADERBOARDS.put(entry.getKey(), entry.getValue().getAsJsonObject().deepCopy());
                    }
                }
            }
        }
        catch (Exception exception)
        {
            MMM.LOGGER.warn("[MMM_SYNC] sync-state-load-failed error={}", exception.getMessage());
        }
    }

    private static Map<String, Long> readCountsObject(JsonObject object)
    {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet())
        {
            if (entry.getValue().isJsonPrimitive())
            {
                try
                {
                    counts.put(entry.getKey(), Math.max(0L, entry.getValue().getAsLong()));
                }
                catch (Exception e)
                {
                    MmmDebugLogger.debug(
                            "sync-delta-counts-object-" + entry.getKey(),
                            JSON_PARSE_DEBUG_LOG_INTERVAL_MS,
                            "[MMM_SYNC] failed to parse sync delta counts field '{}': {}",
                            entry.getKey(),
                            e.getMessage());
                }
            }
        }
        return counts;
    }

    private static void save()
    {
        try
        {
            File file = stateFile();
            File parent = file.getParentFile();
            if (parent != null && parent.isDirectory() == false)
            {
                parent.mkdirs();
            }

            JsonObject root = new JsonObject();
            JsonObject sections = new JsonObject();
            for (Map.Entry<String, String> entry : SECTION_FINGERPRINTS.entrySet())
            {
                sections.addProperty(entry.getKey(), entry.getValue());
            }
            root.add("sectionFingerprints", sections);

            JsonObject breakdowns = new JsonObject();
            for (Map.Entry<String, Map<String, Long>> entry : CURRENT_WORLD_BREAKDOWNS.entrySet())
            {
                JsonObject counts = new JsonObject();
                for (Map.Entry<String, Long> count : entry.getValue().entrySet())
                {
                    counts.addProperty(count.getKey(), count.getValue());
                }
                breakdowns.add(entry.getKey(), counts);
            }
            root.add("currentWorldBreakdowns", breakdowns);

            JsonObject leaderboards = new JsonObject();
            for (Map.Entry<String, JsonObject> entry : AETERNUM_LEADERBOARDS.entrySet())
            {
                leaderboards.add(entry.getKey(), entry.getValue().deepCopy());
            }
            root.add("aeternumLeaderboards", leaderboards);

            try (FileWriter writer = new FileWriter(file))
            {
                GSON.toJson(root, writer);
            }
        }
        catch (Exception exception)
        {
            MMM.LOGGER.warn("[MMM_SYNC] sync-state-save-failed error={}", exception.getMessage());
        }
    }

    private static File stateFile()
    {
        return new File(FileUtils.getConfigDirectory(), STATE_FILE_NAME);
    }
}
