package com.miningtrackeraddon.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class PendingSyncStore
{
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private final Path path;

    PendingSyncStore(Path path)
    {
        this.path = path;
    }

    StoredState load() throws Exception
    {
        if (Files.exists(this.path) == false)
        {
            return new StoredState(List.of(), 0L);
        }

        String json = Files.readString(this.path);
        if (json == null || json.isBlank())
        {
            return new StoredState(List.of(), 0L);
        }

        StoredState state = GSON.fromJson(json, StoredState.class);
        if (state == null)
        {
            return new StoredState(List.of(), 0L);
        }

        List<QueuedSyncItem> validItems = new ArrayList<>();
        if (state.items != null)
        {
            for (QueuedSyncItem item : state.items)
            {
                if (item != null && item.isValid())
                {
                    validItems.add(item.copy());
                }
            }
        }

        validItems.sort(Comparator
                .comparingLong((QueuedSyncItem item) -> item.nextRetryAtMs)
                .thenComparingLong(item -> item.createdAtMs));

        return new StoredState(validItems, Math.max(0L, state.lastSuccessfulSyncAtMs));
    }

    void save(List<QueuedSyncItem> items, long lastSuccessfulSyncAtMs) throws Exception
    {
        Path parent = this.path.getParent();
        if (parent != null)
        {
            Files.createDirectories(parent);
        }

        StoredState state = new StoredState(copyItems(items), Math.max(0L, lastSuccessfulSyncAtMs));
        Path tempPath = this.path.resolveSibling(this.path.getFileName() + ".tmp");
        Files.writeString(tempPath, GSON.toJson(state));

        try
        {
            Files.move(tempPath, this.path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (Exception ignored)
        {
            Files.move(tempPath, this.path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private List<QueuedSyncItem> copyItems(List<QueuedSyncItem> items)
    {
        List<QueuedSyncItem> copy = new ArrayList<>();
        for (QueuedSyncItem item : items)
        {
            copy.add(item.copy());
        }

        copy.sort(Comparator
                .comparingLong((QueuedSyncItem item) -> item.nextRetryAtMs)
                .thenComparingLong(item -> item.createdAtMs));

        return copy;
    }

    static final class StoredState
    {
        List<QueuedSyncItem> items;
        long lastSuccessfulSyncAtMs;

        StoredState(List<QueuedSyncItem> items, long lastSuccessfulSyncAtMs)
        {
            this.items = items;
            this.lastSuccessfulSyncAtMs = lastSuccessfulSyncAtMs;
        }
    }
}