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
        MmmUi.backdrop(context, this.width, this.height);
        MmmUi.card(context, centerX - 142, 12, 284, 78, MmmUi.PANEL, MmmUi.BORDER);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, 20, MmmUi.TEXT);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Drag the HUD to move it."), centerX, 34, MmmUi.LABEL);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Size: " + (int) Math.round(Configs.Generic.HUD_SCALE.getDoubleValue() * 100.0D) + "%"), centerX, 50, MmmUi.ACCENT_BRIGHT);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Use +/- or mouse wheel to change size."), centerX, 70, MmmUi.MUTED);

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
        List<PreviewLine> lines = new ArrayList<>();
        lines.add(PreviewLine.text("MMM", MmmUi.ACCENT_BRIGHT));
        MiningStats.PredictionSnapshot prediction = MiningStats.getPredictionSnapshot();
        long globalTotal = MiningStats.getGlobalTotalMinedForDisplay();
        long worldTotal = MiningStats.getCurrentSourceTotalMined();
        long sessionTotal = MiningStats.getSessionBlocksMined();
        if (FeatureToggle.TWEAK_HUD_PROJECT.getBooleanValue())
        {
            MiningStats.ProjectProgress project = MiningStats.getActiveProjectProgress();
            lines.add(PreviewLine.blocksMined("Project: " + UiFormat.truncate(project.name(), 18) + " | ", project.blocksMined()));
        }
        lines.add(PreviewLine.blocksMined("Global Total: ", globalTotal));
        lines.add(PreviewLine.blocksMined("World Total: ", worldTotal));
        lines.add(PreviewLine.blocksMined("Session Total: ", sessionTotal));
        lines.add(PreviewLine.text("Est. Blocks/Hr: " + UiFormat.formatDetailedBlocksPerHour(Math.round(prediction.blocksPerHour())), MmmUi.TEXT));
        lines.add(PreviewLine.text("Session Time: " + MiningStats.getSessionDurationClock(), MmmUi.TEXT));

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
        context.drawText(this.textRenderer, Text.literal("Daily Goal"), 0, drawY + 2, MmmUi.ACCENT_BRIGHT, false);
        context.drawText(this.textRenderer, Text.literal(progressText), 72, drawY + 2, MmmUi.TEXT, false);
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
            return new PreviewLine(List.of(
                    new PreviewSegment(label, MmmUi.TEXT),
                    new PreviewSegment(UiFormat.formatCompact(value), UiFormat.getBlocksMinedMilestoneColor(value)),
                    new PreviewSegment(" Blocks Mined", MmmUi.TEXT)));
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
}
