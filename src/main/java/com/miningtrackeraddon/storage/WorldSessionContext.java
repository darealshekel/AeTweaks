package com.miningtrackeraddon.storage;

import net.minecraft.client.MinecraftClient;

public final class WorldSessionContext
{
    private static String currentWorldId = "default";
    private static String currentWorldName = "Unknown";

    private WorldSessionContext()
    {
    }

    public static void update(MinecraftClient client)
    {
        WorldInfo info = resolve(client);
        currentWorldId = info.id();
        currentWorldName = info.displayName();
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
            return new WorldInfo(sanitise(address), displayName);
        }

        if (client.getServer() != null)
        {
            String levelName = client.getServer().getSaveProperties().getLevelName();
            return new WorldInfo(sanitise(levelName), levelName);
        }

        return new WorldInfo(currentWorldId, currentWorldName);
    }

    public static String getCurrentWorldId()
    {
        return currentWorldId;
    }

    public static String getCurrentWorldName()
    {
        return currentWorldName;
    }

    private static String sanitise(String value)
    {
        if (value == null || value.isBlank())
        {
            return "unknown";
        }

        return value.toLowerCase().replaceAll("[^a-z0-9._-]+", "_");
    }

    public record WorldInfo(String id, String displayName) {}
}
