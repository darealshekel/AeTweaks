package com.miningtrackeraddon.util;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

import com.miningtrackeraddon.config.Configs;
import com.miningtrackeraddon.tracker.MiningPaceEstimator;
import com.miningtrackeraddon.tracker.MiningStats;

public final class UiFormat
{
    private static final DecimalFormat COMPACT_FORMAT_SMALL = new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.US));
    private static final DecimalFormat COMPACT_FORMAT_MEDIUM = new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.US));
    private static final DecimalFormat COMPACT_FORMAT_LARGE = new DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.US));
    private static final DecimalFormat WHOLE_NUMBER_FORMAT = new DecimalFormat("#,###", DecimalFormatSymbols.getInstance(Locale.US));

    public static final int YELLOW = 0xFFF2D24B;
    public static final int TEXT_PRIMARY = 0xFFFFFFFF;
    public static final int TEXT_MUTED = 0xFFCCCCCC;
    public static final int RED = 0xFFE85C5C;
    public static final int GOLD = 0xFFE0B84D;
    public static final int LIGHT_GREEN = 0xFF7EDC83;
    public static final int DARK_GREEN = 0xFF2F9E44;
    public static final int BLUE = 0xFF4D8DFF;

    private UiFormat()
    {
    }

    public static String formatCompact(long value)
    {
        if (Configs.Generic.ABBREVIATED_NUMBERS.getBooleanValue() == false)
        {
            return WHOLE_NUMBER_FORMAT.format(value);
        }

        long absolute = Math.abs(value);
        if (absolute < 1_000L) return Long.toString(value);
        if (absolute < 1_000_000L) return suffix(value, 1_000D, "K");
        if (absolute < 1_000_000_000L) return suffix(value, 1_000_000D, "M");
        if (absolute < 1_000_000_000_000L) return suffix(value, 1_000_000_000D, "B");
        return suffix(value, 1_000_000_000_000D, "T");
    }

    public static String formatProgress(long current, long target)
    {
        return formatCompact(current) + "/" + formatCompact(target);
    }

    public static String formatBlocks(long value)
    {
        return formatCompact(value) + " blocks";
    }

    public static String formatBlocksPerHour(long value)
    {
        return formatCompact(value) + " blocks/hr";
    }

    public static String formatDetailedBlocksPerHour(long value)
    {
        if (Configs.Generic.ABBREVIATED_NUMBERS.getBooleanValue())
        {
            return formatCompact(value) + " blocks/hr";
        }
        return WHOLE_NUMBER_FORMAT.format(Math.max(0L, value)) + " blocks/hr";
    }

    public static String formatDuration(long totalSeconds)
    {
        long seconds = Math.max(0L, totalSeconds);
        if (seconds < 60L)
        {
            return seconds + "s";
        }

        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long remainingSeconds = seconds % 60L;

        if (hours > 0L)
        {
            long days = hours / 24L;
            if (days > 0L)
            {
                long remainingHours = hours % 24L;
                return days + "d " + remainingHours + "h";
            }
            return hours + "h " + minutes + "m";
        }

        return minutes + "m " + remainingSeconds + "s";
    }

    public static String formatPaceState(MiningPaceEstimator.PaceState paceState)
    {
        return switch (paceState)
        {
            case RAMPING_UP -> "Ramping Up";
            case STABLE -> "Stable";
            case SLOWING -> "Slowing";
            case PAUSED -> "Paused";
            default -> "Calculating";
        };
    }

    public static String formatConfidence(double confidence)
    {
        if (confidence >= 0.72D) return "High";
        if (confidence >= 0.40D) return "Medium";
        if (confidence > 0.0D) return "Low";
        return "Calculating";
    }

    public static int getGoalColor(MiningStats.GoalProgress progress)
    {
        int percent = progress.getPercent();
        if (percent >= 100) return BLUE;
        if (percent >= 75) return DARK_GREEN;
        if (percent >= 50) return LIGHT_GREEN;
        if (percent >= 25) return GOLD;
        return RED;
    }

    public static String truncate(String value, int maxLength)
    {
        if (value == null || value.length() <= maxLength)
        {
            return value == null ? "" : value;
        }

        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private static String suffix(long value, double divisor, String suffix)
    {
        boolean negative = value < 0L;
        double scaled = Math.abs(value) / divisor;
        DecimalFormat format = scaled < 10D ? COMPACT_FORMAT_SMALL : (scaled < 100D ? COMPACT_FORMAT_MEDIUM : COMPACT_FORMAT_LARGE);
        return (negative ? "-" : "") + format.format(scaled) + suffix;
    }
}
