package com.miningtrackeraddon.sync;

public record AeternumLeaderboardEntry(
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

    public boolean sameValues(AeternumLeaderboardEntry other)
    {
        return other != null
                && this.username.equalsIgnoreCase(other.username)
                && this.digs == other.digs
                && this.rank == other.rank;
    }
}
