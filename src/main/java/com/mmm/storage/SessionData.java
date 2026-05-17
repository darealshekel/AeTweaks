package com.mmm.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import com.mmm.MMM;
import com.mmm.util.BlockBreakdownCatalog;

public class SessionData
{
    public static final int MAX_BLOCKS_PER_HOUR = 72_000;
    private static final long RATE_BUCKET_DURATION_MS = 60_000L;

    public final long startTimeMs;
    public long endTimeMs;
    public long totalBlocks;
    public long bestStreakSeconds;
    public int peakBlocksPerHour;
    public Map<String, Long> blockBreakdown = new LinkedHashMap<>();
    public List<Integer> miningRateBuckets = new ArrayList<>();

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
        return clampBlocksPerHour(Math.round(this.totalBlocks / hours));
    }

    public int getPeakBlocksPerHour()
    {
        int bucketPeak = this.getPeakBucketBlocksPerHour();
        return bucketPeak > 0 || this.miningRateBuckets.isEmpty() == false
                ? bucketPeak
                : clampBlocksPerHour(this.peakBlocksPerHour);
    }

    public void updatePeakBlocksPerHour(long value)
    {
        this.peakBlocksPerHour = Math.max(this.getPeakBlocksPerHour(), clampBlocksPerHour(value));
    }

    public static int clampBlocksPerHour(long value)
    {
        return (int) Math.max(0L, Math.min(MAX_BLOCKS_PER_HOUR, value));
    }

    public int getBucketBlocksPerHour(int bucketBlocks)
    {
        return clampBlocksPerHour(Math.max(0L, (long) bucketBlocks) * 60L);
    }

    public void recordMineEvent(long activeElapsedMs)
    {
        recordMinedAmount(activeElapsedMs, 1L);
    }

    public void recordMinedAmount(long activeElapsedMs, long amount)
    {
        if (amount <= 0L)
        {
            return;
        }

        int bucketIndex = (int) Math.max(0L, activeElapsedMs / RATE_BUCKET_DURATION_MS);
        ensureBucketCapacity(bucketIndex);
        long nextValue = (long) this.miningRateBuckets.get(bucketIndex) + amount;
        int bucketBlocks = (int) Math.min(Integer.MAX_VALUE, nextValue);
        this.miningRateBuckets.set(bucketIndex, bucketBlocks);
        updatePeakBlocksPerHour(this.getBucketBlocksPerHour(bucketBlocks));
    }

    public void recordMinedAmountOverInterval(long startActiveElapsedMs, long endActiveElapsedMs, long amount)
    {
        if (amount <= 0L)
        {
            return;
        }

        long startMs = Math.max(0L, startActiveElapsedMs);
        long endMs = Math.max(startMs, endActiveElapsedMs);
        if (endMs <= startMs)
        {
            recordMinedAmount(endMs, amount);
            return;
        }

        long durationMs = endMs - startMs;
        int startBucket = (int) Math.max(0L, startMs / RATE_BUCKET_DURATION_MS);
        int endBucket = (int) Math.max(startBucket, (endMs - 1L) / RATE_BUCKET_DURATION_MS);
        long allocated = 0L;
        long coveredMs = 0L;

        for (int bucket = startBucket; bucket <= endBucket; bucket++)
        {
            long bucketStartMs = (long) bucket * RATE_BUCKET_DURATION_MS;
            long bucketEndMs = bucketStartMs + RATE_BUCKET_DURATION_MS;
            long overlapMs = Math.max(0L, Math.min(endMs, bucketEndMs) - Math.max(startMs, bucketStartMs));
            if (overlapMs <= 0L)
            {
                continue;
            }

            coveredMs += overlapMs;
            long cumulative = bucket == endBucket ? amount : Math.round(amount * (coveredMs / (double) durationMs));
            long bucketAmount = Math.max(0L, cumulative - allocated);
            allocated += bucketAmount;
            recordMinedAmount(bucketStartMs, bucketAmount);
        }
    }

    public String serialise()
    {
        return this.startTimeMs + "," + this.endTimeMs + "," + this.totalBlocks + "," + this.bestStreakSeconds + "," + this.getPeakBlocksPerHour() + "," + this.serialiseBreakdown() + "," + this.serialiseRateBuckets();
    }

    public static SessionData deserialise(String line)
    {
        try
        {
            String[] parts = line.split(",", 7);
            SessionData session = new SessionData(Long.parseLong(parts[0]));
            session.endTimeMs = Long.parseLong(parts[1]);
            session.totalBlocks = Long.parseLong(parts[2]);
            session.bestStreakSeconds = Long.parseLong(parts[3]);
            session.peakBlocksPerHour = clampBlocksPerHour(Long.parseLong(parts[4]));
            if (parts.length >= 6)
            {
                session.blockBreakdown = deserialiseBreakdown(parts[5]);
            }
            if (parts.length >= 7)
            {
                session.miningRateBuckets = deserialiseRateBuckets(parts[6]);
            }
            return session;
        }
        catch (Exception e)
        {
            MMM.LOGGER.warn("[MMM] Failed to parse session history line '{}': {}", line, e.getMessage());
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
                catch (NumberFormatException e)
                {
                    MMM.LOGGER.warn("[MMM] Failed to parse session block breakdown entry '{}': {}", entry, e.getMessage());
                }
            }
        }
        return BlockBreakdownCatalog.sanitize(breakdown);
    }

    private String serialiseRateBuckets()
    {
        if (this.miningRateBuckets.isEmpty())
        {
            return "";
        }

        StringJoiner joiner = new StringJoiner("|");
        for (int value : this.miningRateBuckets)
        {
            joiner.add(Integer.toString(Math.max(0, value)));
        }
        return joiner.toString();
    }

    private static List<Integer> deserialiseRateBuckets(String value)
    {
        List<Integer> buckets = new ArrayList<>();
        if (value == null || value.isBlank())
        {
            return buckets;
        }

        for (String entry : value.split("\\|"))
        {
            try
            {
                buckets.add(Math.max(0, Integer.parseInt(entry)));
            }
            catch (NumberFormatException e)
            {
                MMM.LOGGER.warn("[MMM] Failed to parse session rate bucket entry '{}': {}", entry, e.getMessage());
                buckets.add(0);
            }
        }

        return buckets;
    }

    private void ensureBucketCapacity(int bucketIndex)
    {
        while (this.miningRateBuckets.size() <= bucketIndex)
        {
            this.miningRateBuckets.add(0);
        }
    }

    private int getPeakBucketBlocksPerHour()
    {
        int peak = 0;
        for (int bucketBlocks : this.miningRateBuckets)
        {
            peak = Math.max(peak, this.getBucketBlocksPerHour(bucketBlocks));
        }
        return peak;
    }
}
