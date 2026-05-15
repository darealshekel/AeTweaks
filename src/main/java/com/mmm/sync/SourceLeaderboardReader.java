package com.mmm.sync;

import com.mmm.MMM;
import com.mmm.config.Configs;
import com.mmm.storage.WorldSessionContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;

public final class SourceLeaderboardReader
{
    private static final long DEBUG_LOG_INTERVAL_MS = 10_000L;
    private static long lastDebugLogMs;

    private SourceLeaderboardReader()
    {
    }

    public static SourceLeaderboardSnapshot read(MinecraftClient client)
    {
        List<SourceLeaderboardSnapshot> snapshots = readAll(client);
        return snapshots.isEmpty() ? null : snapshots.get(0);
    }

    public static List<SourceLeaderboardSnapshot> readAll(MinecraftClient client)
    {
        if (client == null || client.world == null || client.player == null)
        {
            return List.of();
        }

        Scoreboard scoreboard = client.world.getScoreboard();
        String username = client.player.getGameProfile().getName();
        WorldSessionContext.WorldInfo worldInfo = WorldSessionContext.getCurrentWorldInfo();
        String detectedServerName = ScoreboardSourceResolver.displayName(
                worldInfo != null ? worldInfo.displayName() : "",
                worldInfo
        );

        List<ScoreboardParser.Candidate> candidates = new ArrayList<>();
        ScoreboardObjective sidebar = scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        addCandidate(candidates, ScoreboardParser.parse(
                username,
                detectedServerName,
                sidebar,
                sidebar == null ? List.of() : scoreboard.getScoreboardEntries(sidebar)));

        for (ScoreboardObjective objective : scoreboard.getObjectives())
        {
            if (objective == sidebar)
            {
                continue;
            }
            addCandidate(candidates, ScoreboardParser.parse(
                    username,
                    detectedServerName,
                    objective,
                    scoreboard.getScoreboardEntries(objective)));
        }

        if (candidates.isEmpty())
        {
            debug("Skipping leaderboard sync because no valid mining scoreboard candidates were found.");
            return List.of();
        }

        candidates.sort(Comparator
                .comparingInt(ScoreboardParser.Candidate::objectivePriority).reversed()
                .thenComparing(Comparator.comparingInt(ScoreboardParser.Candidate::confidence).reversed())
                .thenComparing(candidate -> candidate.snapshot().objectiveTitle().toLowerCase(Locale.ROOT)));

        String sourceName = ScoreboardSourceResolver.displayName(
                worldInfo != null ? worldInfo.displayName() : detectedServerName,
                worldInfo
        );
        Map<String, SourceLeaderboardSnapshot> byObjective = new LinkedHashMap<>();
        for (ScoreboardParser.Candidate candidate : candidates)
        {
            SourceLeaderboardSnapshot snapshot = candidate.snapshot();
            SourceLeaderboardSnapshot normalized = new SourceLeaderboardSnapshot(
                    sourceName,
                    snapshot.objectiveTitle(),
                    snapshot.capturedAtMs(),
                    snapshot.totalDigs(),
                    snapshot.entries()
            );
            byObjective.putIfAbsent(normalizeObjectiveKey(normalized.objectiveTitle()), normalized);
        }

        List<SourceLeaderboardSnapshot> snapshots = new ArrayList<>(byObjective.values());
        SourceLeaderboardSnapshot combinedToolsSnapshot = combineToolUseSnapshots(sourceName, snapshots);
        if (combinedToolsSnapshot != null && byObjective.containsKey(normalizeObjectiveKey(combinedToolsSnapshot.objectiveTitle())) == false)
        {
            boolean hasAuthoritativeTotal = snapshots.stream()
                    .anyMatch(snapshot -> ScoreboardParser.objectivePriority(snapshot.objectiveTitle()) >= 70);
            if (hasAuthoritativeTotal)
            {
                snapshots.add(combinedToolsSnapshot);
            }
            else
            {
                snapshots.add(0, combinedToolsSnapshot);
            }
        }

        SourceLeaderboardSnapshot chosen = snapshots.isEmpty() ? null : snapshots.get(0);
        if (chosen != null)
        {
            debug(
                    "Leaderboard parsed from '{}' entries={} total={} candidateCount={}",
                    chosen.objectiveTitle(),
                    chosen.entries().size(),
                    chosen.totalDigs(),
                    snapshots.size()
            );
        }

        return snapshots;
    }

    private static void addCandidate(List<ScoreboardParser.Candidate> candidates, ScoreboardParser.Candidate candidate)
    {
        if (candidate != null && candidate.snapshot().isValid())
        {
            candidates.add(candidate);
        }
    }

