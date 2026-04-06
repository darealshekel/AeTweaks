package com.miningtrackeraddon.tracker;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import com.miningtrackeraddon.config.Configs;
import com.miningtrackeraddon.config.FeatureToggle;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

public final class GoalNotificationManager
{
    private static final List<String> MESSAGES_25 = List.of(
            "LET’S GO! You’re officially on the board!",
            "Good start! KEEP DIGGING!",
            "Momentum is building! Don’t stop now!",
            "You’ve begun! Stay locked in!",
            "This is where it starts! KEEP PUSHING!"
    );
    private static final List<String> MESSAGES_50 = List.of(
            "HALFWAY THERE! KEEP GOING!",
            "You’re deep in it now! DON’T SLOW DOWN!",
            "This is where most quit! YOU WON’T!",
            "Strong progress! DOUBLE IT!",
            "Stay focused! FINISH THIS!"
    );
    private static final List<String> MESSAGES_75 = List.of(
            "YOU’RE SO CLOSE! DON’T STOP NOW!",
            "FINAL STRETCH! GIVE IT EVERYTHING!",
            "LOCK IN! BRING IT HOME!",
            "THIS IS YOUR MOMENT! FINISH IT!",
            "NO BREAKS! PUSH THROUGH!"
    );
    private static final List<String> MESSAGES_100 = List.of(
            "GOAL COMPLETE! LET’S GOOO!!!",
            "YOU DID IT! ABSOLUTE GRIND!",
            "MISSION DONE! WHAT’S NEXT?!",
            "ANOTHER ONE DOWN! KEEP CLIMBING!",
            "DISCIPLINE WINS! YOU PROVED IT!!!"
    );

    private static final Set<Integer> TRIGGERED_THRESHOLDS = new HashSet<>();
    private static long lastObservedProgress;
    private static long lastObservedTarget;

    private GoalNotificationManager()
    {
    }

    public static void onGoalProgressChanged(long oldProgress, MiningStats.GoalProgress progress)
    {
        if (progress.target() <= 0)
        {
            return;
        }

        if (progress.target() != lastObservedTarget || progress.current() < lastObservedProgress)
        {
            clear();
        }

        int oldPercent = (int) Math.min(100, (oldProgress * 100) / progress.target());
        int newPercent = progress.getPercent();

        lastObservedProgress = progress.current();
        lastObservedTarget = progress.target();

        for (Integer threshold : Configs.getNotificationThresholds())
        {
            if (!TRIGGERED_THRESHOLDS.contains(threshold) && oldPercent < threshold && newPercent >= threshold)
            {
                TRIGGERED_THRESHOLDS.add(threshold);
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
    }

    public static void clear()
    {
        TRIGGERED_THRESHOLDS.clear();
        lastObservedProgress = 0L;
        lastObservedTarget = 0L;
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
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null)
        {
            String message = "You reached " + threshold + "%! " + getRandomMessage(threshold);
            client.player.sendMessage(Text.literal("[AeTweaks] ").formatted(net.minecraft.util.Formatting.GOLD)
                    .append(Text.literal(message).formatted(getFormattingForThreshold(threshold))), false);
        }
    }

    private static String getRandomMessage(int threshold)
    {
        List<String> pool = threshold >= 100 ? MESSAGES_100
                : threshold >= 75 ? MESSAGES_75
                : threshold >= 50 ? MESSAGES_50
                : MESSAGES_25;
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    private static net.minecraft.util.Formatting getFormattingForThreshold(int threshold)
    {
        if (threshold >= 100)
        {
            return net.minecraft.util.Formatting.BLUE;
        }
        if (threshold <= 25)
        {
            return net.minecraft.util.Formatting.RED;
        }
        if (threshold <= 50)
        {
            return net.minecraft.util.Formatting.YELLOW;
        }
        if (threshold <= 75)
        {
            return net.minecraft.util.Formatting.GREEN;
        }
        return net.minecraft.util.Formatting.DARK_GREEN;
    }
}
