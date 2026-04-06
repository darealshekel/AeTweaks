package com.aetweaks.mmmsync.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;

public class WorldColumnMap
{
    private final Map<String, String> worldIdColumns;
    private final Map<String, String> worldNameColumns;
    private final Map<String, String> aliases;

    public WorldColumnMap(Map<String, String> worldIdColumns, Map<String, String> worldNameColumns, Map<String, String> aliases)
    {
        this.worldIdColumns = worldIdColumns == null ? Map.of() : worldIdColumns;
        this.worldNameColumns = worldNameColumns == null ? Map.of() : worldNameColumns;
        this.aliases = aliases == null ? Map.of() : aliases;
    }

    public static WorldColumnMap load(Gson gson, Path path) throws IOException
    {
        Files.createDirectories(path.getParent());
        if (!Files.exists(path))
        {
            WorldColumnMap empty = new WorldColumnMap(Map.of(), Map.of(), Map.of());
            Files.writeString(path, gson.toJson(empty));
            return empty;
        }

        String content = Files.readString(path);
        if (content.isBlank())
        {
            return new WorldColumnMap(Map.of(), Map.of(), Map.of());
        }

        return gson.fromJson(content, WorldColumnMap.class);
    }

    public String resolveColumn(String worldId, String worldName, String serverAddress)
    {
        if (worldId != null && this.worldIdColumns.containsKey(worldId))
        {
            return this.worldIdColumns.get(worldId);
        }

        for (String candidate : buildCandidates(worldName, serverAddress))
        {
            String direct = this.worldNameColumns.get(candidate);
            if (direct != null)
            {
                return direct;
            }

            String lowered = candidate.toLowerCase(Locale.ROOT);
            String aliasTarget = this.aliases.get(lowered);
            if (aliasTarget != null)
            {
                String aliasColumn = this.worldNameColumns.get(aliasTarget);
                if (aliasColumn != null)
                {
                    return aliasColumn;
                }
            }

            for (Map.Entry<String, String> entry : this.worldNameColumns.entrySet())
            {
                if (entry.getKey().equalsIgnoreCase(candidate))
                {
                    return entry.getValue();
                }
            }
        }

        String bestFuzzy = resolveFuzzy(worldName, serverAddress);
        if (bestFuzzy != null)
        {
            return bestFuzzy;
        }

        return this.worldIdColumns.get("default");
    }

    private String resolveFuzzy(String worldName, String serverAddress)
    {
        Set<String> needles = new LinkedHashSet<>();
        for (String candidate : buildCandidates(worldName, serverAddress))
        {
            String normalized = normalize(candidate);
            if (!normalized.isBlank())
            {
                needles.add(normalized);
            }
        }

        for (Map.Entry<String, String> entry : this.worldNameColumns.entrySet())
        {
            String normalizedLabel = normalize(entry.getKey());
            for (String needle : needles)
            {
                if (!needle.isBlank() && !normalizedLabel.isBlank() &&
                        (normalizedLabel.contains(needle) || needle.contains(normalizedLabel)))
                {
                    return entry.getValue();
                }
            }
        }

        return null;
    }

    private List<String> buildCandidates(String worldName, String serverAddress)
    {
        List<String> candidates = new ArrayList<>();
        addCandidate(candidates, worldName);
        addCandidate(candidates, serverAddress);

        if (serverAddress != null && !serverAddress.isBlank())
        {
            String host = serverAddress.split(":")[0].trim();
            addCandidate(candidates, host);

            String[] parts = host.split("\\.");
            for (String part : parts)
            {
                addCandidate(candidates, part);
            }

            if (parts.length >= 2)
            {
                addCandidate(candidates, parts[parts.length - 2]);
            }
        }

        return candidates;
    }

    private void addCandidate(List<String> candidates, String value)
    {
        if (value == null)
        {
            return;
        }

        String trimmed = value.trim();
        if (!trimmed.isBlank() && !candidates.contains(trimmed))
        {
            candidates.add(trimmed);
        }
    }

    private String normalize(String value)
    {
        if (value == null)
        {
            return "";
        }

        String normalized = value.toLowerCase(Locale.ROOT)
                .replaceAll(":[0-9]+$", "")
                .replaceAll("^(play|mc|srv|server|mine)[._-]+", "")
                .replaceAll("[^a-z0-9]+", "");

        for (String suffix : List.of("net", "gg", "com", "org"))
        {
            if (normalized.endsWith(suffix) && normalized.length() > suffix.length() + 2)
            {
                normalized = normalized.substring(0, normalized.length() - suffix.length());
                break;
            }
        }

        return normalized;
    }
}
