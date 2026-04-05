package com.miningtrackeraddon.util;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

import com.miningtrackeraddon.tracker.MiningStats;

public final class UiFormat
{
    private static final DecimalFormat COMPACT_FORMAT = new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.US));

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
        return COMPACT_FORMAT.format(value / divisor) + suffix;
    }
}
