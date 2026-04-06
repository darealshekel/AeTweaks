package com.miningtrackeraddon.tweak;

import com.miningtrackeraddon.config.Configs;

import com.mojang.blaze3d.systems.RenderSystem;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.Color4f;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

public final class BlockEspRenderer
{
    private static final float RAINBOW_SATURATION = 0.9F;
    private static final float RAINBOW_BRIGHTNESS = 1.0F;
    private static final float BOX_EXPAND = 0.002F;

    private BlockEspRenderer()
    {
    }

    public static void refreshConfig()
    {
        Configs.Generic.BLOCK_ESP_HEX_COLOR.setValueFromString(Configs.normalizeBlockEspHexColor(Configs.Generic.BLOCK_ESP_HEX_COLOR.getStringValue()));
    }

    public static void render(MinecraftClient client, Matrix4f positionMatrix, Matrix4f projectionMatrix)
    {
        if (client == null || client.world == null || client.player == null)
        {
            return;
        }

        HitResult hitResult = client.crosshairTarget;
        if (!(hitResult instanceof BlockHitResult blockHitResult) || hitResult.getType() != HitResult.Type.BLOCK)
        {
            return;
        }

        BlockPos targetPos = blockHitResult.getBlockPos();
        BlockState state = client.world.getBlockState(targetPos);
        if (state.isAir())
        {
            return;
        }

        Color4f baseColor = getCurrentColor(client);
        float opacity = Configs.getBlockEspOpacity();
        if (opacity <= 0.0F)
        {
            return;
        }
        Camera camera = client.gameRenderer.getCamera();
        Vec3d cameraPos = camera.getPos();
        float minX = (float) (targetPos.getX() - cameraPos.x - BOX_EXPAND);
        float minY = (float) (targetPos.getY() - cameraPos.y - BOX_EXPAND);
        float minZ = (float) (targetPos.getZ() - cameraPos.z - BOX_EXPAND);
        float maxX = (float) (targetPos.getX() + 1.0D - cameraPos.x + BOX_EXPAND);
        float maxY = (float) (targetPos.getY() + 1.0D - cameraPos.y + BOX_EXPAND);
        float maxZ = (float) (targetPos.getZ() + 1.0D - cameraPos.z + BOX_EXPAND);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);

        if (!Configs.isBlockEspOutlineOnly())
        {
            drawFill(minX, minY, minZ, maxX, maxY, maxZ, Color4f.fromColor(baseColor, opacity));
        }

        if (!Configs.isBlockEspOutlineOnly())
        {
            drawOutline(targetPos, cameraPos, Color4f.fromColor(baseColor, opacity));
        }

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void drawFill(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, Color4f color)
    {
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);
        RenderUtils.drawBoxAllSidesBatchedQuads(minX, minY, minZ, maxX, maxY, maxZ, color, buffer);
        BufferRenderer.drawWithGlobalProgram(buffer.endNullable());
    }

    private static void drawOutline(BlockPos targetPos, Vec3d cameraPos, Color4f color)
    {
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
        RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.lineWidth(2.0F);
        RenderUtils.drawBlockBoundingBoxOutlinesBatchedLines(targetPos, cameraPos, color, BOX_EXPAND, buffer);
        BufferRenderer.drawWithGlobalProgram(buffer.endNullable());
        RenderSystem.lineWidth(1.0F);
    }

    private static Color4f getCurrentColor(MinecraftClient client)
    {
        if (Configs.isBlockEspRainbow())
        {
            float cycleLengthMs = Math.max(250.0F, 5000.0F / Math.max(0.1F, Configs.getBlockEspRainbowSpeed()));
            float hue = (System.currentTimeMillis() % (long) cycleLengthMs) / cycleLengthMs;
            int rgb = java.awt.Color.HSBtoRGB(hue - (float) Math.floor(hue), RAINBOW_SATURATION, RAINBOW_BRIGHTNESS);
            return Color4f.fromColor(rgb | 0xFF000000);
        }

        String hex = Configs.normalizeBlockEspHexColor(Configs.Generic.BLOCK_ESP_HEX_COLOR.getStringValue());
        long parsed = Long.parseLong(hex.substring(1), 16);
        if (hex.length() == 9)
        {
            return Color4f.fromColor((int) parsed);
        }

        return Color4f.fromColor((int) (0xFF000000L | parsed));
    }

    public static boolean shouldReplaceVanillaOutline(MinecraftClient client)
    {
        return client != null
                && client.player != null
                && client.world != null
                && Configs.isBlockEspOutlineOnly()
                && Configs.getBlockEspOpacity() > 0.0F;
    }

    public static int getCurrentOutlineColor(MinecraftClient client)
    {
        Color4f color = Color4f.fromColor(getCurrentColor(client), Configs.getBlockEspOpacity());
        int alpha = Math.max(0, Math.min(255, Math.round(color.a * 255.0F)));
        int red = Math.max(0, Math.min(255, Math.round(color.r * 255.0F)));
        int green = Math.max(0, Math.min(255, Math.round(color.g * 255.0F)));
        int blue = Math.max(0, Math.min(255, Math.round(color.b * 255.0F)));
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }
}
