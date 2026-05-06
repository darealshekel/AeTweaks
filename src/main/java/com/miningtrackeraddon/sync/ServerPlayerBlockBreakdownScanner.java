package com.miningtrackeraddon.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miningtrackeraddon.storage.WorldSessionContext;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

public final class ServerPlayerBlockBreakdownScanner
{
    private static final String MINED_CRITERION_PREFIX = "minecraft.mined:";
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private ServerPlayerBlockBreakdownScanner()
    {
    }

    public static JsonObject scan(MinecraftClient client, WorldSessionContext.WorldInfo worldInfo)
    {
        if (client == null || client.world == null || worldInfo == null)
        {
            return null;
        }

        Map<String, Map<String, Long>> byPlayer = new LinkedHashMap<>();
        for (ScoreboardReader.ObjectiveSnapshot snapshot : ScoreboardReader.readObjectives(client))
        {
            String blockId = extractMinedBlockId(snapshot.criterionName());
            if (blockId == null)
            {
                continue;
            }

            for (ScoreboardReader.ScoreboardLine line : snapshot.lines())
            {
                if (isUsername(line.owner()) == false || line.scoreValue() <= 0)
                {
                    continue;
                }

                byPlayer.computeIfAbsent(line.owner(), ignored -> new LinkedHashMap<>())
                        .merge(blockId, (long) line.scoreValue(), Math::max);
            }
        }

        if (byPlayer.isEmpty())
        {
            return null;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("source_key", ScoreboardSourceResolver.sourceKey(worldInfo.displayName(), worldInfo));
        payload.addProperty("source_name", ScoreboardSourceResolver.displayName(worldInfo.displayName(), worldInfo));
        payload.addProperty("captured_at", Instant.ofEpochMilli(System.currentTimeMillis()).toString());
        payload.addProperty("scope", "server_players");
        payload.addProperty("source", "scoreboard_objectives");

        JsonArray players = new JsonArray();
        byPlayer.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .forEach(playerEntry -> {
                    JsonObject player = new JsonObject();
                    player.addProperty("username", playerEntry.getKey());
                    long total = playerEntry.getValue().values().stream().mapToLong(Long::longValue).sum();
                    player.addProperty("total_count", total);

                    JsonArray blocks = new JsonArray();
                    playerEntry.getValue().entrySet().stream()
                            .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()))
                            .forEach(blockEntry -> {
                                JsonObject block = new JsonObject();
                                block.addProperty("block_id", blockEntry.getKey());
                                block.addProperty("count", blockEntry.getValue());
                                blocks.add(block);
                            });
                    player.add("items", blocks);
                    players.add(player);
                });
        payload.add("players", players);
        return payload;
    }

    public static String fingerprint(JsonObject payload)
    {
        if (payload == null)
        {
            return "";
        }

        JsonObject minimal = new JsonObject();
        copyIfPresent(payload, minimal, "source_key");
        copyIfPresent(payload, minimal, "source_name");
        copyIfPresent(payload, minimal, "scope");
        copyIfPresent(payload, minimal, "source");
        if (payload.has("players"))
        {
            minimal.add("players", payload.get("players"));
        }
        return GSON.toJson(minimal);
    }

    private static String extractMinedBlockId(String criterionName)
    {
        if (criterionName == null)
        {
            return null;
        }

        String normalized = criterionName.toLowerCase(Locale.ROOT);
        if (normalized.startsWith(MINED_CRITERION_PREFIX) == false)
        {
            return null;
        }

        String statBlockName = normalized.substring(MINED_CRITERION_PREFIX.length());
        Identifier identifier = resolveStatIdentifier(statBlockName);
        if (identifier == null || Registries.BLOCK.getOptionalValue(identifier).isEmpty())
        {
            return null;
        }
        return identifier.toString();
    }

    private static Identifier resolveStatIdentifier(String statBlockName)
    {
        Identifier direct = Identifier.tryParse(statBlockName);
        if (direct != null && Registries.BLOCK.getOptionalValue(direct).isPresent())
        {
            return direct;
        }

        int namespaceSeparator = statBlockName.indexOf('.');
        if (namespaceSeparator <= 0 || namespaceSeparator >= statBlockName.length() - 1)
        {
            return null;
        }

        String blockId = statBlockName.substring(0, namespaceSeparator) + ":" + statBlockName.substring(namespaceSeparator + 1);
        return Identifier.tryParse(blockId);
    }

    private static boolean isUsername(String owner)
    {
        return owner != null && USERNAME.matcher(owner).matches();
    }

    private static void copyIfPresent(JsonObject from, JsonObject to, String key)
    {
        if (from.has(key))
        {
            to.add(key, from.get(key));
        }
    }
}
