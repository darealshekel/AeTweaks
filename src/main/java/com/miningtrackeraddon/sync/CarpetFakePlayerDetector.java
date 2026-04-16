package com.miningtrackeraddon.sync;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;

final class CarpetFakePlayerDetector
{
    private static final Pattern SYNTHETIC_BASE_WITH_SUFFIX = Pattern.compile(
        "^(?:tp|dig|load|placer|piston|bore|trencher|digsort|fish|bb|nwe)\\d{1,2}$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SYNTHETIC_SUFFIX_WITH_BASE = Pattern.compile(
        "^\\d{1,2}(?:load|digsort|wide)$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SYNTHETIC_DEFAULT_SKIN_WITH_SMALL_SUFFIX = Pattern.compile(
        "^(?:alex|steve)\\d$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SYNTHETIC_NUMERIC_ONLY = Pattern.compile("^\\d{1,3}$");

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
        if (playerList == null || playerList.isEmpty())
        {
            return usernames;
        }

        for (AeternumLeaderboardEntry entry : entries)
        {
            if (entry == null || entry.username() == null || entry.username().isBlank())
            {
                continue;
            }

            PlayerListEntry playerListEntry = findPlayerListEntry(playerList, entry.username());
            if (playerListEntry == null)
            {
                if (looksSynthetic(entry.username()))
                {
                    usernames.add(entry.username().toLowerCase(Locale.ROOT));
                }
                continue;
            }

            if (isFakePlayer(playerListEntry))
            {
                usernames.add(entry.username().toLowerCase(Locale.ROOT));
            }
        }

        return usernames;
    }

    /**
     * Returns true when the given tab-list entry looks like a Carpet fake player
     * rather than a real Mojang account.
     *
     * Two signals are checked (either alone is sufficient):
     *
     *   1. Null profile or null UUID — the classic signal that still appears in
     *      some older server/Carpet configurations.
     *
     *   2. Offline-mode UUID match — Carpet (1.20+) gives fake players a UUID
     *      derived from their name via the vanilla offline formula:
     *        UUID.nameUUIDFromBytes("OfflinePlayer:<name>")
     *      This produces a version-3 UUID.  Real Mojang accounts always carry a
     *      randomly-generated version-4 UUID, so this comparison has no false
     *      positives on an online-mode server.
     */
    private static boolean isFakePlayer(PlayerListEntry entry)
    {
        if (entry.getProfile() == null || entry.getProfile().getId() == null)
        {
            return true;
        }

        // Use the name exactly as the server stored it in the profile — this is
        // the same string Carpet passes to UUID.nameUUIDFromBytes when creating
        // the fake player, so the comparison is byte-for-byte identical.
        String profileName = entry.getProfile().getName();
        if (profileName == null || profileName.isBlank())
        {
            return true;
        }

        UUID profileId = entry.getProfile().getId();
        UUID expectedOfflineId = UUID.nameUUIDFromBytes(
            ("OfflinePlayer:" + profileName).getBytes(StandardCharsets.UTF_8)
        );
        return profileId.equals(expectedOfflineId);
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

    private static boolean looksSynthetic(String username)
    {
        if (username == null || username.isBlank())
        {
            return false;
        }

        return SYNTHETIC_NUMERIC_ONLY.matcher(username).matches()
            || SYNTHETIC_BASE_WITH_SUFFIX.matcher(username).matches()
            || SYNTHETIC_SUFFIX_WITH_BASE.matcher(username).matches()
            || SYNTHETIC_DEFAULT_SKIN_WITH_SMALL_SUFFIX.matcher(username).matches()
            || "h4ck0s".equalsIgnoreCase(username);
    }
}
