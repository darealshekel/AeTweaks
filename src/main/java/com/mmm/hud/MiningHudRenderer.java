package com.mmm.hud;

import java.util.ArrayList;
import java.util.List;

import com.mmm.config.Configs;
import com.mmm.config.Configs.HudAlignment;
import com.mmm.config.FeatureToggle;
import com.mmm.sync.CloudSyncManager;
import com.mmm.sync.DigsSyncManager;
import com.mmm.tracker.GoalNotificationManager;
import com.mmm.tracker.MiningStats;
import com.mmm.ui.MmmUi;
import com.mmm.util.UiFormat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

public final class MiningHudRenderer
{
    private static final int LINE_BOX_COLOR = MmmUi.INSET;
    private static final int SYNC_OK_COLOR = MmmUi.SUCCESS;
    private static final int SYNC_FAIL_COLOR = MmmUi.ERROR;
    private static final int BBOX_FILL_COLOR = MmmUi.PANEL;
    private static final int HUD_NEUTRAL_BORDER_COLOR = 0x66090909;
    private static final int HUD_TITLE_COLOR = MmmUi.ACCENT_BRIGHT;
    private static final int HUD_TEXT_COLOR = MmmUi.TEXT;
    private static final int GOAL_BAR_BG = MmmUi.INSET;
    private static final int GOAL_BAR_BORDER = HUD_NEUTRAL_BORDER_COLOR;

    private MiningHudRenderer()
    {
    }

