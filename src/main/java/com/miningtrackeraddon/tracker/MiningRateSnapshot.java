package com.miningtrackeraddon.tracker;

public record MiningRateSnapshot(
        long timestampMs,
        int count10s,
        int count30s,
        int count60s,
        int count5m,
        double rate10s,
        double rate30s,
        double rate60s,
        double rate5m,
        double sessionRate,
        double predictedBlocksPerHour,
        double confidence,
        MiningPaceEstimator.PaceState paceState)
{
}
