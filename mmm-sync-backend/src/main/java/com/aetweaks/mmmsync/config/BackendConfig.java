package com.aetweaks.mmmsync.config;

import java.nio.file.Path;

public record BackendConfig(
        int port,
        String apiKey,
        String sheetId,
        int sheetGid,
        String playerColumn,
        String totalColumn,
        int dataStartRow,
        boolean requireWorldMapping,
        Path serviceAccountFile,
        String serviceAccountJson,
        Path worldMapFile,
        Path processedBatchFile)
{
    public static BackendConfig load()
    {
        Path root = Path.of("").toAbsolutePath();
        return new BackendConfig(
                intEnv("MMM_SYNC_PORT", intEnv("PORT", 8787)),
                env("MMM_SYNC_API_KEY", ""),
                env("MMM_SYNC_SHEET_ID", "1ZYHywH13pjoUGUx-BZU_kncINbcxAnRGNW2MAZliiyA"),
                intEnv("MMM_SYNC_SHEET_GID", 450768443),
                env("MMM_SYNC_PLAYER_COLUMN", "I"),
                env("MMM_SYNC_TOTAL_COLUMN", "J"),
                intEnv("MMM_SYNC_DATA_START_ROW", 9),
                boolEnv("MMM_SYNC_REQUIRE_WORLD_MAPPING", false),
                root.resolve(env("MMM_SYNC_GOOGLE_SERVICE_ACCOUNT_FILE", "config/service-account.json")),
                env("MMM_SYNC_GOOGLE_SERVICE_ACCOUNT_JSON", ""),
                root.resolve(env("MMM_SYNC_WORLD_MAP_FILE", "config/world-map.json")),
                root.resolve(env("MMM_SYNC_PROCESSED_BATCH_FILE", "data/processed-batches.json"))
        );
    }

    private static String env(String key, String fallback)
    {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int intEnv(String key, int fallback)
    {
        String value = System.getenv(key);
        if (value == null || value.isBlank())
        {
            return fallback;
        }

        try
        {
            return Integer.parseInt(value.trim());
        }
        catch (NumberFormatException ignored)
        {
            return fallback;
        }
    }

    private static boolean boolEnv(String key, boolean fallback)
    {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value.trim());
    }
}
