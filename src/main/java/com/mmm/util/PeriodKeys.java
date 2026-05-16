package com.mmm.util;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.Locale;

public final class PeriodKeys
{
    public static final ZoneId UTC = ZoneId.of("UTC");
    private static final DateTimeFormatter DISPLAY_KEY = new DateTimeFormatterBuilder()
            .appendPattern("dd-MM-")
            .appendValue(ChronoField.YEAR, 4)
            .toFormatter(Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter LEGACY_DATE_KEY = DateTimeFormatter.ISO_LOCAL_DATE;

    private PeriodKeys()
    {
    }

    public static String currentDailyKey(long now)
    {
        return format(LocalDate.ofInstant(Instant.ofEpochMilli(now), UTC));
    }

    public static String currentWeeklyKey(long now)
    {
        return weeklyKeyForDate(LocalDate.ofInstant(Instant.ofEpochMilli(now), UTC));
    }

    public static String normalizeDailyKey(String value, long now)
    {
        LocalDate parsed = parseDateKey(value);
        return parsed == null ? currentDailyKey(now) : format(parsed);
    }

    public static String normalizeWeeklyKey(String value, long now)
    {
        String raw = clean(value);
        if (raw.isBlank())
        {
            return currentWeeklyKey(now);
        }
        if (raw.equals(legacyIsoWeekKey(now)))
        {
            return currentWeeklyKey(now);
        }

        LocalDate parsed = parseDateKey(raw);
        if (parsed != null)
        {
            return weeklyKeyForDate(parsed);
        }

        parsed = parseLegacyIsoWeek(raw);
        return parsed == null ? currentWeeklyKey(now) : format(parsed);
    }

    public static boolean isCurrentDailyKey(String value, long now)
    {
        String raw = clean(value);
        if (raw.isBlank())
        {
            return false;
        }

        LocalDate parsed = parseDateKey(raw);
        return parsed != null && format(parsed).equals(currentDailyKey(now));
    }

    public static boolean isCurrentWeeklyKey(String value, long now)
    {
        String raw = clean(value);
        if (raw.isBlank())
        {
            return false;
        }
        if (raw.equals(legacyIsoWeekKey(now)))
        {
            return true;
        }

        LocalDate parsed = parseDateKey(raw);
        if (parsed != null)
        {
            return weeklyKeyForDate(parsed).equals(currentWeeklyKey(now));
        }

        parsed = parseLegacyIsoWeek(raw);
        return parsed != null && weeklyKeyForDate(parsed).equals(currentWeeklyKey(now));
    }

    public static String legacyIsoWeekKey(long now)
    {
        LocalDate date = LocalDate.ofInstant(Instant.ofEpochMilli(now), UTC);
        WeekFields weekFields = WeekFields.ISO;
        int year = date.get(weekFields.weekBasedYear());
        int week = date.get(weekFields.weekOfWeekBasedYear());
        return String.format(Locale.ROOT, "%04d-W%02d", year, week);
    }

    private static String weeklyKeyForDate(LocalDate date)
    {
        int diff = (date.getDayOfWeek().getValue() - DayOfWeek.WEDNESDAY.getValue() + 7) % 7;
        return format(date.minusDays(diff));
    }

    private static String format(LocalDate date)
    {
        return DISPLAY_KEY.format(date);
    }

    private static LocalDate parseDateKey(String value)
    {
        String raw = clean(value);
        if (raw.isBlank())
        {
            return null;
        }

        try
        {
            return LocalDate.parse(raw, DISPLAY_KEY);
        }
        catch (Exception ignored)
        {
        }

        try
        {
            return LocalDate.parse(raw, LEGACY_DATE_KEY);
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private static LocalDate parseLegacyIsoWeek(String value)
    {
        String raw = clean(value);
        if (raw.matches("\\d{4}-W\\d{2}") == false)
        {
            return null;
        }

        try
        {
            int year = Integer.parseInt(raw.substring(0, 4));
            int week = Integer.parseInt(raw.substring(6, 8));
            if (week < 1 || week > 53)
            {
                return null;
            }
            LocalDate jan4 = LocalDate.of(year, 1, 4);
            LocalDate weekOneMonday = jan4.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            return weekOneMonday.plusWeeks(week - 1L).plusDays(2);
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private static String clean(String value)
    {
        return value == null ? "" : value.trim();
    }
}
