package com.miningtrackeraddon.sync;

import com.miningtrackeraddon.storage.WorldSessionContext;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.MinecraftClient;

public final class PlayerDigsParser
{
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d[\\d,._ ]*)");
    private static final List<String> PLAYER_MARKERS = List.of("your", "you", "player", "personal", "my", "me", "self");
    private static final List<String> GLOBAL_MARKERS = List.of("server", "global", "community", "overall", "everyone", "all players");

    private PlayerDigsParser()
    {
    }

    public static PlayerDigsModel parse(MinecraftClient client)
    {
        if (client == null || client.player == null)
        {
            return null;
        }

        String currentUsername = client.player.getGameProfile().getName();
        WorldSessionContext.WorldInfo worldInfo = WorldSessionContext.getCurrentWorldInfo();
        boolean recognizedServer = ScoreboardSourceResolver.isCanonicalAeternum(worldInfo);

        Candidate best = ScoreboardReader.readObjectives(client).stream()
                .map(snapshot -> parseObjective(currentUsername, snapshot))
                .filter(candidate -> candidate != null)
                .max(Comparator.comparingInt(Candidate::confidence))
                .orElse(null);

        if (best == null)
        {
            return null;
        }

        if (recognizedServer == false && best.confidence() < 80)
        {
            return null;
        }

        return best.model().withServer(
                ScoreboardSourceResolver.displayName(
                        worldInfo != null ? worldInfo.displayName() : "",
                        worldInfo
                )
        );
    }

    private static Candidate parseObjective(String currentUsername,
                                            ScoreboardReader.ObjectiveSnapshot snapshot)
    {
        if (snapshot.lines().isEmpty())
        {
            return null;
        }

        Candidate best = null;
        for (int index = 0; index < snapshot.lines().size(); index++)
        {
            ScoreboardReader.ScoreboardLine line = snapshot.lines().get(index);
            Candidate candidate = evaluateLine(currentUsername, snapshot, index, line);
            if (candidate != null && (best == null || candidate.confidence() > best.confidence()))
            {
                best = candidate;
            }
        }

        return best;
    }

    private static Candidate evaluateLine(String currentUsername,
                                          ScoreboardReader.ObjectiveSnapshot snapshot,
                                          int index,
                                          ScoreboardReader.ScoreboardLine line)
    {
        String titleLower = snapshot.title().toLowerCase(Locale.ROOT);
        String lineLower = line.cleaned().toLowerCase(Locale.ROOT);
        String ownerLower = line.owner().toLowerCase(Locale.ROOT);
        String usernameLower = currentUsername.toLowerCase(Locale.ROOT);

        long value = extractValue(line);
        if (value < 0L)
        {
            return null;
        }

        boolean objectiveLooksRelevant = titleLower.contains("dig") || titleLower.contains("dug");
        boolean lineLooksRelevant = lineLower.contains("dig") || lineLower.contains("dug");
        boolean isPlayerRow = ownerLower.equals(usernameLower) || lineLower.contains(usernameLower);
        boolean isExplicitPlayerLabel = PLAYER_MARKERS.stream().anyMatch(lineLower::contains)
                && (lineLooksRelevant || objectiveLooksRelevant);
        boolean isGlobalLine = GLOBAL_MARKERS.stream().anyMatch(lineLower::contains);

        if (isGlobalLine && isPlayerRow == false && isExplicitPlayerLabel == false)
        {
            return null;
        }

        if (isPlayerRow == false && isExplicitPlayerLabel == false)
        {
            return null;
        }

        if (value == 0L)
        {
            value = extractNeighborValue(snapshot.lines(), index);
        }

        if (value < 0L || (value == 0L && isExplicitPlayerLabel == false && isPlayerRow == false))
        {
            return null;
        }

        int confidence = 0;
        if (snapshot.sidebar())
        {
            confidence += 20;
        }
        if (objectiveLooksRelevant)
        {
            confidence += 30;
        }
        if (lineLooksRelevant)
        {
            confidence += 20;
        }
        if (isPlayerRow)
        {
            confidence += 100;
        }
        if (isExplicitPlayerLabel)
        {
            confidence += 80;
        }
        if (isGlobalLine)
        {
            confidence -= 60;
        }
        if (value > 0L)
        {
            confidence += 15;
        }

        if (confidence < 80)
        {
            return null;
        }

        return new Candidate(
                new PlayerDigsModel(currentUsername, value, System.currentTimeMillis(), "", snapshot.title()),
                confidence
        );
    }

    private static long extractNeighborValue(List<ScoreboardReader.ScoreboardLine> lines, int index)
    {
        if (index + 1 < lines.size())
        {
            long next = extractValue(lines.get(index + 1));
            if (next > 0L)
            {
                return next;
            }
        }

        if (index > 0)
        {
            long previous = extractValue(lines.get(index - 1));
            if (previous > 0L)
            {
                return previous;
            }
        }

        return -1L;
    }

    private static long extractValue(ScoreboardReader.ScoreboardLine line)
    {
        Matcher matcher = NUMBER_PATTERN.matcher(line.cleaned());
        long best = 0L;

        while (matcher.find())
        {
            String digits = matcher.group(1).replaceAll("[^0-9]", "");
            if (digits.isBlank())
            {
                continue;
            }

            try
            {
                long parsed = Long.parseLong(digits);
                if (parsed > best)
                {
                    best = parsed;
                }
            }
            catch (NumberFormatException ignored)
            {
            }
        }

        if (best > 0L)
        {
            return best;
        }

        return Math.max(0L, line.scoreValue());
    }

    private record Candidate(PlayerDigsModel model, int confidence) {}
}