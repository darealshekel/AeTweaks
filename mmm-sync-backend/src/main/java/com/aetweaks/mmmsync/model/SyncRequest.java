package com.aetweaks.mmmsync.model;

public record SyncRequest(
        String batchId,
        String username,
        String uuid,
        String worldId,
        String worldName,
        String serverAddress,
        boolean multiplayer,
        long deltaBlocks,
        long sessionBlocks,
        long sentAt,
        String modId,
        String modName,
        String modVersion,
        String minecraftVersion)
{
    public void validate()
    {
        if (batchId == null || batchId.isBlank()) throw new IllegalArgumentException("Missing batchId");
        if (username == null || username.isBlank()) throw new IllegalArgumentException("Missing username");
        if (uuid == null || uuid.isBlank()) throw new IllegalArgumentException("Missing uuid");
        if (worldId == null || worldId.isBlank()) throw new IllegalArgumentException("Missing worldId");
        if (worldName == null || worldName.isBlank()) throw new IllegalArgumentException("Missing worldName");
        if (deltaBlocks <= 0L) throw new IllegalArgumentException("deltaBlocks must be positive");
    }
}