    public static void render(DrawContext context, MinecraftClient client)
    {
        if (FeatureToggle.TWEAK_MINING_TRACKER.getBooleanValue() == false ||
            FeatureToggle.TWEAK_HUD.getBooleanValue() == false ||
            client.player == null ||
            client.options.hudHidden)
        {
            GoalNotificationManager.render(context, client);
            return;
        }

        List<HudLine> lines = new ArrayList<>();
        lines.add(HudLine.text("MMM", HUD_TITLE_COLOR));

        MiningStats.PredictionSnapshot prediction = MiningStats.getPredictionSnapshot();
        if (FeatureToggle.TWEAK_HUD_PROJECT.getBooleanValue())
        {
            MiningStats.ProjectProgress project = MiningStats.getActiveProjectProgress();
            lines.add(HudLine.blocksMined("Project: " + UiFormat.truncate(project.name(), 18) + " | ", project.blocksMined()));
        }
        if (FeatureToggle.TWEAK_HUD_TOTAL_MINED.getBooleanValue())
        {
            long globalTotal = MiningStats.getGlobalTotalMinedForDisplay();
            long worldTotal = MiningStats.getCurrentSourceTotalMined();
            long sessionTotal = MiningStats.getSessionBlocksMined();
            long dailyBlocks = MiningStats.getDailyBlocksMined();
            long weeklyBlocks = MiningStats.getWeeklyBlocksMined();
            long dailyRecord = MiningStats.getPersonalRecordDailyBlocks();
            long weeklyRecord = MiningStats.getPersonalRecordWeeklyBlocks();
            lines.add(HudLine.blocksMined("Global Total: ", globalTotal));
            lines.add(HudLine.blocksMined("World Total: ", worldTotal));
            lines.add(HudLine.blocksMined("Session Total: ", sessionTotal));
            lines.add(HudLine.dualBlocksMined("Today / Week: ", dailyBlocks, weeklyBlocks));
            lines.add(HudLine.dualBlocksMined("PR Day / Week: ", dailyRecord, weeklyRecord));
            lines.add(HudLine.text("Fastest 100K: " + MiningStats.getFastest100kClock(), HUD_TEXT_COLOR));
        }
        if (FeatureToggle.TWEAK_HUD_BLOCKS_PER_HOUR.getBooleanValue())
        {
            if (MiningStats.hasActualBlocksPerHour())
            {
                lines.add(HudLine.text("Blocks/hr: " + UiFormat.formatBlocksPerHour(MiningStats.getActualBlocksPerHour()), HUD_TEXT_COLOR));
            }
            lines.add(HudLine.text("Est. Blocks/Hr: " + UiFormat.formatDetailedBlocksPerHour(Math.round(prediction.blocksPerHour())), HUD_TEXT_COLOR));
        }
        lines.add(HudLine.text("Session Time: " + MiningStats.getSessionDurationClock(), HUD_TEXT_COLOR));
        if (FeatureToggle.TWEAK_DAILY_GOAL.getBooleanValue())
        {
            lines.add(HudLine.text("Daily Reset In: " + MiningStats.getDailyResetCountdownClock(), HUD_TEXT_COLOR));
        }
        if (FeatureToggle.TWEAK_HUD_ETA.getBooleanValue() && FeatureToggle.TWEAK_DAILY_GOAL.getBooleanValue())
        {
            lines.add(HudLine.text("ETA To Goal: " + MiningStats.getEstimatedTimeToDailyGoal(), HUD_TEXT_COLOR));
        }

        MiningStats.GoalProgress dailyGoal = MiningStats.getDailyGoalProgress();
        int lineHeight = client.textRenderer.fontHeight + 2;
        int padding = 4;
        int width = Math.max(getTextWidth(client, lines), 190);
        int extraHeight = FeatureToggle.TWEAK_HUD_GOAL_PROGRESS.getBooleanValue() && dailyGoal.enabled() ? 24 : 0;
        int totalHeight = lines.size() * lineHeight + extraHeight + padding * 2;

        float scale = (float) Configs.Generic.HUD_SCALE.getDoubleValue();
        int scaledWidth = (int) ((width + padding * 2) * scale);
        int scaledHeight = (int) (totalHeight * scale);
        int x = resolveHudX(client, scaledWidth);
        int y = resolveHudY(client, scaledHeight);

        context.getMatrices().pushMatrix();
        context.getMatrices().translate(x, y);
        context.getMatrices().scale(scale, scale);

        if (FeatureToggle.TWEAK_HUD_BOUNDING_BOX.getBooleanValue())
        {
            int bboxX = -padding;
            int bboxY = -2;
            int bboxW = width + padding * 2;
            int bboxH = lines.size() * lineHeight + extraHeight + 4;
            context.fill(bboxX, bboxY, bboxX + bboxW, bboxY + bboxH, BBOX_FILL_COLOR);
        }

        int drawY = 0;
        long now = System.currentTimeMillis();
        boolean syncHealthy = CloudSyncManager.isHudHealthy(now) && DigsSyncManager.isHudHealthy(now);
        String title = lines.getFirst().text();
        int titleTextWidth = client.textRenderer.getWidth(title);
        if (Configs.Generic.HUD_TEXT_BACKGROUND.getBooleanValue())
        {
            drawLineBox(context, 0, drawY, titleTextWidth + 12);
        }
        drawSyncIndicator(context, 3, drawY + 5, syncHealthy ? SYNC_OK_COLOR : SYNC_FAIL_COLOR);
        context.drawText(client.textRenderer, Text.literal(title), 10, drawY, HUD_TITLE_COLOR, true);
        drawY += lineHeight;
        for (int i = 1; i < lines.size(); i++)
        {
            HudLine line = lines.get(i);
            if (Configs.Generic.HUD_TEXT_BACKGROUND.getBooleanValue())
            {
                drawLineBox(context, 0, drawY, line.width(client.textRenderer));
            }
            line.draw(context, client.textRenderer, 0, drawY, false);
            drawY += lineHeight;
        }

        if (FeatureToggle.TWEAK_HUD_GOAL_PROGRESS.getBooleanValue() && dailyGoal.enabled())
        {
            drawGoalProgress(context, client, 0, drawY + 2, width, dailyGoal);
        }

        context.getMatrices().popMatrix();
        GoalNotificationManager.render(context, client);
    }

