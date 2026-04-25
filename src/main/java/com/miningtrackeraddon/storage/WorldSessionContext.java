package com.miningtrackeraddon.storage;

import com.miningtrackeraddon.sync.ScoreboardSourceResolver;
import com.miningtrackeraddon.MiningTrackerAddon;
import com.miningtrackeraddon.config.Configs;
import java.nio.file.Path;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.WorldSavePath;

public final class WorldSessionContext
{
    private static String currentWorldId = "default";
    private static String currentWorldName = "Unknown";
    private static String currentWorldKind = "unknown";
    private static String currentWorldHost = "";
    private static String lastDebugFingerprint = "";

    private WorldSessionContext()
    {
    }

    public static void update(MinecraftClient client)
    {
        WorldInfo info = resolve(client);
        maybeDebugResolvedWorld(info);
        currentWorldId = info.id();
        currentWorldName = info.displayName();
        currentWorldKind = info.kind();
        currentWorldHost = info.host();
    }

    public static WorldInfo resolve(MinecraftClient client)
    {
        if (client.getCurrentServerEntry() != null)
        {
            String address = client.getCurrentServerEntry().address;
            String displayName = client.getCurrentServerEntry().name;
            if (displayName == null || displayName.isBlank())
            {
                displayName = "Multiplayer Server";
            }

            String host = address == null ? "" : address.trim();
            // World ID stays IP-based for stable local stat tracking.
            // Display name is always the player's custom server-list name — never the raw IP.
            String resolvedId = sanitise(host);
            return new WorldInfo(resolvedId, displayName.trim(), "multiplayer", host);
        }

        if (client.getServer() != null)
        {
            String levelName = client.getServer().getSaveProperties().getLevelName();
            String worldKey = resolveSingleplayerWorldKey(client, levelName);
            return new WorldInfo(worldKey, levelName, "singleplayer", "");
        }

        return new WorldInfo(currentWorldId, currentWorldName, currentWorldKind, currentWorldHost);
    }

    public static String getCurrentWorldId()
    {
        return currentWorldId;
    }

    public static String getCurrentWorldName()
    {
        return currentWorldName;
    }

    public static WorldInfo getCurrentWorldInfo()
    {
        return new WorldInfo(currentWorldId, currentWorldName, currentWorldKind, currentWorldHost);
    }

    private static String sanitise(String value)
    {
        if (value == null || value.isBlank())
        {
            return "unknown";
        }

        return value.toLowerCase().replaceAll("[^a-z0-9._-]+", "_");
    }

    private static void maybeDebugResolvedWorld(WorldInfo info)
    {
        if (Configs.Generic.WEBSITE_SYNC_DEBUG.getBooleanValue() == false || info == null)
        {
            return;
        }

        String fingerprint = info.id() + "|" + info.displayName() + "|" + info.kind() + "|" + info.host();
        if (fingerprint.equals(lastDebugFingerprint))
        {
            return;
        }

        lastDebugFingerprint = fingerprint;
        MiningTrackerAddon.LOGGER.info(
                "[MMM_DEBUG] world-context-resolved worldId={} displayName={} kind={} host={}",
                info.id(),
                info.displayName(),
                info.kind(),
                info.host()
        );
    }

    private static String resolveSingleplayerWorldKey(MinecraftClient client, String levelName)
    {
        try
        {
            Path savePath = client.getServer().getSavePath(WorldSavePath.ROOT);
            Path folderPath = savePath.getFileName();
            if (folderPath != null)
            {
                String folderName = folderPath.toString();
                if (!folderName.isBlank())
                {
                    return sanitise(folderName);
                }
            }
        }
        catch (Exception ignored)
        {
        }

        return sanitise(levelName);
    }

    public record WorldInfo(String id, String displayName, String kind, String host) {}
}
