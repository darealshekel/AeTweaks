package com.mmm.sync;

public record SourceLeaderboardEntry(
        String username,
        long digs,
        int rank
)
{
    public boolean isValid()
    {
        return this.username != null
                && this.username.isBlank() == false
                && this.digs > 0L
                && this.rank > 0;
    }

    public boolean sameValues(SourceLeaderboardEntry other)
    {
        return other != null
                && this.username.equalsIgnoreCase(other.username)
                && this.digs == other.digs
                && this.rank == other.rank;
    }
}
