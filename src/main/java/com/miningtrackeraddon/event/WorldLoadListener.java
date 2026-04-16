package com.miningtrackeraddon.event;

import com.miningtrackeraddon.config.FeatureToggle;
import com.miningtrackeraddon.config.Configs;
import com.miningtrackeraddon.storage.SessionData;
import com.miningtrackeraddon.storage.SessionHistory;
import com.miningtrackeraddon.storage.WorldSessionContext;
import com.miningtrackeraddon.sync.CloudSyncManager;
import com.miningtrackeraddon.sync.DigsSyncManager;
import com.miningtrackeraddon.sync.SyncQueueManager;
import com.miningtrackeraddon.tracker.GoalNotificationManager;
import com.miningtrackeraddon.tracker.MiningStats;
import com.miningtrackeraddon.MiningTrackerAddon;

import fi.dy.masa.malilib.interfaces.IWorldLoadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.DynamicRegistryManager;

public class WorldLoadListener implements IWorldLoadListener
{
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
            }
            SyncQueueManager.forceFlush("world join");
        }
        else if (worldAfter == null)
        {
            GoalNotificationManager.clear();
            CloudSyncManager.resetForDisconnect();
            DigsSyncManager.resetForDisconnect();
        }
    }

    private void debugWorldSwitch(String previousWorldId, String nextWorldId)
    {
        if (Configs.Generic.WEBSITE_SYNC_DEBUG.getBooleanValue() == false)
        {
            return;
        }

        WorldSessionContext.WorldInfo info = WorldSessionContext.getCurrentWorldInfo();
        MiningTrackerAddon.LOGGER.info(
                "[AET_DEBUG] world-switch previousWorldId={} nextWorldId={} displayName={} host={}",
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
