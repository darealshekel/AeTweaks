package com.miningtrackeraddon.event;

import com.miningtrackeraddon.hud.SummaryScreen;
import com.miningtrackeraddon.mmm.MmmManager;
import com.miningtrackeraddon.storage.SessionData;
import com.miningtrackeraddon.sync.MmmSyncManager;

import fi.dy.masa.malilib.interfaces.IClientTickHandler;
import net.minecraft.client.MinecraftClient;

public class ClientTickHandler implements IClientTickHandler
{
    @Override
    public void onClientTick(MinecraftClient mc)
    {
        if (mc == null)
        {
            return;
        }

        SessionData pending = WorldLoadListener.consumePendingSummary();
        if (pending != null && mc.player == null && mc.world == null)
        {
            String worldName = WorldLoadListener.consumePendingSummaryName();
            mc.setScreen(new SummaryScreen(pending, mc.currentScreen, worldName, "Session Summary"));
        }

        MmmManager.onClientTick(mc);
        MmmSyncManager.onClientTick(mc);
    }
}
