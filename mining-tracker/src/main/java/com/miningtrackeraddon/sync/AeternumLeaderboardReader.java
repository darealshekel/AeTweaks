package com.miningtrackeraddon.sync;

import com.miningtrackeraddon.MiningTrackerAddon;
import com.miningtrackeraddon.config.Configs;
import com.miningtrackeraddon.storage.WorldSessionContext;
import java.util.Comparator;
import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;

public final class AeternumLeaderboardReader
{
    private static final long DEBUG_LOG_INTERVAL_MS = 10_000L;
    private static final int FALLBACK_AETERNUM_CONFIDENCE = 70;
    private static long lastDebugLogMs;

    private AeternumLeaderboardReader()
    {
    }

    public static AeternumLeaderboardSnapshot read(MinecraftClient client)
    {
        if (client == null || client.world == null || client.player == null)
        {
            return null;
        }

        Scoreboard scoreboard = client.world.getScoreboard();
        String username = client.player.getGameProfile().getName();
        WorldSessionContext.WorldInfo worldInfo = WorldSessionContext.getCurrentWorldInfo();
        String detectedServerName = ScoreboardSourceResolver.displayName(
                worldInfo != null ? worldInfo.displayName() : "",
                worldInfo
        );

        ScoreboardObjective sidebar = scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        ScoreboardParser.Candidate sidebarCandidate = ScoreboardParser.parse(
                username,
                detectedServerName,
                sidebar,
                sidebar == null ? java.util.List.of() : scoreboard.getScoreboardEntries(sidebar)
        );

        ScoreboardParser.Candidate chosen = null;

        if (sidebarCandidate != null && sidebarCandidate.snapshot().isValid())
        {
            chosen = sidebarCandidate;
        }
        else
        {
            Optional<ScoreboardParser.Candidate> bestCandidate = scoreboard.getObjectives().stream()
                    .map(objective -> ScoreboardParser.parse(
                            username,
                            detectedServerName,
                            objective,
                            scoreboard.getScoreboardEntries(objective)))
                    .filter(candidate -> candidate != null && candidate.snapshot().isValid())
                    .max(Comparator.comparingInt(ScoreboardParser.Candidate::confidence));

            chosen = bestCandidate.orElse(null);
        }

        boolean recognizedServer = ScoreboardSourceResolver.isCanonicalAeternum(worldInfo);

        if (chosen == null)
        {
            debug("Skipping leaderboard sync because no valid scoreboard candidate was found.");
            return null;
        }

        if (recognizedServer == false && chosen.confidence() < FALLBACK_AETERNUM_CONFIDENCE)
        {
            debug("Skipping leaderboard sync because server detection is unknown and confidence={} objective={}",
                    chosen.confidence(),
                    chosen.snapshot().objectiveTitle());
            return null;
        }

        if (chosen.snapshot().isValid() == false)
        {
            debug("Aeternum leaderboard not parsed. Objectives={}", scoreboard.getObjectives().stream()
                    .map(objective -> objective.getDisplayName().getString())
                    .toList());
            return null;
        }

        debug(
                "Aeternum leaderboard parsed from '{}' confidence={} recognizedServer={} entries={} total={} rows={}",
                chosen.snapshot().objectiveTitle(),
                chosen.confidence(),
                recognizedServer,
                chosen.snapshot().entries().size(),
                chosen.snapshot().totalDigs(),
                chosen.describeLines()
        );

        return new AeternumLeaderboardSnapshot(
                ScoreboardSourceResolver.displayName(
                        worldInfo != null ? worldInfo.displayName() : detectedServerName,
                        worldInfo
                ),
                chosen.snapshot().objectiveTitle(),
                chosen.snapshot().capturedAtMs(),
                chosen.snapshot().totalDigs(),
                chosen.snapshot().entries()
        );
    }

    public static boolean isAeternumServer()
    {
        return ScoreboardSourceResolver.isCanonicalAeternum(WorldSessionContext.getCurrentWorldInfo());
    }

    private static void debug(String message, Object... args)
    {
        if (Configs.Generic.WEBSITE_SYNC_DEBUG.getBooleanValue() == false)
        {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastDebugLogMs < DEBUG_LOG_INTERVAL_MS)
        {
            return;
        }

        lastDebugLogMs = now;
        MiningTrackerAddon.LOGGER.info(message, args);
    }
}