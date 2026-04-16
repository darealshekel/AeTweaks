package com.miningtrackeraddon.sync;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;

final class ScoreboardParser
{
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d[\\d,._ ]*(?:\\.\\d+)?)(?:\\s*([kKmMbBtT]))?");
    private static final Pattern USERNAME_PATTERN = Pattern.compile("(?i)(?:^|\\s|[#>\\[(])([A-Za-z0-9_]{3,16})(?:$|\\s|[\\])<:,.-])");
    private static final Pattern DECLARED_RANK_PATTERN = Pattern.compile("^(?:\\[)?#?(\\d{1,3})(?:\\]|[.):-])?\\s+");
    private static final List<String> PERSONAL_MARKERS = List.of("your", "you", "player", "personal", "my", "me", "self");
    private static final List<String> SERVER_TOTAL_MARKERS = List.of(
            "total",
            "server",
            "global",
            "overall",
            "community",
            "everyone",
            "all players",
            "blocks mined",
            "mined blocks",
            "network"
    );

    private ScoreboardParser()
    {
    }

    static Candidate parse(String currentUsername, String serverName, ScoreboardObjective objective, Collection<ScoreboardEntry> entries)
    {
        if (objective == null || entries == null || entries.isEmpty())
        {
            return null;
        }

        String objectiveTitle = clean(objective.getDisplayName().getString());
        List<ScoreboardLine> rawLines = entries.stream()
                .map(ScoreboardParser::toLine)
                .filter(line -> line != null)
                .toList();

        if (rawLines.isEmpty())
        {
            return null;
        }

        List<AeternumLeaderboardEntry> leaderboardEntries = parseEntries(rawLines);
        if (leaderboardEntries.size() < 3)
        {
            return null;
        }

        long totalDigs = parseTotalDigs(objectiveTitle, rawLines, leaderboardEntries);
        int confidence = computeConfidence(currentUsername, objectiveTitle, leaderboardEntries, totalDigs, rawLines);
        if (confidence <= 0)
        {
            return null;
        }

        return new Candidate(
                new AeternumLeaderboardSnapshot(serverName, objectiveTitle, System.currentTimeMillis(), totalDigs, leaderboardEntries),
                confidence,
                rawLines
        );
    }

    private static ScoreboardLine toLine(ScoreboardEntry entry)
    {
        String owner = clean(entry.owner());
        String raw = entry.display() != null ? entry.display().getString() : entry.name().getString();
        if (raw == null || raw.isBlank())
        {
            raw = owner;
        }

        if (raw == null || raw.isBlank())
        {
            return null;
        }

        String cleaned = clean(raw);
        if (cleaned.isBlank())
        {
            return null;
        }

        return new ScoreboardLine(
                owner,
                cleaned,
                extractNumber(cleaned),
                Math.max(0, entry.value()),
                extractDeclaredRank(cleaned),
                extractUsername(owner, cleaned)
        );
    }

    private static List<AeternumLeaderboardEntry> parseEntries(List<ScoreboardLine> lines)
    {
        Map<String, ParsedEntry> byUsername = new LinkedHashMap<>();

        for (ScoreboardLine line : lines)
        {
            if (isLikelyLeaderboardPlayerLine(line) == false)
            {
                continue;
            }

            String username = line.usernameCandidate();
            String key = username.toLowerCase(Locale.ROOT);
            ParsedEntry existing = byUsername.get(key);
            ParsedEntry next = new ParsedEntry(username, line.numericValue(), line.declaredRank());

            if (existing == null || next.isBetterThan(existing))
            {
                byUsername.put(key, next);
            }
        }

        List<ParsedEntry> ranked = byUsername.values().stream()
                .sorted(Comparator
                        .comparingInt((ParsedEntry entry) -> entry.rankHint() > 0 ? 0 : 1)
                        .thenComparingInt(entry -> entry.rankHint() > 0 ? entry.rankHint() : Integer.MAX_VALUE)
                        .thenComparingLong(ParsedEntry::digs).reversed()
                        .thenComparing(entry -> entry.username().toLowerCase(Locale.ROOT)))
                .toList();

        List<AeternumLeaderboardEntry> leaderboardEntries = new ArrayList<>();
        for (int index = 0; index < ranked.size(); index++)
        {
            ParsedEntry entry = ranked.get(index);
            int rank = entry.rankHint() > 0 ? entry.rankHint() : index + 1;
            leaderboardEntries.add(new AeternumLeaderboardEntry(entry.username(), entry.digs(), rank));
        }

        leaderboardEntries = leaderboardEntries.stream()
                .sorted(Comparator.comparingInt(AeternumLeaderboardEntry::rank))
                .toList();

        return leaderboardEntries;
    }

    private static long parseTotalDigs(String objectiveTitle, List<ScoreboardLine> rawLines, List<AeternumLeaderboardEntry> leaderboardEntries)
    {
        long bestExplicitTotal = 0L;
        for (int index = 0; index < rawLines.size(); index++)
        {
            ScoreboardLine line = rawLines.get(index);
            if (looksLikeServerTotalLine(objectiveTitle, line))
            {
                long value = extractValueNear(rawLines, index);
                if (value > 0L)
                {
                    bestExplicitTotal = Math.max(bestExplicitTotal, value);
                }
            }
        }

        if (bestExplicitTotal > 0L)
        {
            return bestExplicitTotal;
        }

        // Fallback: accept large non-player aggregate-like rows from objective score values.
        long objectiveFallback = 0L;
        for (ScoreboardLine line : rawLines)
        {
            if (line.usernameCandidate() != null)
            {
                continue;
            }
            if (PERSONAL_MARKERS.stream().anyMatch(line.lower()::contains))
            {
                continue;
            }
            if (line.numericValue() < 10_000L)
            {
                continue;
            }
            if (line.declaredRank() > 0)
            {
                continue;
            }
            objectiveFallback = Math.max(objectiveFallback, line.numericValue());
        }

        if (objectiveFallback > 0L)
        {
            return objectiveFallback;
        }

        // Final fallback: do not fabricate server total from partial leaderboard rows.
        return 0L;
    }

    private static boolean looksLikeServerTotalLine(String objectiveTitle, ScoreboardLine line)
    {
        String objectiveLower = objectiveTitle == null ? "" : objectiveTitle.toLowerCase(Locale.ROOT);
        String lower = line.lower();
        boolean objectiveIsDigsBoard = objectiveLower.contains("dig")
                || objectiveLower.contains("dug")
                || objectiveLower.contains("dugga");

        boolean lineLooksLikeTotal = SERVER_TOTAL_MARKERS.stream().anyMatch(lower::contains)
                && ((lower.contains("total") && (lower.contains("dig") || lower.contains("dug")))
                || lower.contains("server")
                || lower.contains("global")
                || lower.contains("overall")
                || objectiveIsDigsBoard);

        return lineLooksLikeTotal
                && line.usernameCandidate() == null
                && PERSONAL_MARKERS.stream().noneMatch(lower::contains);
    }

    private static int computeConfidence(String currentUsername,
                                         String objectiveTitle,
                                         List<AeternumLeaderboardEntry> leaderboardEntries,
                                         long totalDigs,
                                         List<ScoreboardLine> lines)
    {
        int confidence = 0;
        String titleLower = objectiveTitle == null ? "" : objectiveTitle.toLowerCase(Locale.ROOT);
        String usernameLower = currentUsername == null ? "" : currentUsername.toLowerCase(Locale.ROOT);

        if (titleLower.contains("dig") || titleLower.contains("dug"))
        {
            confidence += 50;
        }

        if (titleLower.contains("leader") || titleLower.contains("rank"))
        {
            confidence += 20;
        }

        if (leaderboardEntries.size() >= 3)
        {
            confidence += 20;
        }

        if (leaderboardEntries.size() >= 10)
        {
            confidence += 20;
        }

        if (leaderboardEntries.stream().anyMatch(entry -> entry.username().equalsIgnoreCase(currentUsername)))
        {
            confidence += 15;
        }

        if (totalDigs > 0L)
        {
            confidence += 15;
        }

        if (usernameLower.isBlank() == false
                && lines.stream().map(ScoreboardLine::lower).anyMatch(lower -> lower.contains(usernameLower)))
        {
            confidence += 10;
        }

        return confidence;
    }

    private static long extractValueNear(List<ScoreboardLine> lines, int index)
    {
        ScoreboardLine current = lines.get(index);
        if (current.numericValue() > 0L)
        {
            return current.numericValue();
        }

        if (index + 1 < lines.size())
        {
            ScoreboardLine next = lines.get(index + 1);
            if (next.usernameCandidate() == null && next.numericValue() > 0L)
            {
                return next.numericValue();
            }
        }

        if (index > 0)
        {
            ScoreboardLine previous = lines.get(index - 1);
            if (previous.usernameCandidate() == null && previous.numericValue() > 0L)
            {
                return previous.numericValue();
            }
        }

        return 0L;
    }

    private static String extractUsername(String owner, String cleaned)
    {
        Matcher matcher = USERNAME_PATTERN.matcher(cleaned);
        while (matcher.find())
        {
            String candidate = matcher.group(1);
            if (isMinecraftUsername(candidate))
            {
                return candidate;
            }
        }

        if (isMinecraftUsername(owner))
        {
            return owner;
        }

        return null;
    }

    private static boolean isMinecraftUsername(String value)
    {
        if (value == null || value.length() < 3 || value.length() > 16 || value.matches("[A-Za-z0-9_]+") == false)
        {
            return false;
        }

        String lower = value.toLowerCase(Locale.ROOT);
        return lower.equals("total") == false
                && lower.equals("player") == false
                && lower.equals("you") == false
                && lower.equals("your") == false
                && lower.equals("me") == false
                && lower.equals("self") == false
                && lower.equals("digs") == false
                && lower.equals("dug") == false
                && lower.equals("rank") == false;
    }

    private static int extractDeclaredRank(String cleaned)
    {
        Matcher matcher = DECLARED_RANK_PATTERN.matcher(cleaned);
        if (matcher.find() == false)
        {
            return 0;
        }

        try
        {
            return Integer.parseInt(matcher.group(1));
        }
        catch (NumberFormatException ignored)
        {
            return 0;
        }
    }

    private static boolean isLikelyLeaderboardPlayerLine(ScoreboardLine line)
    {
        String lower = line.lower();

        if (PERSONAL_MARKERS.stream().anyMatch(lower::contains))
        {
            return false;
        }

        if (SERVER_TOTAL_MARKERS.stream().anyMatch(lower::contains) && lower.contains("total"))
        {
            return false;
        }

        return line.usernameCandidate() != null && line.numericValue() > 0L;
    }

    private static String clean(String value)
    {
        return value == null
                ? ""
                : value
                        .replaceAll("§.", "")
                        .replace('\u00A0', ' ')
                        .replaceAll("\\s+", " ")
                        .trim();
    }

    private static long extractNumber(String value)
    {
        Matcher matcher = NUMBER_PATTERN.matcher(value);
        long best = 0L;

        while (matcher.find())
        {
            String numberPart = matcher.group(1);
            String suffixPart = matcher.group(2) == null ? "" : matcher.group(2).toLowerCase(Locale.ROOT);
            if (numberPart == null || numberPart.isBlank())
            {
                continue;
            }

            try
            {
                String normalized = numberPart
                        .replace(" ", "")
                        .replace(",", "")
                        .replace("_", "");
                if (normalized.isBlank())
                {
                    continue;
                }

                double parsedValue = Double.parseDouble(normalized);
                double multiplier = switch (suffixPart)
                {
                    case "k" -> 1_000D;
                    case "m" -> 1_000_000D;
                    case "b" -> 1_000_000_000D;
                    case "t" -> 1_000_000_000_000D;
                    default -> 1D;
                };

                long parsed = Math.max(0L, Math.round(parsedValue * multiplier));
                if (parsed > best)
                {
                    best = parsed;
                }
            }
            catch (NumberFormatException ignored)
            {
            }
        }

        return best;
    }

    record Candidate(AeternumLeaderboardSnapshot snapshot, int confidence, List<ScoreboardLine> lines)
    {
        String describeLines()
        {
            List<String> preview = new ArrayList<>();
            for (ScoreboardLine line : this.lines)
            {
                preview.add(line.cleaned() + " [owner=" + line.owner() + ", score=" + line.scoreValue() + ", user=" + line.usernameCandidate() + "]");
            }
            return String.join(" | ", preview);
        }
    }

    private record ParsedEntry(String username, long digs, int rankHint)
    {
        boolean isBetterThan(ParsedEntry other)
        {
            if (this.rankHint > 0 && other.rankHint > 0 && this.rankHint != other.rankHint)
            {
                return this.rankHint < other.rankHint;
            }

            if (this.digs != other.digs)
            {
                return this.digs > other.digs;
            }

            return this.rankHint > 0 && other.rankHint == 0;
        }
    }

    record ScoreboardLine(
            String owner,
            String cleaned,
            long inlineNumber,
            int scoreValue,
            int declaredRank,
            String usernameCandidate
    )
    {
        String lower()
        {
            return this.cleaned.toLowerCase(Locale.ROOT);
        }

        long numericValue()
        {
            if (this.usernameCandidate != null)
            {
                long explicitValue = extractExplicitPlayerValue(this.cleaned, this.usernameCandidate, this.declaredRank);
                if (explicitValue > 0L)
                {
                    return explicitValue;
                }

                return Math.max(0, this.scoreValue);
            }

            if (this.inlineNumber > 0L)
            {
                return this.inlineNumber;
            }

            return Math.max(0, this.scoreValue);
        }
    }

    private static long extractExplicitPlayerValue(String cleaned, String usernameCandidate, int declaredRank)
    {
        if (cleaned == null || cleaned.isBlank() || usernameCandidate == null || usernameCandidate.isBlank())
        {
            return 0L;
        }

        String candidate = cleaned;
        if (declaredRank > 0)
        {
            candidate = DECLARED_RANK_PATTERN.matcher(candidate).replaceFirst("");
        }

        candidate = candidate.replaceFirst("(?i)" + Pattern.quote(usernameCandidate), " ");
        return extractNumber(candidate);
    }
}
