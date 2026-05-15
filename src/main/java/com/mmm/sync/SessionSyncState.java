package com.mmm.sync;

import com.mmm.MMM;
import com.mmm.storage.SharedStoragePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.Set;

final class SessionSyncState
{
    private static final Path SYNCED_SESSIONS_FILE = SharedStoragePaths.root().resolve("synced-session-keys.txt");
    private static final Set<String> SYNCED_SESSION_KEYS = new LinkedHashSet<>();
    private static boolean loaded;

    private SessionSyncState()
    {
    }

    static synchronized boolean isSynced(String sessionKey)
    {
        loadIfNeeded();
        return normalizeSessionKey(sessionKey).isBlank() == false
                && SYNCED_SESSION_KEYS.contains(normalizeSessionKey(sessionKey));
    }

    static synchronized void markSynced(String sessionKey)
    {
        String normalized = normalizeSessionKey(sessionKey);
        if (normalized.isBlank())
        {
            return;
        }

        loadIfNeeded();
        if (SYNCED_SESSION_KEYS.add(normalized))
        {
            persist();
        }
    }

    private static void loadIfNeeded()
    {
        if (loaded)
        {
            return;
        }
        loaded = true;

        if (Files.exists(SYNCED_SESSIONS_FILE) == false)
        {
            return;
        }

        try
        {
            for (String line : Files.readAllLines(SYNCED_SESSIONS_FILE))
            {
                String normalized = normalizeSessionKey(line);
                if (normalized.isBlank() == false)
                {
                    SYNCED_SESSION_KEYS.add(normalized);
                }
            }
        }
        catch (IOException e)
        {
            MMM.LOGGER.warn("[MMM] Failed to load synced session keys from {}: {}", SYNCED_SESSIONS_FILE, e.getMessage());
        }
    }

    private static void persist()
    {
        try
        {
            Files.createDirectories(SYNCED_SESSIONS_FILE.getParent());
            Files.write(
                    SYNCED_SESSIONS_FILE,
                    SYNCED_SESSION_KEYS,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        }
        catch (IOException e)
        {
            MMM.LOGGER.warn("[MMM] Failed to save synced session keys to {}: {}", SYNCED_SESSIONS_FILE, e.getMessage());
        }
    }

    private static String normalizeSessionKey(String sessionKey)
    {
        return sessionKey == null ? "" : sessionKey.trim();
    }
}
