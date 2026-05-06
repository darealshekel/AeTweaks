package com.miningtrackeraddon.mixin;

import com.miningtrackeraddon.tracker.BlockBreakdownTracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.StatisticsS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin
{
    @Inject(method = "onStatistics", at = @At("RETURN"))
    private void miningtrackeraddon$captureBlockBreakdown(StatisticsS2CPacket packet, CallbackInfo ci)
    {
        BlockBreakdownTracker.captureVanillaStats(MinecraftClient.getInstance(), System.currentTimeMillis());
    }
}
