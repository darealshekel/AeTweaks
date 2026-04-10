package com.miningtrackeraddon.sync;

import java.util.List;

public record AeternumLeaderboardSnapshot(
        String serverName,
        String objectiveTitle,
        long capturedAtMs,
        long totalDigs,
        List<AeternumLeaderboardEntry> entries
)
{
    public boolean isValid()
    {
        return this.serverName != null
                && this.serverName.isBlank() == false
                && this.entries != null
                && this.entries.isEmpty() == false
                && this.entries.stream().allMatch(AeternumLeaderboardEntry::isValid);
    }

    public boolean sameValues(AeternumLeaderboardSnapshot other)
    {
        if (other == null || this.totalDigs != other.totalDigs || this.entries.size() != other.entries.size())
        {
            return false;
        }

        for (int index = 0; index < this.entries.size(); index++)
        {
            if (this.entries.get(index).sameValues(other.entries.get(index)) == false)
            {
                return false;
            }
        }

        return true;
    }
}