    public static int[] getBounds(MinecraftClient client)
    {
        List<String> lines = new ArrayList<>();
        lines.add("MMM");
        if (FeatureToggle.TWEAK_HUD_PROJECT.getBooleanValue()) lines.add("Project: Example Project | 12.3K Blocks Mined");
        if (FeatureToggle.TWEAK_HUD_TOTAL_MINED.getBooleanValue()) lines.add("Global Total: 123M Blocks Mined");
        if (FeatureToggle.TWEAK_HUD_TOTAL_MINED.getBooleanValue()) lines.add("World Total: 12.3K Blocks Mined");
        if (FeatureToggle.TWEAK_HUD_TOTAL_MINED.getBooleanValue()) lines.add("Session Total: 890 Blocks Mined");
        if (FeatureToggle.TWEAK_HUD_TOTAL_MINED.getBooleanValue()) lines.add("Today / Week: 12.3K / 84.2K Blocks Mined");
        if (FeatureToggle.TWEAK_HUD_TOTAL_MINED.getBooleanValue()) lines.add("PR Day / Week: 21.5K / 120K Blocks Mined");
        if (FeatureToggle.TWEAK_HUD_TOTAL_MINED.getBooleanValue()) lines.add("Fastest 100K: 8h 20m");
        if (FeatureToggle.TWEAK_HUD_BLOCKS_PER_HOUR.getBooleanValue())
        {
            lines.add("Blocks/hr: 12.3K blocks/hr");
            lines.add("Est. Blocks/Hr: 12,450 blocks/hr");
        }
        lines.add("Session Time: 01:23:45");
        if (FeatureToggle.TWEAK_DAILY_GOAL.getBooleanValue()) lines.add("Daily Reset In: 23:59:59");
        if (FeatureToggle.TWEAK_HUD_ETA.getBooleanValue() && FeatureToggle.TWEAK_DAILY_GOAL.getBooleanValue()) lines.add("ETA To Goal: 1h 12m");

        int width = Math.max(getTextWidth(client, lines), 190);
        int lineHeight = client.textRenderer.fontHeight + 2;
        int padding = 4;
        int extraHeight = FeatureToggle.TWEAK_HUD_GOAL_PROGRESS.getBooleanValue() && FeatureToggle.TWEAK_DAILY_GOAL.getBooleanValue() ? 24 : 0;
        int totalHeight = lines.size() * lineHeight + extraHeight + padding * 2;
        double scale = Configs.Generic.HUD_SCALE.getDoubleValue();
        int scaledWidth = (int) ((width + padding * 2) * scale);
        int scaledHeight = (int) (totalHeight * scale);
        int x = resolveHudX(client, scaledWidth);
        int y = resolveHudY(client, scaledHeight);
        return new int[] { x, y, x + scaledWidth, y + scaledHeight };
    }

    private record HudSegment(String text, int color)
    {
    }

    private record HudLine(List<HudSegment> segments)
    {
        static HudLine text(String text, int color)
        {
            return new HudLine(List.of(new HudSegment(text, color)));
        }

        static HudLine blocksMined(String label, long value)
        {
            return new HudLine(List.of(
                    new HudSegment(label, HUD_TEXT_COLOR),
                    new HudSegment(UiFormat.formatCompact(value), UiFormat.getBlocksMinedMilestoneColor(value)),
                    new HudSegment(" Blocks Mined", HUD_TEXT_COLOR)));
        }

        static HudLine dualBlocksMined(String label, long left, long right)
        {
            return new HudLine(List.of(
                    new HudSegment(label, HUD_TEXT_COLOR),
                    new HudSegment(UiFormat.formatCompact(left), UiFormat.getBlocksMinedMilestoneColor(left)),
                    new HudSegment(" / ", HUD_TEXT_COLOR),
                    new HudSegment(UiFormat.formatCompact(right), UiFormat.getBlocksMinedMilestoneColor(right)),
                    new HudSegment(" Blocks Mined", HUD_TEXT_COLOR)));
        }

        String text()
        {
            StringBuilder builder = new StringBuilder();
            for (HudSegment segment : this.segments)
            {
                builder.append(segment.text());
            }
            return builder.toString();
        }

        int width(TextRenderer renderer)
        {
            int width = 0;
            for (HudSegment segment : this.segments)
            {
                width += renderer.getWidth(segment.text());
            }
            return width;
        }

