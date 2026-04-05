package com.miningtrackeraddon.tracker;

import com.miningtrackeraddon.config.Configs;
import com.miningtrackeraddon.config.FeatureToggle;
import com.miningtrackeraddon.util.UiFormat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

public final class GoalNotificationManager
{
    private static final long DISPLAY_MS = 3_000L;
    private static long shownAtMs;
    private static boolean visible;
    private static String announcementText = "GOAL REACHED";
    private static int announcementColor = UiFormat.YELLOW;

    private GoalNotificationManager()
    {
    }

    public static void onGoalProgressChanged(long oldProgress, MiningStats.GoalProgress progress)
    {
        if (progress.target() <= 0)
        {
            return;
        }

        int oldPercent = (int) Math.min(100, (oldProgress * 100) / progress.target());
        int newPercent = progress.getPercent();

        for (Integer threshold : Configs.getNotificationThresholds())
        {
            if (oldPercent < threshold && newPercent >= threshold)
            {
                if (FeatureToggle.TWEAK_NOTIFICATIONS.getBooleanValue())
                {
                    showThresholdAnnouncement(threshold);
                    if (FeatureToggle.TWEAK_SOUND_ALERTS.getBooleanValue())
                    {
                        playLevelUpSound();
                    }
                }
                return;
            }
        }

        if (FeatureToggle.TWEAK_SOUND_ALERTS.getBooleanValue() &&
            oldPercent < Configs.Generic.SOUND_ALERT_THRESHOLD.getIntegerValue() &&
            newPercent >= Configs.Generic.SOUND_ALERT_THRESHOLD.getIntegerValue())
        {
            playLevelUpSound();
        }
    }

    public static void render(DrawContext context, MinecraftClient client)
    {
        if (visible == false)
        {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - shownAtMs > DISPLAY_MS)
        {
            visible = false;
            return;
        }

        TextRenderer textRenderer = client.textRenderer;
        Text title = Text.literal(announcementText);
        int centerX = client.getWindow().getScaledWidth() / 2;
        int centerY = client.getWindow().getScaledHeight() / 2;

        context.getMatrices().push();
        context.getMatrices().translate(centerX, centerY - 20, 0.0F);
        context.getMatrices().scale(2.6F, 2.6F, 1.0F);
        int width = textRenderer.getWidth(title);
        context.drawText(textRenderer, title, -width / 2, -6, announcementColor, true);
        context.getMatrices().pop();
    }

    public static void clear()
    {
        visible = false;
        shownAtMs = 0L;
    }

    private static void playLevelUpSound()
    {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null)
        {
            client.player.playSound(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.0F, 1.0F);
        }
    }

    private static void showThresholdAnnouncement(int threshold)
    {
        visible = true;
        shownAtMs = System.currentTimeMillis();
        if (threshold >= 100)
        {
            announcementText = "GOAL REACHED";
            announcementColor = UiFormat.BLUE;
        }
        else
        {
            announcementText = threshold + "%";
            announcementColor = threshold <= 25 ? UiFormat.RED : threshold <= 50 ? UiFormat.YELLOW : threshold <= 75 ? UiFormat.LIGHT_GREEN : UiFormat.DARK_GREEN;
        }
    }
}
