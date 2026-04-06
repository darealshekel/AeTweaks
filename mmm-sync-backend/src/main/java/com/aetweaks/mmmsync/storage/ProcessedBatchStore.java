package com.aetweaks.mmmsync.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class ProcessedBatchStore
{
    private static final int MAX_IDS = 20_000;

    private final Gson gson;
    private final Path path;
    private final LinkedHashSet<String> processedIds = new LinkedHashSet<>();

    public ProcessedBatchStore(Gson gson, Path path) throws IOException
    {
        this.gson = gson;
        this.path = path;
        load();
    }

    public synchronized boolean has(String batchId)
    {
        return this.processedIds.contains(batchId);
    }

    public synchronized void remember(String batchId) throws IOException
    {
        this.processedIds.add(batchId);
        while (this.processedIds.size() > MAX_IDS)
        {
            String first = this.processedIds.iterator().next();
            this.processedIds.remove(first);
        }
        save();
    }

    private void load() throws IOException
    {
        Files.createDirectories(this.path.getParent());
        if (!Files.exists(this.path))
        {
            save();
            return;
        }

        String content = Files.readString(this.path);
        if (content.isBlank())
        {
            return;
        }

        Set<String> ids = this.gson.fromJson(content, new TypeToken<Set<String>>() {}.getType());
        if (ids != null)
        {
            this.processedIds.addAll(ids);
        }
    }

    private void save() throws IOException
    {
        Files.createDirectories(this.path.getParent());
        Files.writeString(this.path, this.gson.toJson(this.processedIds));
    }
}
