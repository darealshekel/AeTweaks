package com.mmm.util;

import com.mmm.MMM;
import com.mmm.config.Configs;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MmmDebugLogger
{
    private static final long GLOBAL_DEBUG_LOG_INTERVAL_MS = 10_000L;
    private static final Map<String, Long> LAST_LOG_MS = new ConcurrentHashMap<>();
    private static volatile long lastAnyLogMs;

    private MmmDebugLogger()
    {
    }

    public static boolean isEnabled()
    {
        return Configs.Generic.WEBSITE_SYNC_DEBUG.getBooleanValue();
    }

    public static synchronized boolean shouldLog(String key, long intervalMs)
    {
        if (isEnabled() == false)
        {
            return false;
        }

        long now = System.currentTimeMillis();
        String safeKey = key == null || key.isBlank() ? "default" : key;
        long safeIntervalMs = Math.max(0L, intervalMs);
        Long previous = LAST_LOG_MS.get(safeKey);
        if (previous != null && now - previous < safeIntervalMs)
        {
            return false;
        }
        if (now - lastAnyLogMs < GLOBAL_DEBUG_LOG_INTERVAL_MS)
        {
            return false;
        }

        LAST_LOG_MS.put(safeKey, now);
        lastAnyLogMs = now;
        return true;
    }

    public static void info(String key, long intervalMs, String message, Object... args)
    {
        if (shouldLog(key, intervalMs))
        {
            MMM.LOGGER.info(message, args);
        }
    }

    public static void debug(String key, long intervalMs, String message, Object... args)
    {
        if (shouldLog(key, intervalMs))
        {
            MMM.LOGGER.debug(message, args);
        }
    }
}
