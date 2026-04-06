package com.miningtrackeraddon.event;

import com.miningtrackeraddon.config.FeatureToggle;
import com.miningtrackeraddon.hud.MiningHudRenderer;
import com.miningtrackeraddon.tweak.BlockEspRenderer;

import fi.dy.masa.malilib.interfaces.IRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import org.joml.Matrix4f;

public class RenderHandler implements IRenderer
{
    @Override
    public void onRenderGameOverlayPost(DrawContext drawContext)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (FeatureToggle.TWEAK_MINING_TRACKER.getBooleanValue())
        {
            MiningHudRenderer.render(drawContext, mc);
        }
    }

    @Override
    public void onRenderTooltipLast(DrawContext drawContext, ItemStack stack, int x, int y)
    {
    }

    @Override
    public void onRenderWorldLast(Matrix4f posMatrix, Matrix4f projMatrix)
    {
        if (FeatureToggle.TWEAK_BLOCK_ESP.getBooleanValue())
        {
            BlockEspRenderer.render(MinecraftClient.getInstance(), posMatrix, projMatrix);
        }
    }
}
