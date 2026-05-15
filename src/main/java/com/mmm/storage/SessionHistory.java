package com.mmm.storage;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.mmm.MMM;
import com.mmm.Reference;
import com.mmm.config.Configs;

public final class SessionHistory
{
    private static final long MIN_SESSION_DURATION_MS = 10L * 60L * 1000L;
    private static final long MIN_SESSION_BLOCKS = 1_000L;
    private static final Path ROOT_DIR = SharedStoragePaths.sessionsDir();
    private static final List<SessionData> HISTORY = new ArrayList<>();
    private static SessionData best = null;
    private static String currentWorldId = "default";
    private static boolean legacyMigrationAttempted = false;

    private SessionHistory()
    {
    }

    public static void loadForWorld(String worldId)
    {
        currentWorldId = normalizeWorldId(worldId);
        migrateLegacySessionsIfNeeded();
        HISTORY.clear();
        best = null;

        for (SessionData session : loadSessions(getSaveFile()))
        {
            HISTORY.add(session);
            updateBest(session);
        }
    }

    public static void save(SessionData session)
    {
        if (session == null || session.getDurationMs() < MIN_SESSION_DURATION_MS || session.totalBlocks < MIN_SESSION_BLOCKS)
        {
            return;
        }

        migrateLegacySessionsIfNeeded();
        HISTORY.add(session);
        updateBest(session);

        Path saveFile = getSaveFile();
        try
        {
            Files.createDirectories(saveFile.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(saveFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND))
            {
                writer.write(session.serialise());
                writer.newLine();
            }
        }
        catch (IOException e)
        {
            MMM.LOGGER.warn("[MMM] Failed to save session history to {}: {}", saveFile, e.getMessage());
        }
    }