        void draw(DrawContext context, TextRenderer renderer, int x, int y, boolean shadow)
        {
            int drawX = x;
            for (HudSegment segment : this.segments)
            {
                context.drawText(renderer, Text.literal(segment.text()), drawX, y, segment.color(), shadow);
                drawX += renderer.getWidth(segment.text());
            }
        }
    }

    private static int resolveHudX(MinecraftClient client, int scaledWidth)
    {
        int maxX = Math.max(0, client.getWindow().getScaledWidth() - scaledWidth);
        int rawX = Math.max(0, Math.min(Configs.Generic.HUD_X.getIntegerValue(), 820));
        double normalized = rawX / 820.0D;
        HudAlignment alignment = (HudAlignment) Configs.Generic.HUD_ALIGNMENT.getOptionListValue();
        return switch (alignment)
        {
            case TOP_RIGHT, BOTTOM_RIGHT -> Math.max(0, Math.min(maxX, (int) Math.round(maxX * (1.0D - normalized))));
            default -> Math.max(0, Math.min(maxX, (int) Math.round(maxX * normalized)));
        };
    }

    private static int resolveHudY(MinecraftClient client, int scaledHeight)
    {
        int maxY = Math.max(0, client.getWindow().getScaledHeight() - scaledHeight);
        int rawY = Math.max(0, Math.min(Configs.Generic.HUD_Y.getIntegerValue(), 460));
        double normalized = rawY / 460.0D;
        HudAlignment alignment = (HudAlignment) Configs.Generic.HUD_ALIGNMENT.getOptionListValue();
        return switch (alignment)
        {
            case BOTTOM_LEFT, BOTTOM_RIGHT -> Math.max(0, Math.min(maxY, (int) Math.round(maxY * (1.0D - normalized))));
            default -> Math.max(0, Math.min(maxY, (int) Math.round(maxY * normalized)));
        };
    }

    private static void drawLineBox(DrawContext context, int x, int y, int textWidth)
    {
        context.fill(x - 4, y - 2, x + textWidth + 5, y + 11, LINE_BOX_COLOR);
        context.fill(x - 3, y - 1, x + textWidth + 4, y, HUD_NEUTRAL_BORDER_COLOR);
        context.drawBorder(x - 4, y - 2, textWidth + 9, 13, HUD_NEUTRAL_BORDER_COLOR);
    }

    private static void drawSyncIndicator(DrawContext context, int centerX, int centerY, int color)
    {
        context.fill(centerX - 2, centerY - 2, centerX + 3, centerY + 3, color);
        context.fill(centerX - 1, centerY - 1, centerX + 2, centerY + 2, color);
    }

    private static void drawGoalProgress(DrawContext context, MinecraftClient client, int x, int y, int width, MiningStats.GoalProgress progress)
    {
        int fillColor = UiFormat.getGoalColor(progress);
        int fillWidth = progress.target() <= 0 ? 0 : (int) Math.min(width, (width * (double) progress.current()) / progress.target());
        String percentText = progress.getPercent() + "%";
        context.drawText(client.textRenderer, Text.literal("Daily Goal"), x, y, HUD_TITLE_COLOR, false);
        context.drawText(client.textRenderer, Text.literal(UiFormat.formatProgress(progress.current(), progress.target())), x + 72, y, HUD_TEXT_COLOR, false);
        context.drawText(client.textRenderer, Text.literal(percentText), x + width - client.textRenderer.getWidth(Text.literal(percentText)), y, fillColor, false);

        int barY = y + 11;
        context.fill(x, barY, x + width, barY + 6, GOAL_BAR_BG);
        context.fill(x, barY, x + fillWidth, barY + 6, fillColor);
        context.drawBorder(x, barY, width, 6, GOAL_BAR_BORDER);
    }

    private static int getTextWidth(MinecraftClient client, List<String> lines)
    {
        int width = 0;
        for (String line : lines)
        {
            width = Math.max(width, client.textRenderer.getWidth(Text.literal(line)));
        }
        return width;
    }

    private static int getTextWidth(MinecraftClient client, Iterable<HudLine> lines)
    {
        int width = 0;
        for (HudLine line : lines)
        {
            width = Math.max(width, line.width(client.textRenderer));
        }
        return width;
    }
}
