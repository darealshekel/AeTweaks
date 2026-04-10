package com.miningtrackeraddon.sync;

final class SyncRetryPolicy
{
    private static final long BASE_DELAY_MS = 5_000L;
    private static final long MAX_DELAY_MS = 10L * 60L * 1_000L;

    private SyncRetryPolicy()
    {
    }

    static long computeDelayMs(int retryCount)
    {
        int exponent = Math.max(0, Math.min(retryCount - 1, 8));
        long delay = BASE_DELAY_MS * (1L << exponent);
        return Math.min(MAX_DELAY_MS, delay);
    }
}
