package com.mmm.tracker;

import com.mmm.mixin.ClientPlayerInteractionManagerAccessor;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

public final class MiningSpeedTracker
{
    private static final int GRAPH_BUFFER_SIZE = 200;


    private static BlockPos lastBlockPos = null;

    private static final float[] speedHistory = new float[GRAPH_BUFFER_SIZE];
    private static int historyIndex = 0;
    private static int historyCount = 0;

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
            lastBlockPos = blockPos.toImmutable();
        }

        if (!MiningStats.isSessionPaused())
        {
            pushHistory(MiningStats.getDisplayedBlocksPerHour());
        }
    }

    private static void tickIdle()
    {
        if (!hasSessionData) return;

        if (!MiningStats.isSessionPaused())
        {
            pushHistory(MiningStats.getDisplayedBlocksPerHour());
        }

        idleTicks++;
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
        lastBlockPos = null;
    }

    private static void resetSession()
    {
        resetBlock();
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

    public static int getIdleTicks()
    {
        return idleTicks;
    }

    public static boolean isActivelyMining()
    {
        return hasSessionData && idleTicks == 0;
    }
}
