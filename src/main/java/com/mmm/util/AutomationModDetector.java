package com.mmm.util;

import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

public final class AutomationModDetector
{
    private static final List<String> BLOCKED_MOD_ID_PARTS = List.of(
            "baritone",
            "meteor-client",
            "meteorclient",
            "meteor",
            "wurst",
            "impact",
            "inertia",
            "liquidbounce",
            "bleachhack",
            "aristois",
            "rusherhack",
            "future"
    );

    private AutomationModDetector()
    {
    }

    public static List<DetectedAutomationMod> findLoadedAutomationMods()
    {
        return FabricLoader.getInstance().getAllMods().stream()
                .map(AutomationModDetector::toDetectedAutomationMod)
                .filter(AutomationModDetector::isAutomationMod)
                .distinct()
                .toList();
    }

    private static DetectedAutomationMod toDetectedAutomationMod(ModContainer mod)
    {
        String id = mod.getMetadata().getId();
        String name = mod.getMetadata().getName();
        return new DetectedAutomationMod(id == null ? "" : id, name == null ? "" : name);
    }

    private static boolean isAutomationMod(DetectedAutomationMod mod)
    {
        String id = mod.id().toLowerCase(Locale.ROOT);
        String name = mod.name().toLowerCase(Locale.ROOT);
        for (String blocked : BLOCKED_MOD_ID_PARTS)
        {
            if (id.contains(blocked) || name.contains(blocked))
            {
                return true;
            }
        }
        return false;
    }

    public record DetectedAutomationMod(String id, String name)
    {
    }
}
