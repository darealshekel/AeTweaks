package com.miningtrackeraddon.storage;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import fi.dy.masa.malilib.util.FileUtils;

public final class SessionHistory
{
    private static final Path ROOT_DIR = Paths.get(FileUtils.getConfigDirectory().getAbsolutePath()).resolve("miningtrackeraddon").resolve("sessions");
    private static final List<SessionData> HISTORY = new ArrayList<>();
    private static SessionData best = null;
    private static String currentWorldId = "default";

    private SessionHistory()
    {
    }

    public static void loadForWorld(String worldId)
    {
        currentWorldId = worldId == null || worldId.isBlank() ? "default" : worldId;
        HISTORY.clear();
        best = null;

        Path saveFile = getSaveFile();
        if (Files.exists(saveFile) == false)
        {
            return;
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
                    HISTORY.add(session);
                    updateBest(session);
                }
            }
        }
        catch (IOException ignored)
        {
        }
    }

    public static void save(SessionData session)
    {
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
        catch (IOException ignored)
        {
        }
    }

    public static String exportStats()
    {
        StringBuilder builder = new StringBuilder();
        builder.append("=== AeTweaks Stats Export ===\n");
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
        Path exportPath = getWorldDir().resolve("aetweaks-export.txt");
        Files.createDirectories(exportPath.getParent());
        Files.writeString(exportPath, exportStats(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return exportPath;
    }

    public static List<SessionData> getHistory()
    {
        return HISTORY;
    }

    public static SessionData getBestSession()
    {
        return best;
    }

    private static void updateBest(SessionData session)
    {
        if (best == null || session.getAverageBlocksPerHour() > best.getAverageBlocksPerHour())
        {
            best = session;
        }
    }

    private static Path getSaveFile()
    {
        return getWorldDir().resolve("sessions.csv");
    }

    private static Path getWorldDir()
    {
        return ROOT_DIR.resolve(currentWorldId);
    }
}
