package com.miningtrackeraddon.hud;

import java.util.ArrayList;
import java.util.List;

import com.miningtrackeraddon.config.Configs;
import com.miningtrackeraddon.config.Configs.HudAlignment;
import com.miningtrackeraddon.util.UiFormat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class HudMoveScreen extends Screen
{
    private final Screen parent;
    private boolean dragging;
    private int dragOffsetX;
    private int dragOffsetY;

    public HudMoveScreen(Screen parent)
    {
        super(Text.literal("HUD"));
        this.parent = parent;
    }

    @Override
    protected void init()
    {
        ensureCursorVisible();
        int centerX = this.width / 2;

        this.addDrawableChild(ButtonWidget.builder(Text.literal("-"), button -> adjustScale(-0.05D)).dimensions(centerX - 54, 44, 20, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("+"), button -> adjustScale(0.05D)).dimensions(centerX + 34, 44, 20, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> close()).dimensions(centerX - 40, this.height - 30, 80, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta)
    {
        ensureCursorVisible();
        int centerX = this.width / 2;
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, 16, UiFormat.YELLOW);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Drag the HUD to move it."), centerX, 28, UiFormat.TEXT_MUTED);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Size: " + (int) Math.round(Configs.Generic.HUD_SCALE.getDoubleValue() * 100.0D) + "%"), centerX, 48, UiFormat.TEXT_PRIMARY);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Use +/- or mouse wheel to change size."), centerX, 72, UiFormat.TEXT_MUTED);

        drawPreview(context, Configs.Generic.HUD_X.getIntegerValue(), Configs.Generic.HUD_Y.getIntegerValue(), Configs.Generic.HUD_SCALE.getDoubleValue());
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button)
    {
        if (button != 0)
        {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        int[] bounds = MiningHudRenderer.getBounds(MinecraftClient.getInstance());
        if (mouseX >= bounds[0] && mouseX <= bounds[2] && mouseY >= bounds[1] && mouseY <= bounds[3])
        {
            dragging = true;
            dragOffsetX = (int) mouseX - bounds[0];
            dragOffsetY = (int) mouseY - bounds[1];
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY)
    {
        if (dragging)
        {
            int[] bounds = MiningHudRenderer.getBounds(MinecraftClient.getInstance());
            int width = bounds[2] - bounds[0];
            int height = bounds[3] - bounds[1];
            int actualX = Math.max(0, Math.min(this.width - width, (int) mouseX - dragOffsetX));
            int actualY = Math.max(0, Math.min(this.height - height, (int) mouseY - dragOffsetY));
            int maxX = Math.max(1, this.width - width);
            int maxY = Math.max(1, this.height - height);
            HudAlignment alignment = (HudAlignment) Configs.Generic.HUD_ALIGNMENT.getOptionListValue();

            int storedX = switch (alignment)
            {
                case TOP_RIGHT, BOTTOM_RIGHT -> (int) Math.round((1.0D - (actualX / (double) maxX)) * 820.0D);
                default -> (int) Math.round((actualX / (double) maxX) * 820.0D);
            };
            int storedY = switch (alignment)
            {
                case BOTTOM_LEFT, BOTTOM_RIGHT -> (int) Math.round((1.0D - (actualY / (double) maxY)) * 460.0D);
                default -> (int) Math.round((actualY / (double) maxY) * 460.0D);
            };

            Configs.Generic.HUD_X.setIntegerValue(Math.max(0, Math.min(820, storedX)));
            Configs.Generic.HUD_Y.setIntegerValue(Math.max(0, Math.min(460, storedY)));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button)
    {
        if (button == 0)
        {
            dragging = false;
            Configs.saveToFile();
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount)
    {
        adjustScale(verticalAmount > 0 ? 0.05D : -0.05D);
        return true;
    }

    @Override
    public void close()
    {
        Configs.saveToFile();
        MinecraftClient.getInstance().setScreen(this.parent);
    }

    @Override
    public boolean shouldPause()
    {
        return false;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta)
    {
    }

    private void ensureCursorVisible()
    {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.mouse != null)
        {
            client.mouse.unlockCursor();
        }
    }

    private void adjustScale(double delta)
    {
        Configs.Generic.HUD_SCALE.setDoubleValue(Math.max(0.75D, Math.min(1.75D, Configs.Generic.HUD_SCALE.getDoubleValue() + delta)));
    }

    private void drawPreview(DrawContext context, int x, int y, double scale)
    {
        List<String> lines = new ArrayList<>();
        lines.add("AeTweaks");
        lines.add("Project: Example Project | 12.3K blocks");
        lines.add("Total Mined: 4.8K blocks");
        lines.add("Blocks Per Hour: 3.2K blocks/hour");
        lines.add("Session Time: 01:23:45");

        int width = Math.max(lines.stream().mapToInt(this.textRenderer::getWidth).max().orElse(190), 190);
        int lineHeight = this.textRenderer.fontHeight + 2;
        int padding = 4;
        int totalHeight = lines.size() * lineHeight + 24 + padding * 2;

        context.getMatrices().push();
        context.getMatrices().translate(x, y, 0.0F);
        context.getMatrices().scale((float) scale, (float) scale, 1.0F);
        context.fill(-padding, -padding, width + padding, totalHeight, 0xD0101010);
        context.drawBorder(-padding, -padding, width + padding * 2, totalHeight, 0xFFFFAA00);

        int drawY = 0;
        context.drawText(this.textRenderer, Text.literal(lines.getFirst()), 0, drawY, UiFormat.YELLOW, true);
        drawY += lineHeight;
        for (int i = 1; i < lines.size(); i++)
        {
            context.drawText(this.textRenderer, Text.literal(lines.get(i)), 0, drawY, UiFormat.TEXT_PRIMARY, false);
            drawY += lineHeight;
        }

        context.drawText(this.textRenderer, Text.literal("Daily Goal"), 0, drawY + 2, UiFormat.YELLOW, false);
        context.drawText(this.textRenderer, Text.literal("5.2K/5.2K"), 72, drawY + 2, UiFormat.TEXT_PRIMARY, false);
        context.drawText(this.textRenderer, Text.literal("100%"), width - this.textRenderer.getWidth("100%"), drawY + 2, UiFormat.BLUE, false);
        int barY = drawY + 13;
        context.fill(0, barY, width, barY + 6, 0xFF333333);
        context.fill(0, barY, width, barY + 6, UiFormat.BLUE);
        context.drawBorder(0, barY, width, 6, 0xFF777777);
        context.getMatrices().pop();
    }
}
