package com.miningtrackeraddon.event;

import com.miningtrackeraddon.hud.SummaryScreen;
import com.miningtrackeraddon.storage.SessionData;

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

        com.miningtrackeraddon.tracker.MiningStats.onClientTick();

        SessionData pending = WorldLoadListener.consumePendingSummary();
        if (pending != null && mc.player == null && mc.world == null)
        {
            String worldName = WorldLoadListener.consumePendingSummaryName();
            mc.setScreen(new SummaryScreen(pending, mc.currentScreen, worldName, "Session Summary"));
        }
    }
}
