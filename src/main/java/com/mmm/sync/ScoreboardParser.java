package com.mmm.sync;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.mmm.util.MmmDebugLogger;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardEntry;

final class ScoreboardParser
{
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d[\\d,._ ]*(?:\\.\\d+)?)(?:\\s*([kKmMbBtT]))?");
    private static final Pattern USERNAME_PATTERN = Pattern.compile("(?i)(?:^|\\s|[#>\\[(])([A-Za-z0-9_]{3,16})(?:$|\\s|[\\])<:,.-])");
    private static final Pattern DECLARED_RANK_PATTERN = Pattern.compile("^(?:\\[)?#?(\\d{1,3})(?:\\]|[.):-])?\\s+");
    private static final long PARSE_DEBUG_LOG_INTERVAL_MS = 30_000L;
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
    private static final List<String> TOTAL_OBJECTIVE_MARKERS = List.of(
            "sum",
            "total",
            "combinedblocks",
            "combined blocks",
            "combined_blocks",
            "combined-blocks",
            "blocks mined",
            "mined blocks"
    );
    private static final List<String> DIG_OBJECTIVE_MARKERS = List.of(
            "dig",
            "dug",
            "dugga"
    );
    private static final List<String> PARTIAL_OBJECTIVE_MARKERS = List.of(
            "pickuses",
            "pick uses",
            "pick_uses",
            "pick-uses",
            "pickaxeuses",
            "pickaxe uses",
            "pickaxe_uses",
            "pickaxe-uses",
            "axeuses",
            "axe uses",
            "axe_uses",
            "axe-uses",
            "shoveluses",
            "shovel uses",
            "shovel_uses",
            "shovel-uses"
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

        String objectiveName = clean(objective.getName());
        String displayTitle = clean(objective.getDisplayName().getString());
        String objectiveContext = objectiveContext(objectiveName, displayTitle);
        int objectivePriority = objectivePriority(objectiveContext);
        if (objectivePriority <= 0)
        {
            return null;
        }

        String objectiveTitle = preferredObjectiveTitle(objectiveName, displayTitle);
        List<ScoreboardLine> rawLines = entries.stream()
                .map(ScoreboardParser::toLine)
                .filter(line -> line != null)
                .toList();

        if (rawLines.isEmpty())
        {
            return null;
        }

        List<SourceLeaderboardEntry> leaderboardEntries = parseEntries(rawLines);
        if (leaderboardEntries.size() < 3)
        {
            return null;
        }

        long totalDigs = parseTotalDigs(objectiveContext, rawLines, leaderboardEntries);
        int confidence = computeConfidence(currentUsername, objectiveContext, leaderboardEntries, totalDigs, rawLines);
        if (confidence <= 0)
        {
            return null;
        }

        return new Candidate(
                new SourceLeaderboardSnapshot(serverName, objectiveTitle, System.currentTimeMillis(), totalDigs, leaderboardEntries),
                confidence,
                objectivePriority,
                rawLines
        );
    }

    static Candidate parseCombinedToolUsage(String currentUsername, String serverName, Collection<ObjectiveRows> objectives)
    {
        if (objectives == null || objectives.isEmpty())
        {
            return null;
        }

        Map<String, ToolUsageEntry> byUsername = new LinkedHashMap<>();
        Set<ToolKind> detectedKinds = new LinkedHashSet<>();
        List<ScoreboardLine> sampleLines = new ArrayList<>();

        for (ObjectiveRows rows : objectives)
        {
            if (rows == null || rows.objective() == null || rows.entries() == null || rows.entries().isEmpty())
            {
                continue;
            }

            String objectiveName = clean(rows.objective().getName());
            String displayTitle = clean(rows.objective().getDisplayName().getString());
            String objectiveContext = objectiveContext(objectiveName, displayTitle);
            ToolKind toolKind = toolKind(objectiveContext);
            if (toolKind == ToolKind.NONE)
            {
                continue;
            }

            List<ScoreboardLine> rawLines = rows.entries().stream()
                    .map(ScoreboardParser::toLine)
                    .filter(line -> line != null)
                    .toList();
            if (rawLines.isEmpty())
            {
                continue;
            }

            List<SourceLeaderboardEntry> parsedEntries = parseEntries(rawLines);
            if (parsedEntries.isEmpty())
            {
                continue;
            }

            detectedKinds.add(toolKind);
            if (sampleLines.size() < 30)
            {
                sampleLines.addAll(rawLines.stream().limit(Math.max(0, 30 - sampleLines.size())).toList());
            }

            for (SourceLeaderboardEntry entry : parsedEntries)
            {
                if (entry == null || entry.username() == null || entry.username().isBlank() || entry.digs() <= 0L)
                {
                    continue;
                }

                String key = entry.username().toLowerCase(Locale.ROOT);
                ToolUsageEntry usageEntry = byUsername.computeIfAbsent(key, ignored -> new ToolUsageEntry(entry.username()));
                usageEntry.putMax(toolKind, entry.digs());
            }
        }

        if (byUsername.isEmpty() || detectedKinds.isEmpty())
        {
            return null;
        }

        List<ToolUsageEntry> ranked = byUsername.values().stream()
                .filter(entry -> entry.total() > 0L)
                .sorted(Comparator
                        .comparingLong(ToolUsageEntry::total).reversed()
                        .thenComparing(entry -> entry.username().toLowerCase(Locale.ROOT)))
                .toList();
        if (ranked.isEmpty())
        {
            return null;
        }

        List<SourceLeaderboardEntry> combinedEntries = new ArrayList<>();
        long totalDigs = 0L;
        for (int index = 0; index < ranked.size(); index++)
        {
            ToolUsageEntry entry = ranked.get(index);
            long total = entry.total();
            totalDigs += total;
            combinedEntries.add(new SourceLeaderboardEntry(entry.username(), total, index + 1));
        }

        int confidence = 55;
        confidence += Math.min(30, detectedKinds.size() * 10);
        if (combinedEntries.size() >= 3)
        {
            confidence += 15;
        }
        if (combinedEntries.size() >= 10)
        {
            confidence += 10;
        }
        if (currentUsername != null
                && currentUsername.isBlank() == false
                && combinedEntries.stream().anyMatch(entry -> entry.username().equalsIgnoreCase(currentUsername)))
        {
            confidence += 10;
        }
        if (totalDigs > 0L)
        {
            confidence += 10;
        }

        return new Candidate(
                new SourceLeaderboardSnapshot(
                        serverName,
                        combinedToolUsageTitle(detectedKinds),
                        System.currentTimeMillis(),
                        totalDigs,
                        combinedEntries),
                confidence,
                60,
                sampleLines
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

    private static List<SourceLeaderboardEntry> parseEntries(List<ScoreboardLine> lines)
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

        List<SourceLeaderboardEntry> leaderboardEntries = new ArrayList<>();
        for (int index = 0; index < ranked.size(); index++)
        {
            ParsedEntry entry = ranked.get(index);
            int rank = entry.rankHint() > 0 ? entry.rankHint() : index + 1;
            leaderboardEntries.add(new SourceLeaderboardEntry(entry.username(), entry.digs(), rank));
        }

        leaderboardEntries = leaderboardEntries.stream()
                .sorted(Comparator.comparingInt(SourceLeaderboardEntry::rank))
                .toList();

        return leaderboardEntries;
    }

    private static long parseTotalDigs(String objectiveTitle, List<ScoreboardLine> rawLines, List<SourceLeaderboardEntry> leaderboardEntries)
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
        String objectiveLower = normalizedObjectiveText(objectiveTitle);
        String lower = line.lower();
        boolean objectiveIsDigsBoard = objectivePriority(objectiveLower) >= 70;

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
                                         List<SourceLeaderboardEntry> leaderboardEntries,
                                         long totalDigs,
                                         List<ScoreboardLine> lines)
    {
        int confidence = 0;
        String titleLower = normalizedObjectiveText(objectiveTitle);
        String usernameLower = currentUsername == null ? "" : currentUsername.toLowerCase(Locale.ROOT);

        if (containsTotalObjectiveMarker(titleLower))
        {
            confidence += 70;
        }
        else if (containsAnyObjectiveMarker(titleLower, DIG_OBJECTIVE_MARKERS))
        {
            confidence += 50;
        }
        else if (containsAnyObjectiveMarker(titleLower, PARTIAL_OBJECTIVE_MARKERS))
        {
            confidence += 35;
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

    static int objectivePriority(String value)
    {
        String lower = normalizedObjectiveText(value);
        if (containsTotalObjectiveMarker(lower))
        {
            return 90;
        }
        if (containsAnyObjectiveMarker(lower, DIG_OBJECTIVE_MARKERS))
        {
            return 70;
        }
        if (containsAnyObjectiveMarker(lower, PARTIAL_OBJECTIVE_MARKERS))
        {
            return 50;
        }
        return 0;
    }

    static boolean isPickUsesObjective(String value)
    {
        String lower = normalizedObjectiveText(value);
        String compactLower = compact(lower);
        return compactLower.equals("pick")
                || compactLower.equals("pickaxe")
                || compactLower.contains("pickuses")
                || compactLower.contains("pickaxeuses")
                || (compactLower.contains("pick") && compactLower.contains("use"));
    }

    static boolean isAxeUsesObjective(String value)
    {
        String lower = normalizedObjectiveText(value);
        String compactLower = compact(lower);
        return isPickUsesObjective(lower) == false
                && (compactLower.equals("axe")
                || compactLower.contains("axeuses")
                || (compactLower.contains("axe") && compactLower.contains("use")));
    }

    static boolean isShovelUsesObjective(String value)
    {
        String lower = normalizedObjectiveText(value);
        String compactLower = compact(lower);
        return compactLower.equals("shovel")
                || compactLower.contains("shoveluses")
                || (compactLower.contains("shovel") && compactLower.contains("use"));
    }

    static boolean isToolUsesObjective(String value)
    {
        return toolKind(value) != ToolKind.NONE;
    }

    private static String preferredObjectiveTitle(String objectiveName, String displayTitle)
    {
        if (objectivePriority(displayTitle) > 0)
        {
            return displayTitle;
        }
        if (objectivePriority(objectiveName) > 0)
        {
            return objectiveName;
        }
        return displayTitle.isBlank() ? objectiveName : displayTitle;
    }

    private static String objectiveContext(String objectiveName, String displayTitle)
    {
        return normalizedObjectiveText(objectiveName + " " + displayTitle);
    }

    private static boolean containsAnyObjectiveMarker(String lower, List<String> markers)
    {
        String compactLower = compact(lower);
        return markers.stream().anyMatch(marker -> {
            String normalized = normalizedObjectiveText(marker);
            return lower.contains(normalized) || compactLower.contains(compact(normalized));
        });
    }

    private static boolean containsTotalObjectiveMarker(String lower)
    {
        String normalized = normalizedObjectiveText(lower);
        String compactLower = compact(normalized);
        if (compactLower.equals("sum") || compactLower.equals("total"))
        {
            return true;
        }
        if (containsAnyObjectiveMarker(normalized, List.of("combinedblocks", "combined blocks", "blocks mined", "mined blocks")))
        {
            return true;
        }
        return (compactLower.contains("sum") || compactLower.contains("total"))
                && (compactLower.contains("block") || compactLower.contains("mine") || compactLower.contains("dig") || compactLower.contains("dug"));
    }

    private static String normalizedObjectiveText(String value)
    {
        return clean(value)
                .toLowerCase(Locale.ROOT)
                .replace('_', ' ')
                .replace('-', ' ');
    }

    private static String compact(String value)
    {
        return value == null ? "" : value.replaceAll("[^a-z0-9]", "");
    }

    private static ToolKind toolKind(String value)
    {
        String lower = normalizedObjectiveText(value);
        if (isPickUsesObjective(lower))
        {
            return ToolKind.PICKAXE;
        }
        if (isShovelUsesObjective(lower))
        {
            return ToolKind.SHOVEL;
        }
        if (isAxeUsesObjective(lower))
        {
            return ToolKind.AXE;
        }
        return ToolKind.NONE;
    }

    private static String combinedToolUsageTitle(Set<ToolKind> detectedKinds)
    {
        List<String> labels = new ArrayList<>();
        if (detectedKinds.contains(ToolKind.PICKAXE))
        {
            labels.add("Pickaxe Uses");
        }
        if (detectedKinds.contains(ToolKind.AXE))
        {
            labels.add("Axe Uses");
        }
        if (detectedKinds.contains(ToolKind.SHOVEL))
        {
            labels.add("Shovel Uses");
        }
        if (labels.isEmpty())
        {
            return "Combined Tool Uses";
        }
        return "Combined Tool Uses: " + String.join(" + ", labels);
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
        catch (NumberFormatException e)
        {
            MmmDebugLogger.info(
                    "scoreboard-rank-parse",
                    PARSE_DEBUG_LOG_INTERVAL_MS,
                    "[MMM_SYNC] failed to parse scoreboard rank from '{}': {}",
                    cleaned,
                    e.getMessage());
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
            catch (NumberFormatException e)
            {
                MmmDebugLogger.info(
                        "scoreboard-number-parse",
                        PARSE_DEBUG_LOG_INTERVAL_MS,
                        "[MMM_SYNC] failed to parse scoreboard number from '{}': {}",
                        matcher.group(),
                        e.getMessage());
            }
        }

        return best;
    }

    record Candidate(SourceLeaderboardSnapshot snapshot, int confidence, int objectivePriority, List<ScoreboardLine> lines)
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

    record ObjectiveRows(ScoreboardObjective objective, Collection<ScoreboardEntry> entries)
    {
    }

    private enum ToolKind
    {
        NONE,
        PICKAXE,
        AXE,
        SHOVEL
    }

    private static final class ToolUsageEntry
    {
        private final String username;
        private final EnumMap<ToolKind, Long> totals = new EnumMap<>(ToolKind.class);

        private ToolUsageEntry(String username)
        {
            this.username = username;
        }

        private String username()
        {
            return this.username;
        }

        private void putMax(ToolKind kind, long value)
        {
            if (kind == null || kind == ToolKind.NONE || value <= 0L)
            {
                return;
            }
            this.totals.merge(kind, value, Math::max);
        }

        private long total()
        {
            long total = 0L;
            for (Long value : this.totals.values())
            {
                total += Math.max(0L, value);
            }
            return total;
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
