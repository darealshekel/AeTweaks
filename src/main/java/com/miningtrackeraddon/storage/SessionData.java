package com.miningtrackeraddon.storage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

public class SessionData
{
    public final long startTimeMs;
    public long endTimeMs;
    public long totalBlocks;
    public long bestStreakSeconds;
    public int peakBlocksPerHour;
    public Map<String, Long> blockBreakdown = new LinkedHashMap<>();

    public SessionData(long startTimeMs)
    {
        this.startTimeMs = startTimeMs;
        this.endTimeMs = startTimeMs;
    }

    public long getDurationMs()
    {
        return Math.max(0L, this.endTimeMs - this.startTimeMs);
    }

    public String getDurationString()
    {
        long elapsed = this.getDurationMs();
        long hours = elapsed / 3_600_000L;
        long minutes = (elapsed % 3_600_000L) / 60_000L;
        long seconds = (elapsed % 60_000L) / 1_000L;
        if (hours > 0) return String.format("%dh %02dm %02ds", hours, minutes, seconds);
        if (minutes > 0) return String.format("%dm %02ds", minutes, seconds);
        return String.format("%ds", seconds);
    }

    public int getAverageBlocksPerHour()
    {
        double hours = this.getDurationMs() / 3_600_000.0;
        if (hours < 0.001D) return 0;
        return (int) (this.totalBlocks / hours);
    }

    public String serialise()
    {
        return this.startTimeMs + "," + this.endTimeMs + "," + this.totalBlocks + "," + this.bestStreakSeconds + "," + this.peakBlocksPerHour + "," + this.serialiseBreakdown();
    }

    public static SessionData deserialise(String line)
    {
        try
        {
            String[] parts = line.split(",", 6);
            SessionData session = new SessionData(Long.parseLong(parts[0]));
            session.endTimeMs = Long.parseLong(parts[1]);
            session.totalBlocks = Long.parseLong(parts[2]);
            session.bestStreakSeconds = Long.parseLong(parts[3]);
            session.peakBlocksPerHour = Integer.parseInt(parts[4]);
            if (parts.length >= 6)
            {
                session.blockBreakdown = deserialiseBreakdown(parts[5]);
            }
            return session;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private String serialiseBreakdown()
    {
        if (this.blockBreakdown.isEmpty())
        {
            return "";
        }

        StringJoiner joiner = new StringJoiner(";");
        for (Map.Entry<String, Long> entry : this.blockBreakdown.entrySet())
        {
            joiner.add(entry.getKey() + "=" + entry.getValue());
        }
        return joiner.toString();
    }

    private static Map<String, Long> deserialiseBreakdown(String value)
    {
        Map<String, Long> breakdown = new LinkedHashMap<>();
        if (value == null || value.isBlank())
        {
            return breakdown;
        }

        for (String entry : value.split(";"))
        {
            String[] kv = entry.split("=", 2);
            if (kv.length == 2)
            {
                try
                {
                    breakdown.put(kv[0], Long.parseLong(kv[1]));
                }
                catch (NumberFormatException ignored)
                {
                }
            }
        }
        return breakdown;
    }
}
