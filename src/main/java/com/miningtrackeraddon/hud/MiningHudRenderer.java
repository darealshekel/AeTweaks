package com.miningtrackeraddon.hud;

import java.util.ArrayList;
import java.util.List;

import com.miningtrackeraddon.config.Configs;
import com.miningtrackeraddon.config.Configs.HudAlignment;
import com.miningtrackeraddon.config.FeatureToggle;
import com.miningtrackeraddon.sync.CloudSyncManager;
import com.miningtrackeraddon.sync.DigsSyncManager;
import com.miningtrackeraddon.tracker.GoalNotificationManager;
import com.miningtrackeraddon.tracker.MiningStats;
import com.miningtrackeraddon.util.UiFormat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

public final class MiningHudRenderer
{
    private static final int LINE_BOX_COLOR = 0xA111141A;
    private static final int LINE_BOX_BORDER_COLOR = 0x66443638;
    private static final int SYNC_OK_COLOR = 0xFF2ECC71;
    private static final int SYNC_FAIL_COLOR = 0xFFE74C3C;
    private static final int BBOX_FILL_COLOR = 0xD1171A20;
    private static final int BBOX_ACCENT_COLOR = 0x553A2411;
    private static final int BBOX_BORDER_COLOR = 0xAA4A3538;
    private static final int HUD_TITLE_COLOR = 0xFFE3BD78;
    private static final int HUD_TEXT_COLOR = 0xFFF5EEE7;
    private static final int HUD_MUTED_COLOR = 0xD2D5C9BF;
    private static final int GOAL_BAR_BG = 0xFF1A1514;
    private static final int GOAL_BAR_BORDER = 0xAA4A3538;

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

        List<String> lines = new ArrayList<>();
        lines.add("MMM");

        MiningStats.ProjectProgress project = MiningStats.getActiveProjectProgress();
        MiningStats.PredictionSnapshot prediction = MiningStats.getPredictionSnapshot();
        lines.add("Project: " + UiFormat.truncate(project.name(), 18) + " | " + UiFormat.formatBlocks(project.blocksMined()));
        if (FeatureToggle.TWEAK_HUD_TOTAL_MINED.getBooleanValue())
        {
            lines.add("World Total: " + UiFormat.formatBlocks(MiningStats.getCurrentSourceTotalMined()));
            lines.add("Session Total: " + UiFormat.formatBlocks(MiningStats.getSessionBlocksMined()));
        }
        if (FeatureToggle.TWEAK_HUD_BLOCKS_PER_HOUR.getBooleanValue())
        {
            if (MiningStats.hasActualBlocksPerHour())
            {
                lines.add("Blocks/hr: " + UiFormat.formatBlocksPerHour(MiningStats.getActualBlocksPerHour()));
            }
            lines.add("Est. Blocks/Hr: " + UiFormat.formatDetailedBlocksPerHour(Math.round(prediction.blocksPerHour())));
        }
        lines.add("Session Time: " + MiningStats.getSessionDurationClock());
        if (FeatureToggle.TWEAK_DAILY_GOAL.getBooleanValue())
        {
            lines.add("Daily Reset In: " + MiningStats.getDailyResetCountdownClock());
        }
        if (FeatureToggle.TWEAK_HUD_ETA.getBooleanValue() && FeatureToggle.TWEAK_DAILY_GOAL.getBooleanValue())
        {
            lines.add("ETA To Goal: " + MiningStats.getEstimatedTimeToDailyGoal());
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
            context.fill(bboxX + 1, bboxY + 1, bboxX + bboxW - 1, bboxY + 2, BBOX_ACCENT_COLOR);
            context.drawBorder(bboxX, bboxY, bboxW, bboxH, BBOX_BORDER_COLOR);
        }

        int drawY = 0;
        long now = System.currentTimeMillis();
        boolean syncHealthy = CloudSyncManager.isHudHealthy(now) && DigsSyncManager.isHudHealthy(now);
        String title = lines.getFirst();
        int titleTextWidth = client.textRenderer.getWidth(title);
        drawLineBox(context, 0, drawY, titleTextWidth + 12);
        drawSyncIndicator(context, 3, drawY + 5, syncHealthy ? SYNC_OK_COLOR : SYNC_FAIL_COLOR);
        context.drawText(client.textRenderer, Text.literal(title), 10, drawY, HUD_TITLE_COLOR, true);
        drawY += lineHeight;
        for (int i = 1; i < lines.size(); i++)
        {
            drawLineBox(context, 0, drawY, client.textRenderer.getWidth(lines.get(i)));
            context.drawText(client.textRenderer, Text.literal(lines.get(i)), 0, drawY, HUD_TEXT_COLOR, false);
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
        lines.add("Project: Example Project | 12.3K blocks");
        if (FeatureToggle.TWEAK_HUD_TOTAL_MINED.getBooleanValue()) lines.add("World Total: 12.3K blocks");
        if (FeatureToggle.TWEAK_HUD_TOTAL_MINED.getBooleanValue()) lines.add("Session Total: 890 blocks");
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
        context.fill(x - 3, y - 1, x + textWidth + 4, y, BBOX_ACCENT_COLOR);
        context.drawBorder(x - 4, y - 2, textWidth + 9, 13, LINE_BOX_BORDER_COLOR);
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
}
