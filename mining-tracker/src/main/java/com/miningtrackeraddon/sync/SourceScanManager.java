package com.miningtrackeraddon.sync;

import com.miningtrackeraddon.storage.WorldSessionContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;

public final class SourceScanManager
{
    private static final int COMPATIBLE_CONFIDENCE_THRESHOLD = 70;
    private static final int MAX_SAMPLE_LINES = 10;

    private SourceScanManager()
    {
    }

    public static SourceScanResult scan(MinecraftClient client)
    {
        WorldSessionContext.WorldInfo world = WorldSessionContext.getCurrentWorldInfo();
        if (client == null || client.player == null || client.world == null)
        {
            return empty(world);
        }

        Scoreboard scoreboard = client.world.getScoreboard();
        String username = client.player.getGameProfile().getName();
        String sourceDisplayName = ScoreboardSourceResolver.displayName(
                world != null ? world.displayName() : "",
                world
        );

        ScoreboardObjective sidebar = scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        ScoreboardParser.Candidate sidebarCandidate = ScoreboardParser.parse(
                username,
                sourceDisplayName,
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
                            sourceDisplayName,
                            objective,
                            scoreboard.getScoreboardEntries(objective)))
                    .filter(candidate -> candidate != null && candidate.snapshot().isValid())
                    .max(Comparator.comparingInt(ScoreboardParser.Candidate::confidence));

            chosen = bestCandidate.orElse(null);
        }

        PlayerDigsModel playerDigs = PlayerDigsParser.parse(client);
        boolean recognizedAeternum = ScoreboardSourceResolver.isCanonicalAeternum(world);
        boolean compatible = chosen != null
                && chosen.snapshot().isValid()
                && (recognizedAeternum || chosen.confidence() >= COMPATIBLE_CONFIDENCE_THRESHOLD);

        String scoreboardTitle = chosen != null
                ? chosen.snapshot().objectiveTitle()
                : playerDigs != null ? playerDigs.objectiveTitle() : null;

        long totalDigs = chosen != null ? chosen.snapshot().totalDigs() : 0L;
        long playerTotalDigs = resolvePlayerTotalDigs(username, chosen, playerDigs);

        List<String> sampleLines = chosen != null
                ? chosen.lines().stream()
                        .map(ScoreboardParser.ScoreboardLine::cleaned)
                        .limit(MAX_SAMPLE_LINES)
                        .toList()
                : List.of();

        List<String> detectedFields = detectFields(totalDigs, playerTotalDigs, chosen);
        String fingerprint = buildFingerprint(world, compatible, chosen, playerTotalDigs, detectedFields);

        return new SourceScanResult(
                compatible,
                sourceDisplayName,
                world != null ? world.kind() : "unknown",
                world != null ? world.host() : "",
                scoreboardTitle,
                sampleLines,
                detectedFields,
                chosen == null ? 0 : chosen.confidence(),
                totalDigs,
                playerTotalDigs,
                fingerprint,
                null
        );
    }

    private static SourceScanResult empty(WorldSessionContext.WorldInfo world)
    {
        String sourceDisplayName = ScoreboardSourceResolver.displayName(
                world != null ? world.displayName() : "",
                world
        );

        return new SourceScanResult(
                false,
                sourceDisplayName,
                world != null ? world.kind() : "unknown",
                world != null ? world.host() : "",
                null,
                List.of(),
                List.of(),
                0,
                0L,
                0L,
                buildEmptyFingerprint(world),
                null
        );
    }

    private static long resolvePlayerTotalDigs(String username,
                                               ScoreboardParser.Candidate candidate,
                                               PlayerDigsModel playerDigs)
    {
        if (playerDigs != null && playerDigs.isValid())
        {
            return playerDigs.totalDigs();
        }

        if (candidate == null || candidate.snapshot() == null)
        {
            return 0L;
        }

        return candidate.snapshot().entries().stream()
                .filter(entry -> entry.username().equalsIgnoreCase(username))
                .findFirst()
                .map(AeternumLeaderboardEntry::digs)
                .orElse(0L);
    }

    private static List<String> detectFields(long totalDigs,
                                             long playerTotalDigs,
                                             ScoreboardParser.Candidate candidate)
    {
        LinkedHashSet<String> fields = new LinkedHashSet<>();

        if (candidate != null && candidate.snapshot() != null && candidate.snapshot().entries().isEmpty() == false)
        {
            fields.add("leaderboard_entries");
        }

        if (totalDigs > 0L)
        {
            fields.add("total_digs");
        }

        if (playerTotalDigs > 0L)
        {
            fields.add("player_total_digs");
        }

        if (candidate != null
                && candidate.snapshot() != null
                && candidate.snapshot().objectiveTitle() != null
                && candidate.snapshot().objectiveTitle().isBlank() == false)
        {
            fields.add("scoreboard_title");
        }

        return new ArrayList<>(fields);
    }

    private static String buildEmptyFingerprint(WorldSessionContext.WorldInfo world)
    {
        return ScoreboardSourceResolver.sourceKey(
                world != null ? world.displayName() : "",
                world
        ) + "|empty";
    }

    private static String buildFingerprint(WorldSessionContext.WorldInfo world,
                                           boolean compatible,
                                           ScoreboardParser.Candidate candidate,
                                           long playerTotalDigs,
                                           List<String> detectedFields)
    {
        StringBuilder builder = new StringBuilder();

        builder.append(ScoreboardSourceResolver.sourceKey(
                        world != null ? world.displayName() : "",
                        world))
                .append('|')
                .append(world != null && world.kind() != null ? world.kind() : "unknown")
                .append('|')
                .append(world != null && world.host() != null ? world.host().toLowerCase(Locale.ROOT) : "")
                .append('|')
                .append(compatible)
                .append('|')
                .append(candidate == null ? "" : candidate.snapshot().objectiveTitle())
                .append('|')
                .append(candidate == null ? 0L : candidate.snapshot().totalDigs())
                .append('|')
                .append(playerTotalDigs)
                .append('|')
                .append(String.join(",", detectedFields));

        if (candidate != null && candidate.snapshot() != null)
        {
            candidate.snapshot().entries().stream()
                    .sorted(Comparator.comparingInt(AeternumLeaderboardEntry::rank))
                    .forEach(entry -> builder.append('|')
                            .append(entry.username().toLowerCase(Locale.ROOT))
                            .append(':')
                            .append(entry.digs())
                            .append(':')
                            .append(entry.rank()));
        }

        return builder.toString();
    }
}