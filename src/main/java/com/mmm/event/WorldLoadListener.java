package com.mmm.event;

import com.mmm.config.FeatureToggle;
import com.mmm.storage.SessionData;
import com.mmm.storage.SessionHistory;
import com.mmm.storage.WorldSessionContext;
import com.mmm.sync.CloudSyncManager;
import com.mmm.sync.DigsSyncManager;
import com.mmm.sync.SyncQueueManager;
import com.mmm.tracker.BlockBreakdownTracker;
import com.mmm.tracker.GoalNotificationManager;
import com.mmm.tracker.MiningStats;
import com.mmm.util.MmmDebugLogger;

import fi.dy.masa.malilib.interfaces.IWorldLoadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.DynamicRegistryManager;

public class WorldLoadListener implements IWorldLoadListener
{
    private static final long WORLD_SWITCH_LOG_INTERVAL_MS = 30_000L;
    private static SessionData pendingSummary;
    private static String pendingSummaryName = "Unknown";

    @Override
    public void onWorldLoadImmutable(DynamicRegistryManager.Immutable immutable)
    {
    }

    @Override
    public void onWorldLoadPre(ClientWorld worldBefore, ClientWorld worldAfter, MinecraftClient mc)
    {
        if (worldBefore != null && worldAfter == null)
        {
            SessionData finished = MiningStats.finaliseSession();
            CloudSyncManager.syncNow("world exit");
            DigsSyncManager.syncNow("world exit");
            SyncQueueManager.forceFlush("world exit");
            if (FeatureToggle.TWEAK_SUMMARY_ON_EXIT.getBooleanValue() && finished.totalBlocks > 0)
            {
                pendingSummary = finished;
                pendingSummaryName = WorldSessionContext.getCurrentWorldName();
            }
            GoalNotificationManager.clear();
        }
    }

    @Override
    public void onWorldLoadPost(ClientWorld worldBefore, ClientWorld worldAfter, MinecraftClient mc)
    {
        if (worldAfter != null)
        {
            String previousWorldId = WorldSessionContext.getCurrentWorldId();
            WorldSessionContext.update(mc);
            String nextWorldId = WorldSessionContext.getCurrentWorldId();
            if (worldBefore == null || previousWorldId.equals(nextWorldId) == false)
            {
                debugWorldSwitch(previousWorldId, nextWorldId);
                DigsSyncManager.resetForWorldChange(nextWorldId);
                SessionHistory.loadForWorld(nextWorldId);
                MiningStats.startWorldSession(nextWorldId);
                BlockBreakdownTracker.requestStatsOnWorldJoin();
            }
            SyncQueueManager.forceFlush("world join");
        }
        else if (worldAfter == null)
        {
            GoalNotificationManager.clear();
            CloudSyncManager.resetForDisconnect();
            DigsSyncManager.resetForDisconnect();
            MiningStats.resetRollingMetrics();
        }
    }

    private void debugWorldSwitch(String previousWorldId, String nextWorldId)
    {
        WorldSessionContext.WorldInfo info = WorldSessionContext.getCurrentWorldInfo();
        MmmDebugLogger.info(
                "world-switch",
                WORLD_SWITCH_LOG_INTERVAL_MS,
                "[MMM_DEBUG] world-switch previousWorldId={} nextWorldId={} displayName={} host={}",
                previousWorldId,
                nextWorldId,
                info.displayName(),
                info.host()
        );
    }

    public static SessionData consumePendingSummary()
    {
        SessionData value = pendingSummary;
        pendingSummary = null;
        return value;
    }

    public static String consumePendingSummaryName()
    {
        String value = pendingSummaryName;
        pendingSummaryName = "Unknown";
        return value;
    }
}
