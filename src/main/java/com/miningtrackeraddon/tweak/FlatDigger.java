package com.miningtrackeraddon.tweak;

import com.miningtrackeraddon.config.FeatureToggle;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

public final class FlatDigger
{
    private FlatDigger()
    {
    }

    public static boolean shouldBlock(BlockPos pos)
    {
        if (!FeatureToggle.TWEAK_FLAT_DIGGER.getBooleanValue())
        {
            return false;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null)
        {
            return false;
        }

        int playerFeetY = client.player.getBlockPos().getY();
        return pos.getY() < playerFeetY;
    }
}
