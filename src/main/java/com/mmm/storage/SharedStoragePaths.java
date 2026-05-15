package com.mmm.storage;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

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

    public static Set<Path> legacyConfigDirs()
    {
        Set<Path> dirs = new LinkedHashSet<>();
        Path currentConfigDir = FileUtils.getConfigDirectory().toPath().toAbsolutePath().normalize();
        addIfDirectory(dirs, currentConfigDir);
        addSiblingInstanceConfigDirs(dirs, currentConfigDir);

        String appData = System.getenv("APPDATA");
        if (appData != null && appData.isBlank() == false)
        {
            Path appDataPath = Paths.get(appData);
            addLauncherInstanceConfigDirs(dirs, appDataPath.resolve("PrismLauncher").resolve("instances"));
            addLauncherInstanceConfigDirs(dirs, appDataPath.resolve("PolyMC").resolve("instances"));
            addLauncherInstanceConfigDirs(dirs, appDataPath.resolve("MultiMC").resolve("instances"));
        }

        String userHome = System.getProperty("user.home");
        if (userHome != null && userHome.isBlank() == false)
        {
            addIfDirectory(dirs, Paths.get(userHome).resolve(".minecraft").resolve("config"));
        }

        return dirs;
    }

    private static void addSiblingInstanceConfigDirs(Set<Path> dirs, Path currentConfigDir)
    {
        for (Path cursor = currentConfigDir; cursor != null; cursor = cursor.getParent())
        {
            Path fileName = cursor.getFileName();
            if (fileName != null && "instances".equalsIgnoreCase(fileName.toString()))
            {
                addLauncherInstanceConfigDirs(dirs, cursor);
                return;
            }
        }
    }

    private static void addLauncherInstanceConfigDirs(Set<Path> dirs, Path instancesDir)
    {
        if (Files.isDirectory(instancesDir) == false)
        {
            return;
        }

        try (var paths = Files.list(instancesDir))
        {
            paths.filter(Files::isDirectory)
                    .map(path -> path.resolve("minecraft").resolve("config"))
                    .forEach(path -> addIfDirectory(dirs, path));
        }
        catch (IOException ignored)
        {
        }
    }

    private static void addIfDirectory(Set<Path> dirs, Path path)
    {
        if (path != null && Files.isDirectory(path))
        {
            dirs.add(path.toAbsolutePath().normalize());
        }
    }
}
