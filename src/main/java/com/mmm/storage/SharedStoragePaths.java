package com.mmm.storage;

import java.nio.file.Path;
import java.nio.file.Paths;

import com.mmm.Reference;

import fi.dy.masa.malilib.util.FileUtils;

public final class SharedStoragePaths
{
    private static final String WINDOWS_APP_DATA_DIR = "ManualMiningManiacs";
    private static final String UNIX_HOME_DIR = ".manual-mining-maniacs";

    private SharedStoragePaths()
    {
    }

    public static Path root()
    {
        String appData = System.getenv("APPDATA");
        if (appData != null && appData.isBlank() == false)
        {
            return Paths.get(appData).resolve(WINDOWS_APP_DATA_DIR);
        }

        String userHome = System.getProperty("user.home");
        if (userHome != null && userHome.isBlank() == false)
        {
            return Paths.get(userHome).resolve(UNIX_HOME_DIR);
        }

        return Paths.get(FileUtils.getConfigDirectory().getAbsolutePath()).resolve(Reference.STORAGE_ID);
    }

    public static Path sessionsDir()
    {
        return root().resolve("sessions");
    }

    public static Path crossVersionStateFile()
    {
        return root().resolve("cross-version-state.json");
    }
}
