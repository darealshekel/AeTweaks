package com.mmm.tracker;

import com.mmm.mixin.ClientPlayerInteractionManagerAccessor;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

public final class MiningSpeedTracker
{
    private static final int GRAPH_BUFFER_SIZE = 200;
    private static final float EMA_ALPHA = 0.3f;
    private static final int SESSION_TIMEOUT_TICKS = 180 * 20;

    private static boolean wasMining = false;
    private static BlockPos lastBlockPos = null;
    private static float lastProgress = 0f;

    private static final float[] speedHistory = new float[GRAPH_BUFFER_SIZE];
    private static int historyIndex = 0;
    private static int historyCount = 0;

    private static float emaSpeed = 0f;
    private static boolean hasSessionData = false;
    private static int idleTicks = 0;

    private MiningSpeedTracker()
    {
    }

    public static void tick(MinecraftClient client)
    {
        if (client.interactionManager == null || client.world == null || client.player == null)
        {
            resetBlock();
            tickIdle();
            return;
        }

        ClientPlayerInteractionManagerAccessor accessor = (ClientPlayerInteractionManagerAccessor) client.interactionManager;
        boolean mining = accessor.mmm$isBreakingBlock();
        BlockPos blockPos = accessor.mmm$getCurrentBreakingPos();
        float progress = accessor.mmm$getCurrentBreakingProgress();

        if (!mining || blockPos == null)
        {
            resetBlock();
            tickIdle();
            return;
        }

        hasSessionData = true;
        idleTicks = 0;

        if (!blockPos.equals(lastBlockPos))
        {
            lastProgress = 0f;
            lastBlockPos = blockPos.toImmutable();
        }

        float delta = progress - lastProgress;
        if (delta > 0)
        {
            float rawSpeed = delta * 20f;
            emaSpeed = EMA_ALPHA * rawSpeed + (1f - EMA_ALPHA) * emaSpeed;
        }
        lastProgress = progress;

        pushHistory(emaSpeed * 3600f);
        wasMining = true;
    }

    private static void tickIdle()
    {
        if (!hasSessionData) return;

        emaSpeed = (1f - EMA_ALPHA) * emaSpeed;
        pushHistory(emaSpeed * 3600f);

        idleTicks++;
        if (idleTicks >= SESSION_TIMEOUT_TICKS)
        {
            resetSession();
        }
    }

    private static void pushHistory(float blocksPerHour)
    {
        speedHistory[historyIndex] = blocksPerHour;
        historyIndex = (historyIndex + 1) % GRAPH_BUFFER_SIZE;
        if (historyCount < GRAPH_BUFFER_SIZE)
        {
            historyCount++;
        }
    }

    private static void resetBlock()
    {
        wasMining = false;
        lastBlockPos = null;
        lastProgress = 0f;
    }

    private static void resetSession()
    {
        resetBlock();
        emaSpeed = 0f;
        hasSessionData = false;
        idleTicks = 0;
        historyIndex = 0;
        historyCount = 0;
    }

    public static boolean hasSessionData()
    {
        return hasSessionData;
    }

    public static float[] getSpeedHistory()
    {
        return speedHistory;
    }

    public static int getHistoryIndex()
    {
        return historyIndex;
    }

    public static int getHistoryCount()
    {
        return historyCount;
    }
}