    private static SourceLeaderboardSnapshot combineToolUseSnapshots(String sourceName, List<SourceLeaderboardSnapshot> snapshots)
    {
        List<SourceLeaderboardSnapshot> partials = new ArrayList<>();
        SourceLeaderboardSnapshot pickUses = bestPartialSnapshot(snapshots, ToolUseKind.PICKAXE);
        SourceLeaderboardSnapshot axeUses = bestPartialSnapshot(snapshots, ToolUseKind.AXE);
        SourceLeaderboardSnapshot shovelUses = bestPartialSnapshot(snapshots, ToolUseKind.SHOVEL);
        if (pickUses != null)
        {
            partials.add(pickUses);
        }
        if (axeUses != null)
        {
            partials.add(axeUses);
        }
        if (shovelUses != null)
        {
            partials.add(shovelUses);
        }
        if (partials.size() < 2)
        {
            return null;
        }

        Map<String, CombinedEntry> combined = new LinkedHashMap<>();
        for (SourceLeaderboardSnapshot partial : partials)
        {
            addPartialEntries(combined, partial);
        }
        if (combined.size() < 3)
        {
            return null;
        }

        List<CombinedEntry> sorted = combined.values().stream()
                .sorted(Comparator.comparingLong(CombinedEntry::digs).reversed().thenComparing(CombinedEntry::username, String.CASE_INSENSITIVE_ORDER))
                .toList();
        List<SourceLeaderboardEntry> entries = new ArrayList<>();
        for (int index = 0; index < sorted.size(); index++)
        {
            CombinedEntry entry = sorted.get(index);
            entries.add(new SourceLeaderboardEntry(entry.username(), entry.digs(), index + 1));
        }

        long total = partials.stream().mapToLong(SourceLeaderboardReader::partialSnapshotTotal).sum();
        return new SourceLeaderboardSnapshot(
                sourceName,
                combinedToolUseTitle(pickUses, axeUses, shovelUses),
                partials.stream().mapToLong(SourceLeaderboardSnapshot::capturedAtMs).max().orElse(System.currentTimeMillis()),
                total,
                entries
        );
    }

    private static SourceLeaderboardSnapshot bestPartialSnapshot(List<SourceLeaderboardSnapshot> snapshots, ToolUseKind kind)
    {
        return snapshots.stream()
                .filter(snapshot -> matchesToolUseKind(snapshot.objectiveTitle(), kind))
                .max(Comparator
                        .comparingLong(SourceLeaderboardReader::partialSnapshotTotal)
                        .thenComparingInt(snapshot -> snapshot.entries().size()))
                .orElse(null);
    }

    private static boolean matchesToolUseKind(String objectiveTitle, ToolUseKind kind)
    {
        return switch (kind)
        {
            case PICKAXE -> ScoreboardParser.isPickUsesObjective(objectiveTitle);
            case AXE -> ScoreboardParser.isAxeUsesObjective(objectiveTitle);
            case SHOVEL -> ScoreboardParser.isShovelUsesObjective(objectiveTitle);
        };
    }

    private static String combinedToolUseTitle(SourceLeaderboardSnapshot pickUses,
                                               SourceLeaderboardSnapshot axeUses,
                                               SourceLeaderboardSnapshot shovelUses)
    {
        List<String> labels = new ArrayList<>();
        if (pickUses != null)
        {
            labels.add("Pickaxe Uses");
        }
        if (axeUses != null)
        {
            labels.add("Axe Uses");
        }
        if (shovelUses != null)
        {
            labels.add("Shovel Uses");
        }
        return String.join(" + ", labels);
    }

    private static long partialSnapshotTotal(SourceLeaderboardSnapshot snapshot)
    {
        return snapshot.totalDigs() > 0L
                ? snapshot.totalDigs()
                : snapshot.entries().stream().mapToLong(SourceLeaderboardEntry::digs).sum();
    }

    private static void addPartialEntries(Map<String, CombinedEntry> combined, SourceLeaderboardSnapshot snapshot)
    {
        for (SourceLeaderboardEntry entry : snapshot.entries())
        {
            String key = entry.username().toLowerCase(Locale.ROOT);
            CombinedEntry previous = combined.get(key);
            combined.put(key, new CombinedEntry(
                    previous == null ? entry.username() : previous.username(),
                    (previous == null ? 0L : previous.digs()) + Math.max(0L, entry.digs())
            ));
        }
    }

    private static String normalizeObjectiveKey(String value)
    {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
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
        MMM.LOGGER.info(message, args);
    }

    private record CombinedEntry(String username, long digs)
    {
    }

    private enum ToolUseKind
    {
        PICKAXE,
        AXE,
        SHOVEL
    }
}
