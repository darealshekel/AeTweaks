package com.miningtrackeraddon.sync;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;

final class CarpetFakePlayerDetector
{
    private CarpetFakePlayerDetector()
    {
    }

    static Set<String> findLikelyFakeUsernames(MinecraftClient client, List<AeternumLeaderboardEntry> entries)
    {
        Set<String> usernames = new LinkedHashSet<>();
        if (client == null || client.getNetworkHandler() == null || entries == null || entries.isEmpty())
        {
            return usernames;
        }

        Collection<PlayerListEntry> playerList = client.getNetworkHandler().getPlayerList();

        for (AeternumLeaderboardEntry entry : entries)
        {
            if (entry == null || entry.username() == null || entry.username().isBlank())
            {
                continue;
            }

            PlayerListEntry playerListEntry = findPlayerListEntry(playerList, entry.username());

            if (playerListEntry == null)
            {
                usernames.add(entry.username().toLowerCase(Locale.ROOT));
            }
        }

        return usernames;
    }

    private static PlayerListEntry findPlayerListEntry(Collection<PlayerListEntry> playerList, String username)
    {
        for (PlayerListEntry entry : playerList)
        {
            if (entry != null
                    && entry.getProfile() != null
                    && entry.getProfile().getName() != null
                    && entry.getProfile().getName().equalsIgnoreCase(username))
            {
                return entry;
            }
        }

        return null;
    }
}