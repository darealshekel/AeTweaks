package com.miningtrackeraddon.sync;

import com.mojang.authlib.GameProfile;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.scoreboard.ReadableScoreboardScore;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;

final class PersonalTotalDetector
{
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(?i)(\\d[\\d,._ ]*(?:\\.\\d+)?)\\s*([kmbt])?");
    private static final List<String> PERSONAL_MARKERS = List.of("your", "you", "my", "personal", "self", "player");
    private static final List<String> DIG_MARKERS = List.of("dig", "dug");

    private PersonalTotalDetector()
    {
    }

    static Detection detect(MinecraftClient client)
    {
        if (client == null || client.player == null)
        {
            return Detection.empty("client-or-player-missing");
        }

        String username = client.player.getGameProfile().getName();
        String usernameLower = username == null ? "" : username.toLowerCase(Locale.ROOT);
        SidebarResult sidebar = detectFromSidebar(client, username, usernameLower);
        TabResult tab = detectFromTabList(client, username, usernameLower);

        long chosen = Math.max(sidebar.total(), tab.total());
        String chosenSource = chosen <= 0L ? "none" : (sidebar.total() >= tab.total() ? "sidebar" : "tab");
        String skipReason = chosen <= 0L ? "no-valid-personal-total" : "";

        return new Detection(
                sidebar.total(),
                tab.total(),
                chosen,
                chosenSource,
                sidebar.objectiveTitle(),
                sidebar.matchedUsername(),
                sidebar.rawScore(),
                sidebar.renderedText(),
                tab.objectiveTitle(),
                tab.matchedUsername(),
                tab.rawScore(),
                tab.renderedText(),
                skipReason
        );
    }

    private static SidebarResult detectFromSidebar(MinecraftClient client, String username, String usernameLower)
    {
        if (client.world == null)
        {
            return new SidebarResult(0L, "no-world", "", 0L, "");
        }

        Scoreboard scoreboard = client.world.getScoreboard();
        ScoreboardObjective objective = scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        if (objective == null)
        {
            return new SidebarResult(0L, "no-sidebar-objective", "", 0L, "");
        }

        long rawScore = readDirectScore(scoreboard, objective, client.player.getGameProfile(), username);
        String rendered = findRenderedSidebarLineForUser(client, usernameLower);
        long parsedRendered = parseNumber(rendered);
        long total = Math.max(rawScore, parsedRendered);
        if (total <= 0L)
        {
            total = Math.max(0L, fallbackFromSidebarEntries(client, usernameLower));
        }

        return new SidebarResult(
                total,
                clean(objective.getDisplayName().getString()),
                username,
                rawScore,
                rendered
        );
    }

    private static TabResult detectFromTabList(MinecraftClient client, String username, String usernameLower)
    {
        if (client.getNetworkHandler() == null || client.world == null)
        {
            return new TabResult(0L, "no-tab-objective", "", 0L, "");
        }

        Scoreboard scoreboard = client.world.getScoreboard();
        ScoreboardObjective objective = scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.LIST);
        long rawScore = objective == null ? 0L : readDirectScore(scoreboard, objective, client.player.getGameProfile(), username);

        Collection<PlayerListEntry> playerList = client.getNetworkHandler().getPlayerList();
        if (playerList == null || playerList.isEmpty())
        {
            return new TabResult(rawScore, objective == null ? "no-tab-objective" : clean(objective.getDisplayName().getString()), username, rawScore, "empty-tab-list");
        }

        String rendered = "";
        long parsedRendered = 0L;
        for (PlayerListEntry entry : playerList)
        {
            if (entry == null || entry.getProfile() == null || entry.getProfile().getName() == null)
            {
                continue;
            }

            String profileName = entry.getProfile().getName();
            String display = entry.getDisplayName() != null ? entry.getDisplayName().getString() : profileName;
            if (display == null)
            {
                display = profileName;
            }

            if (profileName.toLowerCase(Locale.ROOT).equals(usernameLower))
            {
                rendered = clean(display);
                parsedRendered = parseNumber(rendered);
                break;
            }
        }

