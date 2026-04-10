package com.miningtrackeraddon.sync;

import com.miningtrackeraddon.storage.WorldSessionContext;
import java.util.Locale;

public final class ScoreboardSourceResolver
{
    private static final String CANONICAL_AETERNUM_KEY = "aeternum";
    private static final String CANONICAL_REDTECH_KEY = "redtech";

    private ScoreboardSourceResolver()
    {
    }

    public static boolean isCanonicalAeternum(WorldSessionContext.WorldInfo info)
    {
        if (info == null)
        {
            return false;
        }

        return containsIgnoreCase(info.id(), "aeternum")
                || containsIgnoreCase(info.displayName(), "aeternum")
                || containsIgnoreCase(info.host(), "aeternum")
                || containsIgnoreCase(info.host(), "aeternumsmp");
    }

    public static boolean isCanonicalRedTech(WorldSessionContext.WorldInfo info)
    {
        if (info == null)
        {
            return false;
        }

        return containsIgnoreCase(info.id(), "redtech")
                || containsIgnoreCase(info.displayName(), "redtech")
                || containsIgnoreCase(info.host(), "redtech");
    }

    public static String sourceKey(String candidate, WorldSessionContext.WorldInfo info)
    {
        if (isCanonicalAeternum(info))
        {
            return CANONICAL_AETERNUM_KEY;
        }

        if (isCanonicalRedTech(info))
        {
            return CANONICAL_REDTECH_KEY;
        }

        if (info != null && info.host() != null && info.host().isBlank() == false)
        {
            return info.host().trim().toLowerCase(Locale.ROOT);
        }

        if (info != null && info.id() != null && info.id().isBlank() == false)
        {
            return info.id().trim().toLowerCase(Locale.ROOT);
        }

        if (candidate != null && candidate.isBlank() == false)
        {
            return candidate.trim().toLowerCase(Locale.ROOT);
        }

        if (info != null && info.displayName() != null && info.displayName().isBlank() == false)
        {
            return info.displayName().trim().toLowerCase(Locale.ROOT);
        }

        return "unknown-source";
    }

    public static String displayName(String candidate, WorldSessionContext.WorldInfo info)
    {
        if (isCanonicalAeternum(info))
        {
            return "Aeternum";
        }

        if (isCanonicalRedTech(info))
        {
            return "RedTech";
        }

        if (info != null && info.host() != null && info.host().isBlank() == false)
        {
            return info.host().trim();
        }

        if (candidate != null && candidate.isBlank() == false)
        {
            return candidate.trim();
        }

        if (info != null && info.displayName() != null && info.displayName().isBlank() == false)
        {
            return info.displayName().trim();
        }

        if (info != null && info.id() != null && info.id().isBlank() == false)
        {
            return info.id().trim();
        }

        return "Unknown Source";
    }

    private static boolean containsIgnoreCase(String value, String needle)
    {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }
}
