package com.miningtrackeraddon.event;

import com.miningtrackeraddon.config.FeatureToggle;
import com.miningtrackeraddon.mmm.MmmManager;
import com.miningtrackeraddon.storage.SessionData;
import com.miningtrackeraddon.storage.SessionHistory;
import com.miningtrackeraddon.storage.WorldSessionContext;
import com.miningtrackeraddon.sync.MmmSyncManager;
import com.miningtrackeraddon.tracker.GoalNotificationManager;
import com.miningtrackeraddon.tracker.MiningStats;

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
            MmmManager.onWorldLeave();
            MmmSyncManager.onWorldLeave();
        }
    }

    @Override
    public void onWorldLoadPost(ClientWorld worldBefore, ClientWorld worldAfter, MinecraftClient mc)
    {
        if (worldAfter != null && worldBefore == null)
        {
            WorldSessionContext.update(mc);
            SessionHistory.loadForWorld(WorldSessionContext.getCurrentWorldId());
            MiningStats.startWorldSession(WorldSessionContext.getCurrentWorldId());
            MmmManager.onWorldJoin(mc);
            MmmSyncManager.onWorldJoin(mc);
        }
        else if (worldAfter == null)
        {
            GoalNotificationManager.clear();
            MmmManager.onWorldLeave();
            MmmSyncManager.onWorldLeave();
        }
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