        long total = Math.max(rawScore, parsedRendered);
        return new TabResult(
                total,
                objective == null ? "no-tab-objective" : clean(objective.getDisplayName().getString()),
                username,
                rawScore,
                rendered.isBlank() ? "no-tab-match" : rendered
        );
    }

    private static long parseNumber(String raw)
    {
        if (raw == null || raw.isBlank())
        {
            return 0L;
        }

        String cleaned = raw
                .replaceAll("§.", "")
                .replace('\u00A0', ' ')
                .trim();
        Matcher matcher = NUMBER_PATTERN.matcher(cleaned);
        double best = 0D;
        while (matcher.find())
        {
            String numberPart = matcher.group(1) == null ? "" : matcher.group(1);
            String suffixPart = matcher.group(2) == null ? "" : matcher.group(2).toLowerCase(Locale.ROOT);
            String normalized = numberPart.replaceAll("[,_ ]", "");
            if (normalized.isBlank())
            {
                continue;
            }

            try
            {
                double value = Double.parseDouble(normalized);
                value *= switch (suffixPart)
                {
                    case "k" -> 1_000D;
                    case "m" -> 1_000_000D;
                    case "b" -> 1_000_000_000D;
                    case "t" -> 1_000_000_000_000D;
                    default -> 1D;
                };
                if (value > best)
                {
                    best = value;
                }
            }
            catch (NumberFormatException ignored)
            {
            }
        }

        return best <= 0D ? 0L : (long) Math.floor(best);
    }

    private static long readDirectScore(Scoreboard scoreboard, ScoreboardObjective objective, GameProfile profile, String username)
    {
        long fromProfile = readScore(scoreboard, objective, ScoreHolder.fromProfile(profile));
        long fromName = readScore(scoreboard, objective, ScoreHolder.fromName(username));
        return Math.max(fromProfile, fromName);
    }

    private static long readScore(Scoreboard scoreboard, ScoreboardObjective objective, ScoreHolder holder)
    {
        if (holder == null)
        {
            return 0L;
        }
        ReadableScoreboardScore score = scoreboard.getScore(holder, objective);
        return score == null ? 0L : Math.max(0L, score.getScore());
    }

    private static String findRenderedSidebarLineForUser(MinecraftClient client, String usernameLower)
    {
        List<ScoreboardReader.ObjectiveSnapshot> objectives = ScoreboardReader.readObjectives(client);
        for (ScoreboardReader.ObjectiveSnapshot snapshot : objectives)
        {
            if (snapshot.sidebar() == false)
            {
                continue;
            }
            for (ScoreboardReader.ScoreboardLine line : snapshot.lines())
            {
                String lower = line.cleaned().toLowerCase(Locale.ROOT);
                if (lower.contains(usernameLower) || line.owner().toLowerCase(Locale.ROOT).equals(usernameLower))
                {
                    return line.cleaned();
                }
            }
        }
        return "";
    }

    private static long fallbackFromSidebarEntries(MinecraftClient client, String usernameLower)
    {
        List<ScoreboardReader.ObjectiveSnapshot> objectives = ScoreboardReader.readObjectives(client);
        long best = 0L;
        for (ScoreboardReader.ObjectiveSnapshot snapshot : objectives)
        {
            if (snapshot.sidebar() == false)
            {
                continue;
            }
            for (ScoreboardReader.ScoreboardLine line : snapshot.lines())
            {
                String lower = line.cleaned().toLowerCase(Locale.ROOT);
                boolean mentionsUser = lower.contains(usernameLower) || line.owner().toLowerCase(Locale.ROOT).equals(usernameLower);
                boolean personalLine = PERSONAL_MARKERS.stream().anyMatch(lower::contains) && DIG_MARKERS.stream().anyMatch(lower::contains);
                if (mentionsUser == false && personalLine == false)
                {
                    continue;
                }
                long parsed = parseNumber(line.cleaned());
                if (parsed <= 0L)
                {
                    parsed = Math.max(0L, line.scoreValue());
                }
                if (parsed > best)
                {
                    best = parsed;
                }
            }
        }
        return best;
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

    record Detection(
            long sidebarTotal,
            long tabTotal,
            long chosenTotal,
            String chosenSource,
            String sidebarObjectiveTitle,
            String sidebarMatchedUsername,
            long sidebarRawScore,
            String sidebarRenderedText,
            String tabObjectiveTitle,
            String tabMatchedUsername,
            long tabRawScore,
            String tabRenderedText,
            String skipReason
    )
    {
        static Detection empty(String reason)
        {
            return new Detection(0L, 0L, 0L, "none", "", "", 0L, "", "", "", 0L, "", reason == null ? "" : reason);
        }
    }

    private record SidebarResult(
            long total,
            String objectiveTitle,
            String matchedUsername,
            long rawScore,
            String renderedText
    )
    {
    }

    private record TabResult(
            long total,
            String objectiveTitle,
            String matchedUsername,
            long rawScore,
            String renderedText
    )
    {
    }
}
