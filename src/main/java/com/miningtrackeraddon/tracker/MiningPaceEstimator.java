package com.miningtrackeraddon.tracker;

import java.util.ArrayDeque;
import java.util.Deque;

public final class MiningPaceEstimator
{
    private static final long TEN_SECONDS_MS = 10_000L;
    private static final long THIRTY_SECONDS_MS = 30_000L;
    private static final long ONE_MINUTE_MS = 60_000L;
    private static final long FIVE_MINUTES_MS = 300_000L;
    private static final long UPDATE_INTERVAL_MS = 500L;
    private static final long IDLE_DELAY_MS = 5_000L;
    private static final double MAX_REASONABLE_BLOCKS_PER_HOUR = 72_000D;
    private static final double RECENT_SENSITIVITY = 0.68D;
    private static final double SMOOTHING_STRENGTH = 0.62D;
    private static final boolean USE_SESSION_FALLBACK = true;

    private final Deque<Long> events = new ArrayDeque<>();
    private MiningRateSnapshot snapshot = new MiningRateSnapshot(0L, 0, 0, 0, 0, 0D, 0D, 0D, 0D, 0D, 0D, 0D, PaceState.CALCULATING);
    private long sessionStartMs;
    private long lastMineMs;
    private long lastUpdateMs;
    private double emaBlocksPerHour;

    public void reset(long sessionStartMs)
    {
        this.events.clear();
        this.sessionStartMs = sessionStartMs;
        this.lastMineMs = 0L;
        this.lastUpdateMs = 0L;
        this.emaBlocksPerHour = 0D;
        this.snapshot = new MiningRateSnapshot(sessionStartMs, 0, 0, 0, 0, 0D, 0D, 0D, 0D, 0D, 0D, 0D, PaceState.CALCULATING);
    }

    public void recordBlock(long timestampMs)
    {
        this.events.addLast(timestampMs);
        this.lastMineMs = timestampMs;
        prune(timestampMs);
    }

    public MiningRateSnapshot update(long now, long sessionBlocks, long sessionStartMs, boolean force)
    {
        if (this.sessionStartMs != sessionStartMs)
        {
            reset(sessionStartMs);
        }

        if (!force && now - this.lastUpdateMs < UPDATE_INTERVAL_MS)
        {
            return this.snapshot;
        }

        this.lastUpdateMs = now;
        prune(now);

        int count10 = 0;
        int count30 = 0;
        int count60 = 0;
        int count5m = 0;

        for (Long timestamp : this.events)
        {
            long age = now - timestamp;
            if (age <= FIVE_MINUTES_MS)
            {
                count5m++;
            }
            if (age <= ONE_MINUTE_MS)
            {
                count60++;
            }
            if (age <= THIRTY_SECONDS_MS)
            {
                count30++;
            }
            if (age <= TEN_SECONDS_MS)
            {
                count10++;
            }
        }

        long sessionDurationMs = Math.max(1_000L, now - sessionStartMs);
        double rate10 = rateForWindow(count10, Math.min(TEN_SECONDS_MS, sessionDurationMs));
        double rate30 = rateForWindow(count30, Math.min(THIRTY_SECONDS_MS, sessionDurationMs));
        double rate60 = rateForWindow(count60, Math.min(ONE_MINUTE_MS, sessionDurationMs));
        double rate5m = rateForWindow(count5m, Math.min(FIVE_MINUTES_MS, sessionDurationMs));
        double sessionRate = rateForWindow(sessionBlocks, sessionDurationMs);

        long idleMs = this.lastMineMs <= 0L ? sessionDurationMs : Math.max(0L, now - this.lastMineMs);
        long idleDelayMs = IDLE_DELAY_MS;
        PaceState paceState = MiningTrendAnalyzer.getState(rate10, rate30, rate60, idleMs, idleDelayMs);

        double recentSensitivity = clamp(RECENT_SENSITIVITY, 0.1D, 1.0D);
        double[] weights = buildWeights(paceState, recentSensitivity);
        double predictedBase = rate10 * weights[0] + rate30 * weights[1] + rate60 * weights[2] + rate5m * weights[3] + sessionRate * weights[4];

        if (paceState == PaceState.RAMPING_UP)
        {
            predictedBase *= 1.04D;
        }
        else if (paceState == PaceState.SLOWING)
        {
            predictedBase *= 0.93D;
        }

        if (paceState == PaceState.PAUSED)
        {
            double idleDecay = Math.max(0.15D, 1.0D - Math.max(0L, idleMs - idleDelayMs) / 20_000.0D);
            predictedBase = Math.max(sessionRate * 0.40D, predictedBase * idleDecay);
        }

        if (USE_SESSION_FALLBACK && predictedBase <= 0D)
        {
            predictedBase = sessionRate;
        }

        if (predictedBase > 0D && this.emaBlocksPerHour <= 0D)
        {
            this.emaBlocksPerHour = predictedBase;
        }
        else if (predictedBase > 0D)
        {
            double smoothingStrength = clamp(SMOOTHING_STRENGTH, 0.1D, 0.95D);
            double alpha = clamp(0.95D - smoothingStrength, 0.08D, 0.65D);
            this.emaBlocksPerHour = this.emaBlocksPerHour + alpha * (predictedBase - this.emaBlocksPerHour);
        }
        else
        {
            this.emaBlocksPerHour *= 0.98D;
        }

        double confidence = computeConfidence(count10, count30, count60, count5m, sessionBlocks, idleMs, idleDelayMs);
        if (confidence < 0.15D && sessionRate > 0D && USE_SESSION_FALLBACK)
        {
            this.emaBlocksPerHour = Math.max(this.emaBlocksPerHour, sessionRate * 0.75D);
        }

        double predicted = clamp(this.emaBlocksPerHour, 0D, MAX_REASONABLE_BLOCKS_PER_HOUR);
        this.snapshot = new MiningRateSnapshot(now, count10, count30, count60, count5m, rate10, rate30, rate60, rate5m, sessionRate, predicted, confidence, paceState);
        return this.snapshot;
    }

