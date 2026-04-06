package com.miningtrackeraddon.mmm.data;

public record MmmViewState(
        MmmStatus status,
        String currentUsername,
        String statusText,
        boolean multiplayerContext,
        MmmLeaderboardEntry matchedEntry,
        long lastUpdatedAt)
{
}
