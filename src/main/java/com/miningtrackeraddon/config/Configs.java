package com.miningtrackeraddon.config;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.config.options.BooleanHotkeyGuiWrapper;
import fi.dy.masa.malilib.config.options.ConfigColor;
import fi.dy.masa.malilib.config.options.ConfigDouble;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import fi.dy.masa.malilib.config.options.ConfigStringList;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.JsonUtils;
import com.miningtrackeraddon.Reference;

public class Configs implements IConfigHandler
{
    private static final String CONFIG_FILE_NAME = Reference.STORAGE_ID + ".json";
    private static final String LEGACY_CONFIG_FILE_NAME = Reference.MOD_ID + ".json";

    public static class Generic
    {
        public static final ConfigInteger DAILY_GOAL = new ConfigInteger("dailyGoal", 1000, 1, Integer.MAX_VALUE, "Daily goal target.");
        public static final fi.dy.masa.malilib.config.options.ConfigString NOTIFICATION_THRESHOLDS = new fi.dy.masa.malilib.config.options.ConfigString("notificationThresholds", "25,50,75,100", "Popup threshold percentages, comma separated.");
        public static final ConfigInteger SOUND_ALERT_THRESHOLD = new ConfigInteger("soundAlertThreshold", 100, 1, 100, "Sound alert threshold percentage.");
        public static final ConfigInteger HUD_X = new ConfigInteger("hudX", 4, 0, 10000, "Mining HUD horizontal position.");
        public static final ConfigInteger HUD_Y = new ConfigInteger("hudY", 4, 0, 10000, "Mining HUD vertical position.");
        public static final ConfigDouble HUD_SCALE = new ConfigDouble("hudScale", 1.0D, 0.75D, 1.75D, "Mining HUD scale.");
        public static final ConfigOptionList BLOCK_ESP_COLOR_MODE = new ConfigOptionList("blockEspColorMode", BlockEspColorMode.RAINBOW, "Block ESP color mode.");
        public static final ConfigColor BLOCK_ESP_HEX_COLOR = new ConfigColor("blockEspHexColor", "#55FF55", "Block ESP custom color. Used when the color mode is Single Color.");
        public static final ConfigOptionList BLOCK_ESP_RENDER_MODE = new ConfigOptionList("blockEspRenderMode", BlockEspRenderMode.FULL_BLOCK, "Block ESP render mode.");
        public static final ConfigInteger BLOCK_ESP_OPACITY = new ConfigInteger("blockEspOpacity", 35, 0, 100, "Block ESP opacity percentage.");
        public static final ConfigDouble BLOCK_ESP_RAINBOW_SPEED = new ConfigDouble("blockEspRainbowSpeed", 1.0D, 0.1D, 10.0D, "Block ESP rainbow animation speed multiplier.");
        public static final ConfigStringList PERIMETER_OUTLINE_BLOCKS_LIST = new ConfigStringList("perimeterOutlineBlocksList", ImmutableList.of(), "The block types checked by the Perimeter Wall Dig Helper tweak.");

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                DAILY_GOAL,
                NOTIFICATION_THRESHOLDS,
                SOUND_ALERT_THRESHOLD,
                HUD_X,
                HUD_Y,
                HUD_SCALE,
                BLOCK_ESP_COLOR_MODE,
                BLOCK_ESP_HEX_COLOR,
                BLOCK_ESP_RENDER_MODE,
                BLOCK_ESP_OPACITY,
                BLOCK_ESP_RAINBOW_SPEED,
                PERIMETER_OUTLINE_BLOCKS_LIST
        );
    }

    public static long dailyProgress = 0L;
    public static long dailyGoalLastResetMs = System.currentTimeMillis();
    public static String activeProjectId = "";
    public static final List<ProjectEntry> PROJECTS = new ArrayList<>();

    public static void onConfigLoaded()
    {
        if (PROJECTS.isEmpty())
        {
            PROJECTS.add(ProjectEntry.create("Main Project", 0L));
        }

        for (ProjectEntry project : PROJECTS)
        {
            if (project.id == null || project.id.isBlank())
            {
                project.id = UUID.randomUUID().toString();
            }
            if (project.name == null || project.name.isBlank())
            {
                project.name = "Project";
            }
            project.progress = Math.max(0L, project.progress);
        }

        if (activeProjectId == null || activeProjectId.isBlank() || getActiveProject() == null)
        {
            activeProjectId = PROJECTS.getFirst().id;
        }

        dailyProgress = Math.max(0L, dailyProgress);
        dailyGoalLastResetMs = Math.max(0L, dailyGoalLastResetMs);

        List<Integer> thresholds = getNotificationThresholds();
        thresholds.removeIf(value -> value <= 0 || value > 100);
        thresholds.sort(Comparator.naturalOrder());
        if (thresholds.isEmpty())
        {
            thresholds = List.of(25, 50, 75, 100);
        }
        Generic.NOTIFICATION_THRESHOLDS.setValueFromString(String.join(",", thresholds.stream().map(String::valueOf).toList()));
        Generic.BLOCK_ESP_HEX_COLOR.setValueFromString(normalizeBlockEspHexColor(Generic.BLOCK_ESP_HEX_COLOR.getStringValue()));
        Generic.BLOCK_ESP_OPACITY.setIntegerValue(Math.max(0, Math.min(100, Generic.BLOCK_ESP_OPACITY.getIntegerValue())));
    }

    public static void loadFromFile()
    {
        migrateLegacyConfigIfNeeded();

        File configFile = getPrimaryConfigFile();
        if (configFile.exists() && configFile.isFile() && configFile.canRead())
        {
            JsonElement element = JsonUtils.parseJsonFile(configFile);
            if (element != null && element.isJsonObject())
            {
                JsonObject root = element.getAsJsonObject();
                ConfigUtils.readConfigBase(root, "Generic", Generic.OPTIONS);
                ConfigUtils.readHotkeys(root, "GenericHotkeys", Hotkeys.HOTKEY_LIST);
                ConfigUtils.readHotkeyToggleOptions(root, "TweakHotkeys", "TweakToggles", FeatureToggle.VALUES);
                readCustomState(root);
            }
        }

        onConfigLoaded();
    }

    public static void saveToFile()
    {
        migrateLegacyConfigIfNeeded();

        File dir = FileUtils.getConfigDirectory();
        if ((dir.exists() && dir.isDirectory()) || dir.mkdirs())
        {
            JsonObject root = new JsonObject();
            ConfigUtils.writeConfigBase(root, "Generic", Generic.OPTIONS);
            ConfigUtils.writeHotkeys(root, "GenericHotkeys", Hotkeys.HOTKEY_LIST);
            ConfigUtils.writeHotkeyToggleOptions(root, "TweakHotkeys", "TweakToggles", FeatureToggle.VALUES);
            writeCustomState(root);
            JsonUtils.writeJsonToFile(root, getPrimaryConfigFile());
        }
    }

    public static List<BooleanHotkeyGuiWrapper> getWrappedToggles()
    {
        return FeatureToggle.VALUES.stream().map(toggle -> new BooleanHotkeyGuiWrapper(toggle.getName(), toggle, toggle.getKeybind())).toList();
    }

    public static List<Integer> getNotificationThresholds()
    {
        List<Integer> values = new ArrayList<>();
        for (String part : Generic.NOTIFICATION_THRESHOLDS.getStringValue().split(","))
        {
            String trimmed = part.trim();
            if (trimmed.isEmpty())
            {
                continue;
            }
            try
            {
                values.add(Integer.parseInt(trimmed));
            }
            catch (NumberFormatException ignored)
            {
            }
        }
        return values;
    }

    public static ProjectEntry getActiveProject()
    {
        for (ProjectEntry project : PROJECTS)
        {
            if (project.id.equals(activeProjectId))
            {
                return project;
            }
        }
        return PROJECTS.isEmpty() ? null : PROJECTS.getFirst();
    }

    public static ProjectEntry createProject(String name)
    {
        ProjectEntry entry = ProjectEntry.create(name, 0L);
        PROJECTS.add(entry);
        if (activeProjectId == null || activeProjectId.isBlank())
        {
            activeProjectId = entry.id;
        }
        return entry;
    }

    public static boolean isBlockEspRainbow()
    {
        return Generic.BLOCK_ESP_COLOR_MODE.getOptionListValue() == BlockEspColorMode.RAINBOW;
    }

    public static boolean isBlockEspOutlineOnly()
    {
        return Generic.BLOCK_ESP_RENDER_MODE.getOptionListValue() == BlockEspRenderMode.OUTLINE_ONLY;
    }

    public static float getBlockEspOpacity()
    {
        return Generic.BLOCK_ESP_OPACITY.getIntegerValue() / 100.0F;
    }

    public static float getBlockEspRainbowSpeed()
    {
        return (float) Generic.BLOCK_ESP_RAINBOW_SPEED.getDoubleValue();
    }

    public static String normalizeBlockEspHexColor(String value)
    {
        String normalized = value == null ? "" : value.trim();
        if (normalized.startsWith("#"))
        {
            normalized = normalized.substring(1);
        }

        if (normalized.matches("(?i)[0-9a-f]{6}([0-9a-f]{2})?"))
        {
            return "#" + normalized.toUpperCase();
        }

        return "#55FF55";
    }

    @Override
    public void load()
    {
        loadFromFile();
    }

    @Override
    public void save()
    {
        saveToFile();
    }

    private static void readCustomState(JsonObject root)
    {
        if (root.has("State") && root.get("State").isJsonObject())
        {
            JsonObject state = root.getAsJsonObject("State");
            if (state.has("dailyProgress")) dailyProgress = state.get("dailyProgress").getAsLong();
            if (state.has("dailyGoalLastResetMs")) dailyGoalLastResetMs = state.get("dailyGoalLastResetMs").getAsLong();
            if (state.has("activeProjectId")) activeProjectId = state.get("activeProjectId").getAsString();
            PROJECTS.clear();
            if (state.has("projects") && state.get("projects").isJsonArray())
            {
                for (JsonElement element : state.getAsJsonArray("projects"))
                {
                    if (element.isJsonObject())
                    {
                        JsonObject object = element.getAsJsonObject();
                        ProjectEntry project = new ProjectEntry();
                        project.id = object.has("id") ? object.get("id").getAsString() : UUID.randomUUID().toString();
                        project.name = object.has("name") ? object.get("name").getAsString() : "Project";
                        project.progress = object.has("progress") ? object.get("progress").getAsLong() : 0L;
                        PROJECTS.add(project);
                    }
                }
            }
        }
    }

    private static void writeCustomState(JsonObject root)
    {
        JsonObject state = new JsonObject();
        state.addProperty("dailyProgress", dailyProgress);
        state.addProperty("dailyGoalLastResetMs", dailyGoalLastResetMs);
        state.addProperty("activeProjectId", activeProjectId == null ? "" : activeProjectId);

        JsonArray projects = new JsonArray();
        for (ProjectEntry project : PROJECTS)
        {
            JsonObject object = new JsonObject();
            object.addProperty("id", project.id);
            object.addProperty("name", project.name);
            object.addProperty("progress", project.progress);
            projects.add(object);
        }
        state.add("projects", projects);
        root.add("State", state);
    }

    private static File getPrimaryConfigFile()
    {
        return new File(FileUtils.getConfigDirectory(), CONFIG_FILE_NAME);
    }

    private static File getLegacyConfigFile()
    {
        return new File(FileUtils.getConfigDirectory(), LEGACY_CONFIG_FILE_NAME);
    }

    private static void migrateLegacyConfigIfNeeded()
    {
        File primary = getPrimaryConfigFile();
        File legacy = getLegacyConfigFile();
        if (primary.exists() || !legacy.exists() || !legacy.isFile())
        {
            return;
        }

        try
        {
            java.nio.file.Files.copy(legacy.toPath(), primary.toPath());
        }
        catch (Exception ignored)
        {
        }
    }

    public static class ProjectEntry
    {
        public String id;
        public String name;
        public long progress;

        public static ProjectEntry create(String name, long progress)
        {
            ProjectEntry entry = new ProjectEntry();
            entry.id = UUID.randomUUID().toString();
            entry.name = name;
            entry.progress = progress;
            return entry;
        }
    }

    public enum BlockEspColorMode implements IConfigOptionListEntry
    {
        RAINBOW("rainbow", "Rainbow"),
        SINGLE_COLOR("single_color", "Single Color");

        private final String value;
        private final String displayName;

        BlockEspColorMode(String value, String displayName)
        {
            this.value = value;
            this.displayName = displayName;
        }

        @Override
        public String getStringValue()
        {
            return this.value;
        }

        @Override
        public String getDisplayName()
        {
            return this.displayName;
        }

        @Override
        public IConfigOptionListEntry cycle(boolean forward)
        {
            return values()[(this.ordinal() + (forward ? 1 : values().length - 1)) % values().length];
        }

        @Override
        public IConfigOptionListEntry fromString(String value)
        {
            for (BlockEspColorMode mode : values())
            {
                if (mode.value.equalsIgnoreCase(value) || mode.displayName.equalsIgnoreCase(value))
                {
                    return mode;
                }
            }

            return RAINBOW;
        }
    }

    public enum BlockEspRenderMode implements IConfigOptionListEntry
    {
        FULL_BLOCK("full_block", "Full Block"),
        OUTLINE_ONLY("outline_only", "Outline Only");

        private final String value;
        private final String displayName;

        BlockEspRenderMode(String value, String displayName)
        {
            this.value = value;
            this.displayName = displayName;
        }

        @Override
        public String getStringValue()
        {
            return this.value;
        }

        @Override
        public String getDisplayName()
        {
            return this.displayName;
        }

        @Override
        public IConfigOptionListEntry cycle(boolean forward)
        {
            return values()[(this.ordinal() + (forward ? 1 : values().length - 1)) % values().length];
        }

        @Override
        public IConfigOptionListEntry fromString(String value)
        {
            for (BlockEspRenderMode mode : values())
            {
                if (mode.value.equalsIgnoreCase(value) || mode.displayName.equalsIgnoreCase(value))
                {
                    return mode;
                }
            }

            return FULL_BLOCK;
        }
    }
}
