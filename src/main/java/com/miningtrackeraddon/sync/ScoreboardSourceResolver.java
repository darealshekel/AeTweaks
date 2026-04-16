package com.miningtrackeraddon.sync;

import com.miningtrackeraddon.storage.WorldSessionContext;
import java.util.Locale;

public final class ScoreboardSourceResolver
{
    private ScoreboardSourceResolver()
    {
    }

    public static String sourceKey(String candidate, WorldSessionContext.WorldInfo info)
    {
        if (info != null && info.id() != null && info.id().isBlank() == false)
        {
            return info.id();
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
        if (info != null && info.displayName() != null && info.displayName().isBlank() == false)
        {
            return info.displayName().trim();
        }

        if (candidate != null && candidate.isBlank() == false)
        {
            return candidate.trim();
        }

        if (info != null && info.id() != null && info.id().isBlank() == false)
        {
            return info.id().trim();
        }

        return "Unknown Source";
    }
}