    public static String exportStats()
    {
        StringBuilder builder = new StringBuilder();
        builder.append("=== MMM Stats Export ===\n");
        builder.append("World: ").append(currentWorldId).append("\n");
        builder.append("Generated: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date())).append("\n\n");
        builder.append("SESSION HISTORY\n---------------\n");

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        for (int i = HISTORY.size() - 1; i >= 0; i--)
        {
            SessionData session = HISTORY.get(i);
            builder.append(String.format("#%-3d %s | %6d blocks/hour avg | %6d blocks | %s | Streak %ds%n",
                    i + 1,
                    dateFormat.format(new Date(session.startTimeMs)),
                    session.getAverageBlocksPerHour(),
                    session.totalBlocks,
                    session.getDurationString(),
                    session.bestStreakSeconds));
        }
        builder.append("\n=== End of Export ===\n");
        return builder.toString();
    }

    public static Path exportToFile() throws IOException
    {
        Path exportPath = getWorldDir().resolve("mmm-export.txt");
        Files.createDirectories(exportPath.getParent());
        Files.writeString(exportPath, exportStats(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return exportPath;
    }

    public static List<SessionData> getHistory()
    {
        return HISTORY;
    }

    public static List<WorldHistory> getWorldHistories()
    {
        migrateLegacySessionsIfNeeded();
        List<String> worldIds = new ArrayList<>();
        if (Files.isDirectory(ROOT_DIR))
        {
            try (var paths = Files.list(ROOT_DIR))
            {
                paths.filter(Files::isDirectory)
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .forEach(path ->
                        {
                            String worldId = path.getFileName() == null ? "" : path.getFileName().toString();
                            if (!worldId.isBlank() && Files.exists(path.resolve("sessions.csv")))
                            {
                                worldIds.add(worldId);
                            }
                        });
            }
            catch (IOException e)
            {
                MMM.LOGGER.warn("[MMM] Failed to list session worlds in {}: {}", ROOT_DIR, e.getMessage());
            }
        }

        if (!worldIds.contains(currentWorldId))
        {
            worldIds.add(0, currentWorldId);
        }

        List<WorldHistory> histories = new ArrayList<>();
        for (String worldId : worldIds)
        {
            List<SessionData> sessions = loadSessions(getSaveFile(worldId));
            if (sessions.isEmpty() && !worldId.equals(currentWorldId))
            {
                continue;
            }

            histories.add(new WorldHistory(worldId, resolveDisplayName(worldId), List.copyOf(sessions), findBest(sessions)));
        }

        return histories;
    }

    public static SessionData getBestSession()
    {
        return best;
    }

    public static String getCurrentWorldId()
    {
        return currentWorldId;
    }

    private static void updateBest(SessionData session)
    {
        if (best == null || session.getAverageBlocksPerHour() > best.getAverageBlocksPerHour())
        {
            best = session;
        }
    }

    private static SessionData findBest(List<SessionData> sessions)
    {
        SessionData bestSession = null;
        for (SessionData session : sessions)
        {
            if (bestSession == null || session.getAverageBlocksPerHour() > bestSession.getAverageBlocksPerHour())
            {
                bestSession = session;
            }
        }
        return bestSession;
    }

    private static List<SessionData> loadSessions(Path saveFile)
    {
        List<SessionData> sessions = new ArrayList<>();
        if (Files.exists(saveFile) == false)
        {
            return sessions;
        }

        try (BufferedReader reader = Files.newBufferedReader(saveFile))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#"))
                {
                    continue;
                }
                SessionData session = SessionData.deserialise(line);
                if (session != null)
                {
                    sessions.add(session);
                }
            }
        }
        catch (IOException e)
        {
            MMM.LOGGER.warn("[MMM] Failed to load session history from {}: {}", saveFile, e.getMessage());
        }
        return sessions;
    }

    private static String resolveDisplayName(String worldId)
    {
        for (Configs.WorldStatsEntry entry : Configs.WORLD_STATS)
        {
            if (worldId.equals(entry.worldId) && entry.displayName != null && !entry.displayName.isBlank())
            {
                return entry.displayName;
            }
        }

        if (worldId.equals(currentWorldId))
        {
            String currentName = WorldSessionContext.getCurrentWorldName();
            if (currentName != null && !currentName.isBlank() && !"Unknown".equalsIgnoreCase(currentName))
            {
                return currentName;
            }
        }

        return worldId;
    }

    private static String normalizeWorldId(String worldId)
    {
        return worldId == null || worldId.isBlank() ? "default" : worldId;
    }

    private static Path getSaveFile()
    {
        return getSaveFile(currentWorldId);
    }

    private static Path getSaveFile(String worldId)
    {
        return getWorldDir(worldId).resolve("sessions.csv");
    }

    private static Path getWorldDir()
    {
        return getWorldDir(currentWorldId);
    }

    private static Path getWorldDir(String worldId)
    {
        return ROOT_DIR.resolve(normalizeWorldId(worldId));
    }

    private static void migrateLegacySessionsIfNeeded()
    {
        if (legacyMigrationAttempted)
        {
            return;
        }
        legacyMigrationAttempted = true;

        for (Path legacyRootDir : findLegacySessionRoots())
        {
            if (legacyRootDir.equals(ROOT_DIR) || Files.isDirectory(legacyRootDir) == false)
            {
                continue;
            }

            try (var paths = Files.list(legacyRootDir))
            {
                paths.filter(Files::isDirectory).forEach(SessionHistory::mergeLegacyWorldSessions);
            }
            catch (IOException e)
            {
                MMM.LOGGER.warn("[MMM] Failed to migrate session history from {} to {}: {}", legacyRootDir, ROOT_DIR, e.getMessage());
            }
        }
    }

    private static Set<Path> findLegacySessionRoots()
    {
        Set<Path> roots = new LinkedHashSet<>();
        for (Path configDir : SharedStoragePaths.legacyConfigDirs())
        {
            roots.add(configDir.resolve(Reference.STORAGE_ID).resolve("sessions").toAbsolutePath().normalize());
            roots.add(configDir.resolve(Reference.LEGACY_STORAGE_ID).resolve("sessions").toAbsolutePath().normalize());
        }
        return roots;
    }

    private static void mergeLegacyWorldSessions(Path legacyWorldDir)
    {
        Path legacyFile = legacyWorldDir.resolve("sessions.csv");
        if (Files.exists(legacyFile) == false)
        {
            return;
        }

        String worldId = legacyWorldDir.getFileName() == null ? "default" : legacyWorldDir.getFileName().toString();
        Path sharedFile = getSaveFile(worldId);

        try
        {
            Files.createDirectories(sharedFile.getParent());
            Set<String> existing = new HashSet<>();
            if (Files.exists(sharedFile))
            {
                existing.addAll(Files.readAllLines(sharedFile));
            }

            try (BufferedWriter writer = Files.newBufferedWriter(sharedFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND))
            {
                for (String line : Files.readAllLines(legacyFile))
                {
                    String trimmed = line == null ? "" : line.trim();
                    if (trimmed.isEmpty() || existing.contains(trimmed))
                    {
                        continue;
                    }
                    writer.write(trimmed);
                    writer.newLine();
                    existing.add(trimmed);
                }
            }
        }
        catch (IOException e)
        {
            MMM.LOGGER.warn("[MMM] Failed to merge legacy session history from {}: {}", legacyFile, e.getMessage());
        }
    }

    public record WorldHistory(String worldId, String displayName, List<SessionData> sessions, SessionData bestSession) {}
}
