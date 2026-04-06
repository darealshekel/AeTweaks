package com.miningtrackeraddon.mmm;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import com.miningtrackeraddon.MiningTrackerAddon;
import com.miningtrackeraddon.mmm.data.MmmLeaderboardEntry;
import com.miningtrackeraddon.mmm.data.MmmStatus;
import com.miningtrackeraddon.mmm.data.MmmViewState;
import com.miningtrackeraddon.mmm.net.MmmLeaderboardService;

import net.minecraft.client.MinecraftClient;

public final class MmmManager
{
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final MmmLeaderboardService SERVICE = new MmmLeaderboardService();

    private static volatile MmmStatus status = MmmStatus.UNAVAILABLE;
    private static volatile String statusText = "Join a world to load MMM.";
    private static volatile String currentUsername = "";
    private static volatile boolean multiplayerContext;
    private static volatile long lastUpdatedAt;
    private static volatile List<MmmLeaderboardEntry> cachedEntries = List.of();
    private static volatile MmmLeaderboardEntry matchedEntry;
    private static volatile CompletableFuture<Void> inFlightRequest;
    private static volatile boolean waitingForPlayer = true;

    private MmmManager()
    {
    }

    public static void onWorldJoin(MinecraftClient client)
    {
        waitingForPlayer = true;
        refreshForClient(client, false);
    }

    public static void onWorldLeave()
    {
        currentUsername = "";
        multiplayerContext = false;
        matchedEntry = null;
        status = MmmStatus.UNAVAILABLE;
        statusText = "Join a world to load MMM.";
        waitingForPlayer = true;
    }

    public static void onClientTick(MinecraftClient client)
    {
        if (client == null || client.world == null)
        {
            return;
        }

        String username = resolveCurrentUsername(client);
        if (username.isBlank())
        {
            if (status != MmmStatus.LOADING)
            {
                status = MmmStatus.UNAVAILABLE;
                statusText = "Player is still loading...";
            }
            return;
        }

        if (waitingForPlayer || !username.equals(currentUsername))
        {
            refreshForClient(client, false);
        }
    }

    public static void refreshForClient(MinecraftClient client, boolean forceFetch)
    {
        multiplayerContext = isMultiplayer(client);
        String username = resolveCurrentUsername(client);
        currentUsername = username;

        if (username.isBlank())
        {
            matchedEntry = null;
            status = MmmStatus.UNAVAILABLE;
            statusText = "Player unavailable. MMM will load automatically in-game.";
            waitingForPlayer = true;
            return;
        }

        waitingForPlayer = false;

        if (!forceFetch && isCacheFresh())
        {
            matchCurrentUser(username);
            return;
        }

        if (inFlightRequest != null && !inFlightRequest.isDone())
        {
            status = MmmStatus.LOADING;
            statusText = "Loading MMM leaderboard...";
            return;
        }

        status = MmmStatus.LOADING;
        statusText = "Loading MMM leaderboard...";
        matchedEntry = null;

        inFlightRequest = CompletableFuture.supplyAsync(() -> {
            try
            {
                return SERVICE.fetchEntries();
            }
            catch (Exception exception)
            {
                throw new RuntimeException(exception);
            }
        }).thenAccept(entries -> {
            cachedEntries = entries;
            lastUpdatedAt = System.currentTimeMillis();
            matchCurrentUser(username);
        }).exceptionally(throwable -> {
            MiningTrackerAddon.LOGGER.warn("Failed to refresh MMM leaderboard", throwable);
            matchedEntry = null;
            status = MmmStatus.ERROR;
            statusText = "MMM is unavailable right now.";
            return null;
        });
    }

    public static MmmViewState getViewState()
    {
        return new MmmViewState(status, currentUsername, statusText, multiplayerContext, matchedEntry, lastUpdatedAt);
    }

    public static String resolveCurrentUsername(MinecraftClient client)
    {
        if (client == null || client.player == null)
        {
            return "";
        }

        String name = client.player.getGameProfile().getName();
        return name == null ? "" : name.trim();
    }

    private static boolean isMultiplayer(MinecraftClient client)
    {
        return client != null && client.getCurrentServerEntry() != null;
    }

    private static boolean isCacheFresh()
    {
        return !cachedEntries.isEmpty() && (System.currentTimeMillis() - lastUpdatedAt) < CACHE_TTL.toMillis();
    }

    private static void matchCurrentUser(String username)
    {
        if (username == null || username.isBlank())
        {
            matchedEntry = null;
            status = MmmStatus.UNAVAILABLE;
            statusText = "Player unavailable. MMM will load automatically in-game.";
            return;
        }

        List<MmmLeaderboardEntry> exactMatches = cachedEntries.stream()
                .filter(entry -> entry.username().equals(username))
                .toList();

        if (exactMatches.size() == 1)
        {
            matchedEntry = exactMatches.getFirst();
            status = MmmStatus.READY;
            statusText = "Matched Manual Mining Leaderboards entry.";
            return;
        }

        if (exactMatches.size() > 1)
        {
            matchedEntry = null;
            status = MmmStatus.ERROR;
            statusText = "Multiple exact MMM matches were found.";
            return;
        }

        List<MmmLeaderboardEntry> caseInsensitiveMatches = cachedEntries.stream()
                .filter(entry -> entry.username().toLowerCase(Locale.ROOT).equals(username.toLowerCase(Locale.ROOT)))
                .toList();

        if (caseInsensitiveMatches.size() == 1)
        {
            matchedEntry = caseInsensitiveMatches.getFirst();
            status = MmmStatus.READY;
            statusText = "Matched MMM entry using case-insensitive fallback.";
            return;
        }

        matchedEntry = null;
        status = MmmStatus.NOT_FOUND;
        statusText = "No MMM leaderboard entry was found for " + username + ".";
    }
}
