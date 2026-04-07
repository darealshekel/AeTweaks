package com.miningtrackeraddon.tracker;

public final class MiningTrendAnalyzer
{
    private MiningTrendAnalyzer()
    {
    }

    public static MiningPaceEstimator.PaceState getState(double shortRate, double mediumRate, double longRate, long idleMs, long idleDelayMs)
    {
        if (idleMs >= idleDelayMs)
        {
            return MiningPaceEstimator.PaceState.PAUSED;
        }

        if (shortRate > mediumRate * 1.12D && mediumRate > longRate * 1.04D)
        {
            return MiningPaceEstimator.PaceState.RAMPING_UP;
        }

        if (shortRate < mediumRate * 0.82D && mediumRate < longRate * 0.94D)
        {
            return MiningPaceEstimator.PaceState.SLOWING;
        }

        return MiningPaceEstimator.PaceState.STABLE;
    }
}
