package com.miningtrackeraddon.mmm.render;

import com.miningtrackeraddon.mmm.data.MmmEnvironment;
import com.miningtrackeraddon.util.UiFormat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public final class MmmIconRenderer
{
    private static final Identifier SERVER_ICON = Identifier.of("miningtrackeraddon", "textures/gui/mmm_server.png");

    private MmmIconRenderer()
    {
    }

    public static void drawSkinFace(DrawContext context, MinecraftClient client, int x, int y, int size)
    {
        ClientPlayerEntity player = client.player;
        if (player != null)
        {
            PlayerSkinDrawer.draw(context, player.getSkinTextures(), x, y, size);
            return;
        }

        context.fill(x, y, x + size, y + size, 0xFF2A2A2A);
        context.drawBorder(x, y, size, size, 0xFF7A7A7A);
        context.drawCenteredTextWithShadow(client.textRenderer, Text.literal("?"), x + size / 2, y + size / 2 - 4, UiFormat.TEXT_PRIMARY);
    }

    public static void drawEnvironmentIcon(DrawContext context, MinecraftClient client, MmmEnvironment environment, int x, int y)
    {
        if (environment == MmmEnvironment.SERVER)
        {
            context.drawTexture(RenderLayer::getGuiTextured, SERVER_ICON, x, y, 0.0F, 0.0F, 10, 10, 10, 10);
            return;
        }

        if (environment == MmmEnvironment.SINGLEPLAYER)
        {
            context.drawText(client.textRenderer, Text.literal("\u2665"), x, y + 1, 0xFFFF5555, false);
            return;
        }

        context.fill(x + 2, y + 2, x + 8, y + 8, 0xFFAAAAAA);
    }

    public static void drawRemoteIcon(DrawContext context, String imageUrl, int x, int y, int width, int height)
    {
        Identifier texture = MmmRemoteTextureCache.getOrRequest(imageUrl);
        if (texture != null)
        {
            context.drawTexture(RenderLayer::getGuiTextured, texture, x, y, 0.0F, 0.0F, width, height, width, height);
            return;
        }

        context.fill(x, y, x + width, y + height, 0xFF2A2A2A);
        context.drawBorder(x, y, width, height, 0xFF707070);
    }

    public static void drawFlag(DrawContext context, String imageUrl, int x, int y)
    {
        drawRemoteIcon(context, imageUrl, x, y, 18, 12);
    }

    public static void drawBreakdownType(DrawContext context, MinecraftClient client, boolean singleplayer, int x, int y)
    {
        if (singleplayer)
        {
            context.drawText(client.textRenderer, Text.literal("\u2665"), x, y, 0xFFFF5555, false);
        }
        else
        {
            context.drawTexture(RenderLayer::getGuiTextured, SERVER_ICON, x, y, 0.0F, 0.0F, 10, 10, 10, 10);
        }
    }
}
