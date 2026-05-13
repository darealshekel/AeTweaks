package com.mmm.tracker;

import com.mmm.storage.WorldSessionContext;
import com.mmm.util.BlockBreakdownCatalog;
import com.mmm.util.MmmDebugLogger;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.stat.Stats;

public final class BlockBreakdownTracker
{
    private static final long REQUEST_DELAY_MS = 1_000L;
    private static final long STATS_DEBUG_LOG_INTERVAL_MS = 30_000L;
    private static boolean statsRequestPending;
    private static long nextStatsRequestAtMs;
    private static String pendingWorldId = "";

    private BlockBreakdownTracker()
    {
    }

    public static void requestStatsOnWorldJoin()
    {
        statsRequestPending = true;
        nextStatsRequestAtMs = System.currentTimeMillis() + REQUEST_DELAY_MS;
        pendingWorldId = WorldSessionContext.getCurrentWorldId();
    }

    public static void onClientTick(MinecraftClient client, long now)
    {
        if (statsRequestPending == false || now < nextStatsRequestAtMs)
        {
            return;
        }

        if (client == null || client.world == null || client.player == null || client.getNetworkHandler() == null)
        {
            nextStatsRequestAtMs = now + 500L;
            return;
        }

        if (pendingWorldId.isBlank() == false && pendingWorldId.equals(WorldSessionContext.getCurrentWorldId()) == false)
        {
            statsRequestPending = false;
            return;
        }

        client.getNetworkHandler().sendPacket(new ClientStatusC2SPacket(ClientStatusC2SPacket.Mode.REQUEST_STATS));
        statsRequestPending = false;

        MmmDebugLogger.info(
                "requested-vanilla-mined-stats",
                STATS_DEBUG_LOG_INTERVAL_MS,
                "[MMM_DEBUG] requested-vanilla-mined-stats worldId={}",
                WorldSessionContext.getCurrentWorldId());
    }

    public static void captureVanillaStats(MinecraftClient client, long now)
    {
        if (client == null || client.player == null)
        {
            return;
        }

        Map<String, Long> minedBlocks = new LinkedHashMap<>();
        for (Block block : Registries.BLOCK)
        {
            int count = client.player.getStatHandler().getStat(Stats.MINED, block);
            if (count <= 0)
            {
                continue;
            }

            String blockId = Registries.BLOCK.getId(block).toString();
            if (BlockBreakdownCatalog.isValid(blockId))
            {
                minedBlocks.put(blockId, (long) count);
            }
        }

        if (minedBlocks.isEmpty())
        {
            return;
        }

        MiningStats.applyMinecraftStatsBlockBreakdown(minedBlocks, now);

        long total = minedBlocks.values().stream().mapToLong(Long::longValue).sum();
        MmmDebugLogger.info(
                "captured-vanilla-mined-stats",
                STATS_DEBUG_LOG_INTERVAL_MS,
                "[MMM_DEBUG] captured-vanilla-mined-stats worldId={} blockTypes={} total={}",
                WorldSessionContext.getCurrentWorldId(),
                minedBlocks.size(),
                total);
    }
}
