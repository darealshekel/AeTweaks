package com.mmm.hud;

import java.util.ArrayList;
import java.util.List;

import com.mmm.config.Configs;
import com.mmm.config.Configs.HudAlignment;
import com.mmm.config.FeatureToggle;
import com.mmm.tracker.MiningStats;
import com.mmm.ui.MmmUi;
import com.mmm.util.UiFormat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
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
        ControlLayout layout = this.controlLayout();

        this.addDrawableChild(ButtonWidget.builder(Text.literal("-"), button -> adjustScale(-0.05D)).dimensions(layout.x() + 16, layout.y() + 46, 22, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("+"), button -> adjustScale(0.05D)).dimensions(layout.x() + 42, layout.y() + 46, 22, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> close()).dimensions(layout.x() + layout.width() - 84, layout.y() + 46, 68, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta)
    {
        ensureCursorVisible();
        ControlLayout layout = this.controlLayout();
        context.fill(0, 0, this.width, this.height, 0x33050505);
        MmmUi.card(context, layout.x(), layout.y(), layout.width(), layout.height(), MmmUi.PANEL, MmmUi.BORDER);
        MmmUi.drawSectionHeading(context, this.textRenderer, "MOVE HUD", layout.x() + 12, layout.y() + 12, layout.width() - 24);
        MmmUi.drawTextWithin(context, this.textRenderer, "Drag the HUD preview. Scroll or use +/- for size.", layout.x() + 12, layout.y() + 28, layout.width() - 24, MmmUi.MUTED, false);
        MmmUi.drawTextWithin(context, this.textRenderer, "Size: " + (int) Math.round(Configs.Generic.HUD_SCALE.getDoubleValue() * 100.0D) + "%", layout.x() + 74, layout.y() + 52, 76, MmmUi.ACCENT_BRIGHT, false);

        drawHudBounds(context);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button)
    {
        if (super.mouseClicked(mouseX, mouseY, button))
        {
            return true;
        }

        if (button != 0)
        {
            return false;
        }

        int[] bounds = MiningHudRenderer.getBounds(MinecraftClient.getInstance());
        if (mouseX >= bounds[0] && mouseX <= bounds[2] && mouseY >= bounds[1] && mouseY <= bounds[3])
        {
            dragging = true;
            dragOffsetX = (int) mouseX - bounds[0];
            dragOffsetY = (int) mouseY - bounds[1];
            return true;
        }
        return false;
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

    private void drawHudBounds(DrawContext context)
    {
        int[] bounds = MiningHudRenderer.getBounds(MinecraftClient.getInstance());
        context.drawBorder(bounds[0] - 2, bounds[1] - 2, bounds[2] - bounds[0] + 4, bounds[3] - bounds[1] + 4, MmmUi.ACCENT);
    }

    private void drawPreview(DrawContext context, int x, int y, double scale)
    {
        List<PreviewLine> lines = new ArrayList<>();
        lines.add(PreviewLine.text("MMM", Configs.getHudTitleColor()));
        long globalTotal = MiningStats.getGlobalTotalMinedForDisplay();
        long worldTotal = MiningStats.getCurrentSourceTotalMined();
        long sessionTotal = MiningStats.getSessionBlocksMined();
        boolean sessionPaused = MiningStats.isSessionPaused();
        boolean sessionInactive = MiningStats.isSessionActive() == false || sessionPaused;
        if (FeatureToggle.TWEAK_HUD_PROJECT.getBooleanValue())
        {
            MiningStats.ProjectProgress project = MiningStats.getActiveProjectProgress();
            lines.add(PreviewLine.blocksMined("Project: " + UiFormat.truncate(project.name(), 18) + " | ", project.blocksMined()));
        }
        lines.add(PreviewLine.blocksMined("Global Total: ", globalTotal));
        lines.add(PreviewLine.blocksMined("World Total: ", worldTotal));
        lines.add(PreviewLine.blocksMined("Session Total: ", sessionTotal, sessionInactive));
        lines.add(PreviewLine.bphBps(MiningStats.getDisplayedBlocksPerHour(), MiningStats.getDisplayedBlocksPerSecond(), false));
        String sessionClock = MiningStats.getSessionDurationClock();
        lines.add(PreviewLine.text("Session Time: " + sessionClock, inactiveTextColor(sessionClock, sessionPaused)));

        int width = Math.max(lines.stream().mapToInt(line -> line.width(this.textRenderer)).max().orElse(190), 190);
        int lineHeight = this.textRenderer.fontHeight + 2;
        int padding = 4;
        int totalHeight = lines.size() * lineHeight + 24 + padding * 2;

        context.getMatrices().pushMatrix();
        context.getMatrices().translate(x, y);
        context.getMatrices().scale((float) scale, (float) scale);
        MmmUi.card(context, -padding, -padding, width + padding * 2, totalHeight, MmmUi.PANEL, MmmUi.BORDER);

        int drawY = 0;
        lines.getFirst().draw(context, this.textRenderer, 0, drawY, true);
        drawY += lineHeight;
        for (int i = 1; i < lines.size(); i++)
        {
            PreviewLine line = lines.get(i);
            line.draw(context, this.textRenderer, 0, drawY, false);
            drawY += lineHeight;
        }

        MiningStats.GoalProgress dailyGoal = MiningStats.getDailyGoalProgress();
        String progressText = UiFormat.formatProgress(dailyGoal.current(), dailyGoal.target());
        String percentText = dailyGoal.getPercent() + "%";
        int fillColor = UiFormat.getGoalColor(dailyGoal);
        context.drawText(this.textRenderer, Text.literal("Daily Goal"), 0, drawY + 2, Configs.getHudTitleColor(), false);
        int progressX = Math.max(0, (width - this.textRenderer.getWidth(progressText)) / 2);
        context.drawText(this.textRenderer, Text.literal(progressText), progressX, drawY + 2, Configs.getHudTextColor(), false);
        context.drawText(this.textRenderer, Text.literal(percentText), width - this.textRenderer.getWidth(percentText), drawY + 2, fillColor, false);
        int barY = drawY + 13;
        int fillWidth = dailyGoal.target() <= 0 ? 0 : (int) Math.min(width, (width * (double) dailyGoal.current()) / dailyGoal.target());
        context.fill(0, barY, width, barY + 6, MmmUi.INSET);
        context.fill(0, barY, fillWidth, barY + 6, fillColor);
        context.drawBorder(0, barY, width, 6, MmmUi.BORDER_SOFT);
        context.getMatrices().popMatrix();
    }

    private record PreviewSegment(String text, int color)
    {
    }

    private record PreviewLine(List<PreviewSegment> segments)
    {
        static PreviewLine text(String text, int color)
        {
            return new PreviewLine(List.of(new PreviewSegment(text, color)));
        }

        static PreviewLine blocksMined(String label, long value)
        {
            return blocksMined(label, value, false);
        }

        static PreviewLine blocksMined(String label, long value, boolean inactive)
        {
            return new PreviewLine(List.of(
                    new PreviewSegment(label, inactive ? Configs.getHudInactiveColor() : Configs.getHudTextColor()),
                    new PreviewSegment(UiFormat.formatCompact(value), inactive ? Configs.getHudInactiveColor() : Configs.getHudNumberColor()),
                    new PreviewSegment(" Blocks Mined", inactive ? Configs.getHudInactiveColor() : Configs.getHudTextColor())));
        }

        static PreviewLine bphBps(long blocksPerHour, double blocksPerSecond, boolean inactive)
        {
            int numberColor = inactive ? Configs.getHudInactiveColor() : Configs.getHudNumberColor();
            int labelColor = inactive ? Configs.getHudInactiveColor() : Configs.getHudTextColor();
            return new PreviewLine(List.of(
                    new PreviewSegment("BPH: ", labelColor),
                    new PreviewSegment(UiFormat.formatCompact(Math.max(0L, blocksPerHour)), numberColor),
                    new PreviewSegment(" / BPS: ", labelColor),
                    new PreviewSegment(UiFormat.formatBlocksPerSecond(blocksPerSecond), numberColor)));
        }

        int width(TextRenderer renderer)
        {
            int width = 0;
            for (PreviewSegment segment : this.segments)
            {
                width += renderer.getWidth(segment.text());
            }
            return width;
        }

        void draw(DrawContext context, TextRenderer renderer, int x, int y, boolean shadow)
        {
            int drawX = x;
            for (PreviewSegment segment : this.segments)
            {
                context.drawText(renderer, Text.literal(segment.text()), drawX, y, segment.color(), shadow);
                drawX += renderer.getWidth(segment.text());
            }
        }
    }

    private ControlLayout controlLayout()
    {
        int panelW = 270;
        int panelH = 78;
        int panelX = Math.max(12, this.width - panelW - 12);
        int panelY = 12;
        return new ControlLayout(panelX, panelY, panelW, panelH);
    }

    private record ControlLayout(int x, int y, int width, int height)
    {
    }

    private static int inactiveTextColor(String value, boolean sessionPaused)
    {
        String text = value == null ? "" : value.trim();
        return sessionPaused || "--".equals(text) || "Paused".equals(text) || "00:00:00".equals(text) ? Configs.getHudInactiveColor() : Configs.getHudTextColor();
    }
}