    public MiningRateSnapshot getSnapshot()
    {
        return this.snapshot;
    }

    private void prune(long now)
    {
        long cutoff = now - FIVE_MINUTES_MS;
        while (!this.events.isEmpty() && this.events.peekFirst() < cutoff)
        {
            this.events.pollFirst();
        }
    }

    private double rateForWindow(long count, long windowMs)
    {
        if (count <= 0L || windowMs <= 0L)
        {
            return 0D;
        }
        return count * 3_600_000.0D / windowMs;
    }

    private double[] buildWeights(PaceState paceState, double sensitivity)
    {
        double shortWeight = 0.35D + sensitivity * 0.10D;
        double mediumWeight = 0.28D;
        double oneMinuteWeight = 0.20D;
        double longWeight = 0.10D;
        double sessionWeight = 0.07D;

        if (paceState == PaceState.SLOWING)
        {
            shortWeight -= 0.08D;
            longWeight += 0.05D;
            sessionWeight += 0.03D;
        }
        else if (paceState == PaceState.RAMPING_UP)
        {
            shortWeight += 0.06D;
            mediumWeight += 0.03D;
            sessionWeight -= 0.04D;
        }
        else if (paceState == PaceState.PAUSED)
        {
            shortWeight = 0.10D;
            mediumWeight = 0.18D;
            oneMinuteWeight = 0.22D;
            longWeight = 0.22D;
            sessionWeight = 0.28D;
        }

        double sum = shortWeight + mediumWeight + oneMinuteWeight + longWeight + sessionWeight;
        return new double[] {
                shortWeight / sum,
                mediumWeight / sum,
                oneMinuteWeight / sum,
                longWeight / sum,
                sessionWeight / sum
        };
    }

    private double computeConfidence(int count10, int count30, int count60, int count5m, long sessionBlocks, long idleMs, long idleDelayMs)
    {
        double sampleConfidence = Math.min(1.0D,
                count10 / 18.0D * 0.25D
                        + count30 / 45.0D * 0.25D
                        + count60 / 90.0D * 0.25D
                        + count5m / 300.0D * 0.15D
                        + Math.min(sessionBlocks, 600L) / 600.0D * 0.10D);

        if (idleMs > idleDelayMs)
        {
            sampleConfidence *= Math.max(0.20D, 1.0D - (idleMs - idleDelayMs) / 25_000.0D);
        }

        return clamp(sampleConfidence, 0D, 1.0D);
    }

    private double clamp(double value, double min, double max)
    {
        return Math.max(min, Math.min(max, value));
    }

    public enum PaceState
    {
        CALCULATING,
        RAMPING_UP,
        STABLE,
        SLOWING,
        PAUSED
    }
}
