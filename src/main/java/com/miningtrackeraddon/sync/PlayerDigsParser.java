package com.miningtrackeraddon.sync;

import com.miningtrackeraddon.MiningTrackerAddon;
import com.miningtrackeraddon.config.Configs;
import com.miningtrackeraddon.storage.WorldSessionContext;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.MinecraftClient;

public final class PlayerDigsParser
{
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(?<![A-Za-z0-9_])(\\d[\\d,._ ]*)(?![A-Za-z0-9_])");
    private static final Pattern RANK_PREFIX_PATTERN = Pattern.compile("(?i)^\\s*(?:#|\\[)?\\d{1,3}(?:\\]|[.):-])\\s+([A-Za-z0-9_]{3,16})\\b");
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
        Candidate best = ScoreboardReader.readObjectives(client).stream()
                .map(snapshot -> parseObjective(currentUsername, snapshot))
                .filter(candidate -> candidate != null)
                .max(Comparator.comparingInt(Candidate::confidence))
                .orElse(null);

        if (best == null || best.confidence() < 80)
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

        if (isLikelyRankOnlyLine(line.cleaned(), usernameLower, value)
                || isLikelyLowQualityPersonalValue(snapshot.title(), line.cleaned(), usernameLower, value))
        {
            debugLine(snapshot.title(), line, false, "rejected-rank-like-line");
            return null;
        }

        if (value < 0L || (value == 0L && isExplicitPlayerLabel == false && isPlayerRow == false))
        {
            debugLine(snapshot.title(), line, false, "rejected-no-value");
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
            debugLine(snapshot.title(), line, false, "rejected-low-confidence-" + confidence);
            return null;
        }

        debugLine(snapshot.title(), line, true, "accepted-confidence-" + confidence + "-value-" + value);
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

        String cleanedLower = line.cleaned().toLowerCase(Locale.ROOT);
        String ownerLower = line.owner().toLowerCase(Locale.ROOT);
        // A plain username line often carries list-position/rank in scoreValue, not total digs.
        // Ignore score fallback when there is no numeric text and no obvious digs marker.
        if (cleanedLower.matches("[a-z0-9_]{3,16}")
                || cleanedLower.equals(ownerLower))
        {
            return -1L;
        }

        return Math.max(0L, line.scoreValue());
    }

    private static boolean isLikelyRankOnlyLine(String cleaned, String usernameLower, long numericValue)
    {
        if (cleaned == null || cleaned.isBlank())
        {
            return false;
        }

        String lower = cleaned.toLowerCase(Locale.ROOT);
        if (lower.equals(usernameLower) && numericValue > 0L && numericValue <= 100L)
        {
            return true;
        }

        Matcher rankMatcher = RANK_PREFIX_PATTERN.matcher(cleaned);
        if (rankMatcher.find())
        {
            String rankedUser = rankMatcher.group(1) == null ? "" : rankMatcher.group(1).toLowerCase(Locale.ROOT);
            if (rankedUser.equals(usernameLower) && numericValue > 0L && numericValue <= 1_000L)
            {
                return true;
            }
        }

        return false;
    }

    private static boolean isLikelyLowQualityPersonalValue(String objectiveTitle, String cleaned, String usernameLower, long numericValue)
    {
        if (numericValue <= 0L)
        {
            return false;
        }

        String text = cleaned == null ? "" : cleaned;
        String lower = text.toLowerCase(Locale.ROOT);
        String objectiveLower = objectiveTitle == null ? "" : objectiveTitle.toLowerCase(Locale.ROOT);
        boolean mentionsUsername = usernameLower != null && usernameLower.isBlank() == false && lower.contains(usernameLower);
        boolean hasDigContext = lower.contains("dig")
                || lower.contains("dug")
                || objectiveLower.contains("dig")
                || objectiveLower.contains("dug");
        boolean hasTotalContext = lower.contains("total") || lower.contains("personal") || lower.contains("you");

        if (mentionsUsername == false)
        {
            return false;
        }

        // Reject tiny username-attached values unless the line explicitly looks like a personal total.
        if (numericValue <= 1000L && hasDigContext == false && hasTotalContext == false)
        {
            return true;
        }

        return false;
    }

    private static void debugLine(String objectiveTitle, ScoreboardReader.ScoreboardLine line, boolean accepted, String reason)
    {
        if (Configs.Generic.WEBSITE_SYNC_DEBUG.getBooleanValue() == false)
        {
            return;
        }

        String lower = line.cleaned().toLowerCase(Locale.ROOT);
        boolean mentionsPlayerMarker = PLAYER_MARKERS.stream().anyMatch(lower::contains);
        if (!mentionsPlayerMarker && line.cleaned().length() > 64)
        {
            // Keep debug concise; long non-personal lines are usually noise for this parser.
            return;
        }

        MiningTrackerAddon.LOGGER.info(
                "[AET_DEBUG] player-digs-line objective={} owner={} text={} scoreValue={} decision={} reason={}",
                objectiveTitle,
                line.owner(),
                line.cleaned(),
                line.scoreValue(),
                accepted ? "accepted" : "rejected",
                reason
        );
    }

    private record Candidate(PlayerDigsModel model, int confidence) {}
}
