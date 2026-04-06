package com.miningtrackeraddon.hud;

import java.util.ArrayList;
import java.util.List;

import com.miningtrackeraddon.config.Configs;
import com.miningtrackeraddon.config.FeatureToggle;
import com.miningtrackeraddon.tracker.GoalNotificationManager;
import com.miningtrackeraddon.tracker.MiningStats;
import com.miningtrackeraddon.sync.MmmSyncManager;
import com.miningtrackeraddon.util.UiFormat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

public final class MiningHudRenderer
{
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
        lines.add("AeTweaks");

        MiningStats.ProjectProgress project = MiningStats.getActiveProjectProgress();
        lines.add("Project: " + UiFormat.truncate(project.name(), 18) + " | " + UiFormat.formatBlocks(project.blocksMined()));
        if (FeatureToggle.TWEAK_HUD_TOTAL_MINED.getBooleanValue())
        {
            lines.add("Total Mined: " + UiFormat.formatBlocks(MiningStats.getTotalMined()));
        }
        if (FeatureToggle.TWEAK_HUD_BLOCKS_PER_HOUR.getBooleanValue())
        {
            if (MiningStats.hasActualBlocksPerHour())
            {
                lines.add("Blocks/hr: " + UiFormat.formatBlocksPerHour(MiningStats.getActualBlocksPerHour()));
            }
            lines.add("ETA blocks/hr: " + UiFormat.formatBlocksPerHour(MiningStats.getEstimatedBlocksPerHour()));
        }
        lines.add("Session Time: " + MiningStats.getSessionDurationClock());
        if (FeatureToggle.TWEAK_MMM_SYNC.getBooleanValue())
        {
            lines.add(MmmSyncManager.getHudStatusLine());
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
        int x = Math.max(0, Math.min(Configs.Generic.HUD_X.getIntegerValue(), client.getWindow().getScaledWidth() - scaledWidth));
        int y = Math.max(0, Math.min(Configs.Generic.HUD_Y.getIntegerValue(), client.getWindow().getScaledHeight() - scaledHeight));
        Configs.Generic.HUD_X.setIntegerValue(x);
        Configs.Generic.HUD_Y.setIntegerValue(y);

        context.getMatrices().push();
        context.getMatrices().translate(x, y, 0);
        context.getMatrices().scale(scale, scale, 1.0F);
        context.fill(-padding, -padding, width + padding, totalHeight, 0xAA101010);
        context.drawBorder(-padding, -padding, width + padding * 2, totalHeight, 0xFF6C5A1A);

        int drawY = 0;
        context.drawText(client.textRenderer, Text.literal(lines.getFirst()), 0, drawY, UiFormat.YELLOW, true);
        drawY += lineHeight;
        for (int i = 1; i < lines.size(); i++)
        {
            context.drawText(client.textRenderer, Text.literal(lines.get(i)), 0, drawY, UiFormat.TEXT_PRIMARY, false);
            drawY += lineHeight;
        }

        if (FeatureToggle.TWEAK_HUD_GOAL_PROGRESS.getBooleanValue() && dailyGoal.enabled())
        {
            drawGoalProgress(context, client, 0, drawY + 2, width, dailyGoal);
        }

        context.getMatrices().pop();
        GoalNotificationManager.render(context, client);
    }

    public static int[] getBounds(MinecraftClient client)
    {
        List<String> lines = new ArrayList<>();
        lines.add("AeTweaks");
        lines.add("Project: Example Project | 12.3K blocks");
        if (FeatureToggle.TWEAK_HUD_TOTAL_MINED.getBooleanValue()) lines.add("Total Mined: 12.3K blocks");
        if (FeatureToggle.TWEAK_HUD_BLOCKS_PER_HOUR.getBooleanValue())
        {
            lines.add("Blocks/hr: 12.3K blocks/hr");
            lines.add("ETA blocks/hr: 12.3K blocks/hr");
        }
        lines.add("Session Time: 01:23:45");
        if (FeatureToggle.TWEAK_MMM_SYNC.getBooleanValue()) lines.add("MMM Sync: Connected");
        if (FeatureToggle.TWEAK_HUD_ETA.getBooleanValue() && FeatureToggle.TWEAK_DAILY_GOAL.getBooleanValue()) lines.add("ETA To Goal: 1h 12m");

        int width = Math.max(getTextWidth(client, lines), 190);
        int lineHeight = client.textRenderer.fontHeight + 2;
        int padding = 4;
        int extraHeight = FeatureToggle.TWEAK_HUD_GOAL_PROGRESS.getBooleanValue() && FeatureToggle.TWEAK_DAILY_GOAL.getBooleanValue() ? 24 : 0;
        int totalHeight = lines.size() * lineHeight + extraHeight + padding * 2;
        double scale = Configs.Generic.HUD_SCALE.getDoubleValue();
        int scaledWidth = (int) ((width + padding * 2) * scale);
        int scaledHeight = (int) (totalHeight * scale);
        int x = Configs.Generic.HUD_X.getIntegerValue();
        int y = Configs.Generic.HUD_Y.getIntegerValue();
        return new int[] { x, y, x + scaledWidth, y + scaledHeight };
    }

    private static void drawGoalProgress(DrawContext context, MinecraftClient client, int x, int y, int width, MiningStats.GoalProgress progress)
    {
        int fillColor = UiFormat.getGoalColor(progress);
        int fillWidth = progress.target() <= 0 ? 0 : (int) Math.min(width, (width * (double) progress.current()) / progress.target());
        String percentText = progress.getPercent() + "%";
        context.drawText(client.textRenderer, Text.literal("Daily Goal"), x, y, UiFormat.YELLOW, false);
        context.drawText(client.textRenderer, Text.literal(UiFormat.formatProgress(progress.current(), progress.target())), x + 72, y, UiFormat.TEXT_PRIMARY, false);
        context.drawText(client.textRenderer, Text.literal(percentText), x + width - client.textRenderer.getWidth(Text.literal(percentText)), y, fillColor, false);

        int barY = y + 11;
        context.fill(x, barY, x + width, barY + 6, 0xFF333333);
        context.fill(x, barY, x + fillWidth, barY + 6, fillColor);
        context.drawBorder(x, barY, width, 6, 0xFF777777);
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
