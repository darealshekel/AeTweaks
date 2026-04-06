package com.miningtrackeraddon.mmm.data;

import java.util.Collections;
import java.util.List;

public record MmmLeaderboardEntry(
        int rank,
        String username,
        long totalDigs,
        String countryFlag,
        String flagIconUrl,
        List<MmmDigBreakdown> breakdown)
{
    public MmmLeaderboardEntry
    {
        countryFlag = countryFlag == null ? "" : countryFlag;
        flagIconUrl = flagIconUrl == null ? "" : flagIconUrl;
        breakdown = breakdown == null ? List.of() : List.copyOf(breakdown);
    }

    public List<MmmDigBreakdown> getBreakdown()
    {
        return Collections.unmodifiableList(this.breakdown);
    }
}
