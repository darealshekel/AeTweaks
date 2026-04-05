package com.miningtrackeraddon.event;

import com.miningtrackeraddon.config.FeatureToggle;
import com.miningtrackeraddon.hud.MiningHudRenderer;

import fi.dy.masa.malilib.interfaces.IRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Fog;
import net.minecraft.client.render.Frustum;
import net.minecraft.item.ItemStack;
import net.minecraft.util.profiler.Profiler;
import org.joml.Matrix4f;

public class RenderHandler implements IRenderer
{
    @Override
    public void onRenderGameOverlayPostAdvanced(DrawContext drawContext, float partialTicks, Profiler profiler, MinecraftClient mc)
    {
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
    public void onRenderWorldLastAdvanced(Matrix4f posMatrix, Matrix4f projMatrix, Frustum frustum, Camera camera, Fog fog, Profiler profiler)
    {
    }
}
