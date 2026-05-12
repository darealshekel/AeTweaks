package com.mmm.config;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mmm.Reference;
import com.mmm.util.BlockBreakdownCatalog;

import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.config.options.BooleanHotkeyGuiWrapper;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigColor;
import fi.dy.masa.malilib.config.options.ConfigDouble;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import fi.dy.masa.malilib.config.options.ConfigStringList;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.JsonUtils;

public class Configs implements IConfigHandler
{
    private static final String CONFIG_FILE_NAME = Reference.STORAGE_ID + ".json";
    private static final String LEGACY_CONFIG_FILE_NAME = Reference.LEGACY_STORAGE_ID + ".json";
    private static final String DEFAULT_CLOUD_SYNC_ENDPOINT = "https://sync.mmmaniacs.com/v1/sync";
    private static final String LEGACY_ENDPOINT_BRAND = "aet" + "weaks";
    private static final String LEGACY_SUPABASE_PROJECT_REF = "jmspoiryzfilppiovhmf";
    public static final int MIN_DAILY_GOAL = 35_000;

    public static class Generic
    {
        public static final ConfigBoolean WEBSITE_SYNC_ENABLED = new ConfigBoolean("websiteSyncEnabled", true, "Enable MMM website sync.");
        public static final ConfigBoolean TOTAL_DIGS_SYNC_ENABLED = new ConfigBoolean("totalDigsSyncEnabled", true, "Sync Total Digs to Website.");
        public static final ConfigBoolean WEBSITE_SYNC_DEBUG = new ConfigBoolean("websiteSyncDebug", false, "Enable verbose website sync debug logging.");
        public static final ConfigInteger VALIDATION_MIN_BLOCKS = new ConfigInteger("validationMinBlocks", 250, 1, 100_000, "Minimum physical blocks in a session before MMM anti-AFK and anti-farm checks apply.");
        public static final ConfigDouble VALIDATION_CAMERA_VARIANCE_THRESHOLD = new ConfigDouble("validationCameraVarianceThreshold", 1.5D, 0.0D, 45.0D, "Pitch/yaw standard-deviation threshold, in degrees, below which a large mining session is flagged.");
        public static final ConfigDouble VALIDATION_POSITION_VARIANCE_THRESHOLD = new ConfigDouble("validationPositionVarianceThreshold", 1.25D, 0.0D, 16.0D, "Movement radius threshold, in blocks, below which a large mining session is flagged.");
        public static final ConfigInteger VALIDATION_CONTINUOUS_MINING_TICKS = new ConfigInteger("validationContinuousMiningTicks", 2400, 20, 72_000, "Maximum continuous held-mining ticks before a session is flagged for no action pauses.");
        public static final ConfigInteger VALIDATION_CLUSTER_BUFFER_SIZE = new ConfigInteger("validationClusterBufferSize", 50, 20, 200, "Recent broken-block buffer size used for repeated cluster farm detection.");
        public static final ConfigInteger VALIDATION_PLACE_BREAK_WINDOW_SECONDS = new ConfigInteger("validationPlaceBreakWindowSeconds", 30, 1, 600, "Seconds after placement during which breaking the same block counts toward place-and-break telemetry.");
        public static final ConfigBoolean ABBREVIATED_NUMBERS = new ConfigBoolean("abbreviatedNumbers", true, "Show shortened large numbers such as 10M instead of 10,000,000.");
        public static final ConfigInteger DAILY_GOAL = new ConfigInteger("dailyGoal", MIN_DAILY_GOAL, MIN_DAILY_GOAL, 1_000_000, "Daily goal target.");
        public static final fi.dy.masa.malilib.config.options.ConfigString NOTIFICATION_THRESHOLDS = new fi.dy.masa.malilib.config.options.ConfigString("notificationThresholds", "25,50,75,100", "Popup threshold percentages, comma separated.");
        public static final ConfigInteger SOUND_ALERT_THRESHOLD = new ConfigInteger("soundAlertThreshold", 100, 1, 100, "Sound alert threshold percentage.");
        public static final ConfigInteger HUD_X = new ConfigInteger("hudX", 4, 0, 820, "Mining HUD horizontal position.");
        public static final ConfigInteger HUD_Y = new ConfigInteger("hudY", 4, 0, 460, "Mining HUD vertical position.");
        public static final ConfigOptionList HUD_ALIGNMENT = new ConfigOptionList("hudAlignment", HudAlignment.TOP_LEFT, "Mining HUD alignment anchor.");
        public static final ConfigDouble HUD_SCALE = new ConfigDouble("hudScale", 1.0D, 0.75D, 1.75D, "Mining HUD scale.");
        public static final ConfigBoolean HUD_TEXT_BACKGROUND = new ConfigBoolean("hudTextBackground", false, "Draw small background boxes behind individual MMM HUD text lines.");
        public static final ConfigColor HUD_TITLE_HEX_COLOR = new ConfigColor("hudTitleHexColor", "#E00000", "Title color used by the MMM HUD.");
        public static final ConfigColor HUD_TEXT_HEX_COLOR = new ConfigColor("hudTextHexColor", "#F6F3EF", "Label/text color used by the MMM HUD.");
        public static final ConfigColor HUD_NUMBER_HEX_COLOR = new ConfigColor("hudNumberHexColor", "#FFFFFF", "Number color used by MMM HUD and UI numeric values.");
        public static final ConfigColor HUD_INACTIVE_HEX_COLOR = new ConfigColor("hudInactiveHexColor", "#949494", "Inactive/paused text color used by the MMM HUD.");
        public static final ConfigOptionList BPS_SMOOTHING = new ConfigOptionList("bpsSmoothing", BpsSmoothing.FAST, "BPS Smoothing");
        public static final ConfigOptionList BLOCK_ESP_COLOR_MODE = new ConfigOptionList("blockEspColorMode", BlockEspColorMode.RAINBOW, "Block ESP color mode.");
        public static final ConfigColor BLOCK_ESP_HEX_COLOR = new ConfigColor("blockEspHexColor", "#55FF55", "Block ESP custom color. Used when the color mode is Single Color.");
        public static final ConfigOptionList BLOCK_ESP_RENDER_MODE = new ConfigOptionList("blockEspRenderMode", BlockEspRenderMode.FULL_BLOCK, "Block ESP render mode.");
        public static final ConfigInteger BLOCK_ESP_OPACITY = new ConfigInteger("blockEspOpacity", 35, 0, 100, "Block ESP opacity percentage.");
        public static final ConfigDouble BLOCK_ESP_RAINBOW_SPEED = new ConfigDouble("blockEspRainbowSpeed", 1.0D, 0.1D, 10.0D, "Block ESP rainbow animation speed multiplier.");
        public static final ConfigColor GRAPH_LINE_HEX_COLOR = new ConfigColor("graphLineHexColor", "#E00000", "Speed graph line color.");
        public static final ConfigColor GRAPH_FILL_HEX_COLOR = new ConfigColor("graphFillHexColor", "#E00000", "Speed graph fill color.");
        public static final ConfigInteger GRAPH_FILL_OPACITY = new ConfigInteger("graphFillOpacity", 42, 0, 100, "Speed graph fill opacity percentage.");
        public static final ConfigColor GRAPH_GRID_HEX_COLOR = new ConfigColor("graphGridHexColor", "#FFC8C8C8", "Speed graph grid line color.");
        public static final ConfigInteger GRAPH_GRID_OPACITY = new ConfigInteger("graphGridOpacity", 27, 0, 100, "Speed graph grid line opacity percentage.");
        public static final ConfigInteger GRAPH_BG_OPACITY = new ConfigInteger("graphBgOpacity", 75, 0, 100, "Speed graph background opacity percentage.");
        public static final ConfigInteger GRAPH_SCALE_STEP = new ConfigInteger("graphScaleStep", 100, 50, 1000, "Speed graph Y-axis grid interval (blocks/hr).");
        public static final ConfigStringList PERIMETER_OUTLINE_BLOCKS_LIST = new ConfigStringList("perimeterOutlineBlocksList", ImmutableList.of(), "The block types checked by the Perimeter Wall Dig Helper tweak.");

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                WEBSITE_SYNC_ENABLED,
                TOTAL_DIGS_SYNC_ENABLED,
                WEBSITE_SYNC_DEBUG,
                ABBREVIATED_NUMBERS,
                DAILY_GOAL,
                NOTIFICATION_THRESHOLDS,
                SOUND_ALERT_THRESHOLD,
                HUD_X,
                HUD_Y,
                HUD_ALIGNMENT,
                HUD_SCALE,
                HUD_TEXT_BACKGROUND,
                HUD_TITLE_HEX_COLOR,
                HUD_TEXT_HEX_COLOR,
                HUD_NUMBER_HEX_COLOR,
                HUD_INACTIVE_HEX_COLOR,
                BPS_SMOOTHING,
                BLOCK_ESP_COLOR_MODE,
                BLOCK_ESP_HEX_COLOR,
                BLOCK_ESP_RENDER_MODE,
                BLOCK_ESP_OPACITY,
                BLOCK_ESP_RAINBOW_SPEED,
                GRAPH_LINE_HEX_COLOR,
                GRAPH_FILL_HEX_COLOR,
                GRAPH_FILL_OPACITY,
                GRAPH_BG_OPACITY,
                GRAPH_GRID_HEX_COLOR,
                GRAPH_GRID_OPACITY,
                GRAPH_SCALE_STEP
        );

        public static final ImmutableList<IConfigBase> PERSISTED_OPTIONS = ImmutableList.of(
                WEBSITE_SYNC_ENABLED,
                TOTAL_DIGS_SYNC_ENABLED,
                WEBSITE_SYNC_DEBUG,
                VALIDATION_MIN_BLOCKS,
                VALIDATION_CAMERA_VARIANCE_THRESHOLD,
                VALIDATION_POSITION_VARIANCE_THRESHOLD,
                VALIDATION_CONTINUOUS_MINING_TICKS,
                VALIDATION_CLUSTER_BUFFER_SIZE,
                VALIDATION_PLACE_BREAK_WINDOW_SECONDS,
                ABBREVIATED_NUMBERS,
                DAILY_GOAL,
                NOTIFICATION_THRESHOLDS,
                SOUND_ALERT_THRESHOLD,
                HUD_X,
                HUD_Y,
                HUD_ALIGNMENT,
                HUD_SCALE,
                HUD_TEXT_BACKGROUND,
                HUD_TITLE_HEX_COLOR,
                HUD_TEXT_HEX_COLOR,
                HUD_NUMBER_HEX_COLOR,
                HUD_INACTIVE_HEX_COLOR,
                BPS_SMOOTHING,
                BLOCK_ESP_COLOR_MODE,
                BLOCK_ESP_HEX_COLOR,
                BLOCK_ESP_RENDER_MODE,
                BLOCK_ESP_OPACITY,
                BLOCK_ESP_RAINBOW_SPEED,
                GRAPH_LINE_HEX_COLOR,
                GRAPH_FILL_HEX_COLOR,
                GRAPH_FILL_OPACITY,
                GRAPH_BG_OPACITY,
                GRAPH_GRID_HEX_COLOR,
                GRAPH_GRID_OPACITY,
                GRAPH_SCALE_STEP,
                PERIMETER_OUTLINE_BLOCKS_LIST
        );
    }

    public static final long DEFAULT_WEBSITE_SYNC_INTERVAL_MS = 300_000L;
    public static final long SUPPORTER_WEBSITE_SYNC_INTERVAL_MS = 150_000L;
    public static final long SUPPORTER_PLUS_WEBSITE_SYNC_INTERVAL_MS = 60_000L;
    public static final long MIN_WEBSITE_SYNC_INTERVAL_MS = 60_000L;
    public static final long MAX_WEBSITE_SYNC_INTERVAL_MS = 300_000L;
    public static long dailyProgress = 0L;
    public static long dailyGoalLastResetMs = System.currentTimeMillis();
    public static long dailyBlocksMined = 0L;
    public static String dailyBlocksDate = "";
    public static long weeklyBlocksMined = 0L;
    public static String weeklyBlocksWeek = "";
    public static long personalRecordDailyBlocks = 0L;
    public static long personalRecordWeeklyBlocks = 0L;
    public static long fastest100kMs = 0L;
    public static long fastest100kStartedAtMs = 0L;
    public static long fastest100kFinishedAtMs = 0L;
    public static String activeProjectId = "";
    public static String cloudSyncEndpoint = DEFAULT_CLOUD_SYNC_ENDPOINT;
    public static String cloudSyncSecret = "";
    public static String cloudClientId = "";
    public static String websiteLinkedMinecraftUuid = "";
    public static String websiteLinkedMinecraftUsername = "";
    public static long websiteLinkedAtMs = 0L;
    public static String websiteSyncTier = "free";
    public static long websiteSyncIntervalMs = DEFAULT_WEBSITE_SYNC_INTERVAL_MS;
    public static long websiteGlobalTotalBlocks = 0L;
    public static long websiteGlobalTotalUpdatedAtMs = 0L;
    public static long websiteLastSuccessfulSyncMs = 0L;
    public static long totalBlocksMined = 0L;
    public static final List<ProjectEntry> PROJECTS = new ArrayList<>();
    public static final List<WorldStatsEntry> WORLD_STATS = new ArrayList<>();
    public static final String BLOCK_BREAKDOWN_SOURCE_MINECRAFT_STATS = "minecraft_stats";
    public static final String BLOCK_BREAKDOWN_SOURCE_LOCAL_OBSERVED = "local_observed";

    public static void onConfigLoaded()
    {
        boolean syncIdentityGenerated = false;
        boolean dailyGoalMigrated = false;

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
        dailyBlocksMined = Math.max(0L, dailyBlocksMined);
        dailyBlocksDate = dailyBlocksDate == null ? "" : dailyBlocksDate.trim();
        weeklyBlocksMined = Math.max(0L, weeklyBlocksMined);
        weeklyBlocksWeek = weeklyBlocksWeek == null ? "" : weeklyBlocksWeek.trim();
        personalRecordDailyBlocks = Math.max(personalRecordDailyBlocks, dailyBlocksMined);
        personalRecordWeeklyBlocks = Math.max(personalRecordWeeklyBlocks, weeklyBlocksMined);
        fastest100kMs = Math.max(0L, fastest100kMs);
        fastest100kStartedAtMs = Math.max(0L, fastest100kStartedAtMs);
        fastest100kFinishedAtMs = Math.max(0L, fastest100kFinishedAtMs);
        totalBlocksMined = Math.max(0L, totalBlocksMined);
        boolean migratedLegacySyncEndpoint = isLegacySyncEndpoint(cloudSyncEndpoint);
        if (cloudSyncEndpoint == null || cloudSyncEndpoint.isBlank() || migratedLegacySyncEndpoint)
        {
            cloudSyncEndpoint = DEFAULT_CLOUD_SYNC_ENDPOINT;
        }

        for (WorldStatsEntry entry : WORLD_STATS)
        {
            if (entry.worldId == null || entry.worldId.isBlank())
            {
                entry.worldId = "default";
            }
            if (entry.displayName == null || entry.displayName.isBlank())
            {
                entry.displayName = entry.worldId;
            }
            entry.kind = entry.kind == null || entry.kind.isBlank() ? "unknown" : entry.kind;
            entry.host = entry.host == null ? "" : entry.host;
            entry.totalBlocks = Math.max(0L, entry.totalBlocks);
            entry.lastSeenAt = Math.max(0L, entry.lastSeenAt);
            entry.blockBreakdown = sanitizeBlockBreakdown(entry.blockBreakdown);
            entry.blockBreakdownSource = sanitizeBlockBreakdownSource(entry.blockBreakdownSource);
            entry.blockBreakdownUpdatedAtMs = Math.max(0L, entry.blockBreakdownUpdatedAtMs);
        }
        cloudSyncSecret = cloudSyncSecret == null ? "" : cloudSyncSecret.trim();
        if (cloudClientId == null || cloudClientId.isBlank())
        {
            cloudClientId = "mmm_" + UUID.randomUUID();
            syncIdentityGenerated = true;
        }
        websiteLinkedMinecraftUuid = websiteLinkedMinecraftUuid == null ? "" : websiteLinkedMinecraftUuid.trim().toLowerCase();
        websiteLinkedMinecraftUsername = websiteLinkedMinecraftUsername == null ? "" : websiteLinkedMinecraftUsername.trim();
        websiteLinkedAtMs = Math.max(0L, websiteLinkedAtMs);
        websiteSyncTier = normalizeWebsiteSyncTier(websiteSyncTier);
        websiteSyncIntervalMs = normalizeWebsiteSyncIntervalMs(websiteSyncIntervalMs);
        websiteGlobalTotalBlocks = Math.max(0L, websiteGlobalTotalBlocks);
        websiteGlobalTotalUpdatedAtMs = Math.max(0L, websiteGlobalTotalUpdatedAtMs);
        websiteLastSuccessfulSyncMs = Math.max(0L, websiteLastSuccessfulSyncMs);
        if (Generic.DAILY_GOAL.getIntegerValue() < MIN_DAILY_GOAL)
        {
            Generic.DAILY_GOAL.setIntegerValue(MIN_DAILY_GOAL);
            dailyGoalMigrated = true;
        }

        List<Integer> thresholds = getNotificationThresholds();
        thresholds.removeIf(value -> value <= 0 || value > 100);
        thresholds.sort(Comparator.naturalOrder());
        if (thresholds.isEmpty())
        {
            thresholds = List.of(25, 50, 75, 100);
        }
        Generic.NOTIFICATION_THRESHOLDS.setValueFromString(String.join(",", thresholds.stream().map(String::valueOf).toList()));
        Generic.BLOCK_ESP_HEX_COLOR.setValueFromString(normalizeBlockEspHexColor(Generic.BLOCK_ESP_HEX_COLOR.getStringValue()));
        Generic.HUD_TITLE_HEX_COLOR.setValueFromString(normalizeHexColor(Generic.HUD_TITLE_HEX_COLOR.getStringValue(), "#E00000"));
        Generic.HUD_TEXT_HEX_COLOR.setValueFromString(normalizeHexColor(Generic.HUD_TEXT_HEX_COLOR.getStringValue(), "#F6F3EF"));
        Generic.HUD_NUMBER_HEX_COLOR.setValueFromString(normalizeHexColor(Generic.HUD_NUMBER_HEX_COLOR.getStringValue(), "#FFFFFF"));
        Generic.HUD_INACTIVE_HEX_COLOR.setValueFromString(normalizeHexColor(Generic.HUD_INACTIVE_HEX_COLOR.getStringValue(), "#949494"));
        Generic.BLOCK_ESP_OPACITY.setIntegerValue(Math.max(0, Math.min(100, Generic.BLOCK_ESP_OPACITY.getIntegerValue())));
        Generic.GRAPH_LINE_HEX_COLOR.setValueFromString(normalizeHexColor(Generic.GRAPH_LINE_HEX_COLOR.getStringValue(), "#E00000"));
        Generic.GRAPH_FILL_HEX_COLOR.setValueFromString(normalizeHexColor(Generic.GRAPH_FILL_HEX_COLOR.getStringValue(), "#E00000"));
        Generic.GRAPH_GRID_HEX_COLOR.setValueFromString(normalizeHexColor(Generic.GRAPH_GRID_HEX_COLOR.getStringValue(), "#FFC8C8C8"));
        Generic.GRAPH_FILL_OPACITY.setIntegerValue(Math.max(0, Math.min(100, Generic.GRAPH_FILL_OPACITY.getIntegerValue())));
        Generic.GRAPH_BG_OPACITY.setIntegerValue(Math.max(0, Math.min(100, Generic.GRAPH_BG_OPACITY.getIntegerValue())));
        Generic.GRAPH_GRID_OPACITY.setIntegerValue(Math.max(0, Math.min(100, Generic.GRAPH_GRID_OPACITY.getIntegerValue())));

        if (syncIdentityGenerated || migratedLegacySyncEndpoint || dailyGoalMigrated)
        {
            saveToFile();
        }
    }

    private static boolean isLegacySyncEndpoint(String endpoint)
    {
        if (endpoint == null)
        {
            return false;
        }

        String normalized = endpoint.trim().toLowerCase();
        return normalized.contains(LEGACY_ENDPOINT_BRAND)
                || normalized.contains("aewt-sync-pro")
                || normalized.contains("xshbqnihopsznsnjqjji")
                || normalized.contains(LEGACY_SUPABASE_PROJECT_REF)
                || (normalized.contains("supabase.co") && normalized.contains("mmm-sync"))
                || normalized.endsWith("/" + LEGACY_ENDPOINT_BRAND + "-sync");
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
                ConfigUtils.readConfigBase(root, "Generic", Generic.PERSISTED_OPTIONS);
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
            ConfigUtils.writeConfigBase(root, "Generic", Generic.PERSISTED_OPTIONS);
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

    public static String normalizeHexColor(String value, String fallback)
    {
        String normalized = value == null ? "" : value.trim();
        if (normalized.startsWith("#"))
        {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith("0x") || normalized.startsWith("0X"))
        {
            normalized = normalized.substring(2);
        }

        if (normalized.matches("(?i)[0-9a-f]{8}"))
        {
            return "#" + normalized.substring(2).toUpperCase();
        }

        if (normalized.matches("(?i)[0-9a-f]{6}"))
        {
            return "#" + normalized.toUpperCase();
        }

        return fallback;
    }

    public static int getHudNumberColor()
    {
        return parseOpaqueHexColor(Generic.HUD_NUMBER_HEX_COLOR.getStringValue(), "#FFFFFF");
    }

    public static int getHudTitleColor()
    {
        return parseOpaqueHexColor(Generic.HUD_TITLE_HEX_COLOR.getStringValue(), "#E00000");
    }

    public static int getHudTextColor()
    {
        return parseOpaqueHexColor(Generic.HUD_TEXT_HEX_COLOR.getStringValue(), "#F6F3EF");
    }

    public static int getHudInactiveColor()
    {
        return parseOpaqueHexColor(Generic.HUD_INACTIVE_HEX_COLOR.getStringValue(), "#949494");
    }

    public static int getGraphLineColor()  { return parseOpaqueHexColor(Generic.GRAPH_LINE_HEX_COLOR.getStringValue(), "#E00000"); }
    public static int getGraphFillColor()  { return parseOpaqueHexColor(Generic.GRAPH_FILL_HEX_COLOR.getStringValue(), "#E00000"); }
    public static int getGraphGridColor()  { return parseOpaqueHexColor(Generic.GRAPH_GRID_HEX_COLOR.getStringValue(), "#FFC8C8C8"); }
    public static float getGraphFillOpacity() { return Generic.GRAPH_FILL_OPACITY.getIntegerValue() / 100.0F; }
    public static float getGraphBgOpacity() { return Generic.GRAPH_BG_OPACITY.getIntegerValue() / 100.0F; }
    public static float getGraphGridOpacity() { return Generic.GRAPH_GRID_OPACITY.getIntegerValue() / 100.0F; }
    public static float getGraphScaleStep() { return (float) Generic.GRAPH_SCALE_STEP.getIntegerValue(); }

    private static int parseOpaqueHexColor(String value, String fallback)
    {
        String hex = normalizeHexColor(value, fallback);
        long parsed = Long.parseLong(hex.substring(1), 16) & 0x00FFFFFFL;
        return (int) (0xFF000000L | parsed);
    }

    public static BpsSmoothing getBpsSmoothingMode()
    {
        return (BpsSmoothing) Generic.BPS_SMOOTHING.getOptionListValue();
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
            if (state.has("dailyBlocksMined")) dailyBlocksMined = state.get("dailyBlocksMined").getAsLong();
            if (state.has("dailyBlocksDate")) dailyBlocksDate = state.get("dailyBlocksDate").getAsString();
            if (state.has("weeklyBlocksMined")) weeklyBlocksMined = state.get("weeklyBlocksMined").getAsLong();
            if (state.has("weeklyBlocksWeek")) weeklyBlocksWeek = state.get("weeklyBlocksWeek").getAsString();
            if (state.has("personalRecordDailyBlocks")) personalRecordDailyBlocks = state.get("personalRecordDailyBlocks").getAsLong();
            if (state.has("personalRecordWeeklyBlocks")) personalRecordWeeklyBlocks = state.get("personalRecordWeeklyBlocks").getAsLong();
            if (state.has("fastest100kMs")) fastest100kMs = state.get("fastest100kMs").getAsLong();
            if (state.has("fastest100kStartedAtMs")) fastest100kStartedAtMs = state.get("fastest100kStartedAtMs").getAsLong();
            if (state.has("fastest100kFinishedAtMs")) fastest100kFinishedAtMs = state.get("fastest100kFinishedAtMs").getAsLong();
            if (state.has("activeProjectId")) activeProjectId = state.get("activeProjectId").getAsString();
            if (state.has("cloudSyncEnabled")) Generic.WEBSITE_SYNC_ENABLED.setBooleanValue(state.get("cloudSyncEnabled").getAsBoolean());
            if (state.has("totalDigsSyncEnabled")) Generic.TOTAL_DIGS_SYNC_ENABLED.setBooleanValue(state.get("totalDigsSyncEnabled").getAsBoolean());
            if (state.has("cloudSyncEndpoint")) cloudSyncEndpoint = state.get("cloudSyncEndpoint").getAsString();
            if (state.has("cloudSyncSecret")) cloudSyncSecret = state.get("cloudSyncSecret").getAsString();
            if (state.has("cloudClientId")) cloudClientId = state.get("cloudClientId").getAsString();
            if (state.has("websiteLinkedMinecraftUuid")) websiteLinkedMinecraftUuid = state.get("websiteLinkedMinecraftUuid").getAsString();
            if (state.has("websiteLinkedMinecraftUsername")) websiteLinkedMinecraftUsername = state.get("websiteLinkedMinecraftUsername").getAsString();
            if (state.has("websiteLinkedAtMs")) websiteLinkedAtMs = state.get("websiteLinkedAtMs").getAsLong();
            if (state.has("websiteSyncTier")) websiteSyncTier = state.get("websiteSyncTier").getAsString();
            if (state.has("websiteSyncIntervalMs")) websiteSyncIntervalMs = state.get("websiteSyncIntervalMs").getAsLong();
            if (state.has("websiteGlobalTotalBlocks")) websiteGlobalTotalBlocks = state.get("websiteGlobalTotalBlocks").getAsLong();
            if (state.has("websiteGlobalTotalUpdatedAtMs")) websiteGlobalTotalUpdatedAtMs = state.get("websiteGlobalTotalUpdatedAtMs").getAsLong();
            if (state.has("websiteLastSuccessfulSyncMs")) websiteLastSuccessfulSyncMs = state.get("websiteLastSuccessfulSyncMs").getAsLong();
            if (state.has("totalBlocksMined")) totalBlocksMined = state.get("totalBlocksMined").getAsLong();
            PROJECTS.clear();
            WORLD_STATS.clear();
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
            if (state.has("worldStats") && state.get("worldStats").isJsonArray())
            {
                for (JsonElement element : state.getAsJsonArray("worldStats"))
                {
                    if (element.isJsonObject())
                    {
                        JsonObject object = element.getAsJsonObject();
                        WorldStatsEntry entry = new WorldStatsEntry();
                        entry.worldId = object.has("worldId") ? object.get("worldId").getAsString() : "default";
                        entry.displayName = object.has("displayName") ? object.get("displayName").getAsString() : entry.worldId;
                        entry.kind = object.has("kind") ? object.get("kind").getAsString() : "unknown";
                        entry.host = object.has("host") ? object.get("host").getAsString() : "";
                        entry.totalBlocks = object.has("totalBlocks") ? object.get("totalBlocks").getAsLong() : 0L;
                        entry.lastSeenAt = object.has("lastSeenAt") ? object.get("lastSeenAt").getAsLong() : 0L;
                        entry.blockBreakdown = readBlockBreakdown(object);
                        entry.blockBreakdownSource = object.has("blockBreakdownSource") ? object.get("blockBreakdownSource").getAsString() : "";
                        entry.blockBreakdownUpdatedAtMs = object.has("blockBreakdownUpdatedAtMs") ? object.get("blockBreakdownUpdatedAtMs").getAsLong() : 0L;
                        WORLD_STATS.add(entry);
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
        state.addProperty("dailyBlocksMined", dailyBlocksMined);
        state.addProperty("dailyBlocksDate", dailyBlocksDate == null ? "" : dailyBlocksDate);
        state.addProperty("weeklyBlocksMined", weeklyBlocksMined);
        state.addProperty("weeklyBlocksWeek", weeklyBlocksWeek == null ? "" : weeklyBlocksWeek);
        state.addProperty("personalRecordDailyBlocks", personalRecordDailyBlocks);
        state.addProperty("personalRecordWeeklyBlocks", personalRecordWeeklyBlocks);
        state.addProperty("fastest100kMs", fastest100kMs);
        state.addProperty("fastest100kStartedAtMs", fastest100kStartedAtMs);
        state.addProperty("fastest100kFinishedAtMs", fastest100kFinishedAtMs);
        state.addProperty("activeProjectId", activeProjectId == null ? "" : activeProjectId);
        state.addProperty("cloudSyncEnabled", Generic.WEBSITE_SYNC_ENABLED.getBooleanValue());
        state.addProperty("totalDigsSyncEnabled", Generic.TOTAL_DIGS_SYNC_ENABLED.getBooleanValue());
        state.addProperty("cloudSyncEndpoint", cloudSyncEndpoint == null ? DEFAULT_CLOUD_SYNC_ENDPOINT : cloudSyncEndpoint);
        state.addProperty("cloudSyncSecret", cloudSyncSecret == null ? "" : cloudSyncSecret);
        state.addProperty("cloudClientId", cloudClientId == null ? "" : cloudClientId);
        state.addProperty("websiteLinkedMinecraftUuid", websiteLinkedMinecraftUuid == null ? "" : websiteLinkedMinecraftUuid);
        state.addProperty("websiteLinkedMinecraftUsername", websiteLinkedMinecraftUsername == null ? "" : websiteLinkedMinecraftUsername);
        state.addProperty("websiteLinkedAtMs", websiteLinkedAtMs);
        state.addProperty("websiteSyncTier", normalizeWebsiteSyncTier(websiteSyncTier));
        state.addProperty("websiteSyncIntervalMs", normalizeWebsiteSyncIntervalMs(websiteSyncIntervalMs));
        state.addProperty("websiteGlobalTotalBlocks", websiteGlobalTotalBlocks);
        state.addProperty("websiteGlobalTotalUpdatedAtMs", websiteGlobalTotalUpdatedAtMs);
        state.addProperty("websiteLastSuccessfulSyncMs", websiteLastSuccessfulSyncMs);
        state.addProperty("totalBlocksMined", totalBlocksMined);

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

        JsonArray worldStats = new JsonArray();
        for (WorldStatsEntry entry : WORLD_STATS)
        {
            JsonObject object = new JsonObject();
            object.addProperty("worldId", entry.worldId);
            object.addProperty("displayName", entry.displayName);
            object.addProperty("kind", entry.kind);
            object.addProperty("host", entry.host);
            object.addProperty("totalBlocks", entry.totalBlocks);
            object.addProperty("lastSeenAt", entry.lastSeenAt);
            object.addProperty("blockBreakdownSource", sanitizeBlockBreakdownSource(entry.blockBreakdownSource));
            object.addProperty("blockBreakdownUpdatedAtMs", entry.blockBreakdownUpdatedAtMs);
            object.add("blockBreakdown", writeBlockBreakdown(entry.blockBreakdown));
            worldStats.add(object);
        }
        state.add("worldStats", worldStats);
        root.add("State", state);
    }

    public static WorldStatsEntry getOrCreateWorldStats(String worldId, String displayName, String kind, String host)
    {
        String normalizedWorldId = worldId == null || worldId.isBlank() ? "default" : worldId;
        for (WorldStatsEntry entry : WORLD_STATS)
        {
            if (normalizedWorldId.equals(entry.worldId))
            {
                entry.displayName = displayName == null || displayName.isBlank() ? entry.displayName : displayName;
                entry.kind = kind == null || kind.isBlank() ? entry.kind : kind;
                entry.host = host == null ? "" : host;
                if (entry.blockBreakdown == null)
                {
                    entry.blockBreakdown = new LinkedHashMap<>();
                }
                entry.blockBreakdownSource = sanitizeBlockBreakdownSource(entry.blockBreakdownSource);
                return entry;
            }
        }

        WorldStatsEntry entry = new WorldStatsEntry();
        entry.worldId = normalizedWorldId;
        entry.displayName = displayName == null || displayName.isBlank() ? normalizedWorldId : displayName;
        entry.kind = kind == null || kind.isBlank() ? "unknown" : kind;
        entry.host = host == null ? "" : host;
        entry.blockBreakdown = new LinkedHashMap<>();
        entry.blockBreakdownSource = "";
        WORLD_STATS.add(entry);
        return entry;
    }

    private static Map<String, Long> readBlockBreakdown(JsonObject object)
    {
        if (object.has("blockBreakdown") == false || object.get("blockBreakdown").isJsonObject() == false)
        {
            return new LinkedHashMap<>();
        }

        Map<String, Long> breakdown = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("blockBreakdown").entrySet())
        {
            if (entry.getKey() == null || entry.getKey().isBlank())
            {
                continue;
            }
            try
            {
                long count = entry.getValue().getAsLong();
                if (count > 0L)
                {
                    breakdown.put(entry.getKey(), count);
                }
            }
            catch (Exception ignored)
            {
            }
        }
        return breakdown;
    }

    private static JsonObject writeBlockBreakdown(Map<String, Long> breakdown)
    {
        JsonObject object = new JsonObject();
        if (breakdown == null)
        {
            return object;
        }

        breakdown.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getKey().isBlank() == false && entry.getValue() != null && entry.getValue() > 0L)
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> object.addProperty(entry.getKey(), entry.getValue()));
        return object;
    }

    public static Map<String, Long> sanitizeBlockBreakdown(Map<String, Long> breakdown)
    {
        return BlockBreakdownCatalog.sanitize(breakdown);
    }

    public static String sanitizeBlockBreakdownSource(String source)
    {
        if (BLOCK_BREAKDOWN_SOURCE_MINECRAFT_STATS.equals(source))
        {
            return BLOCK_BREAKDOWN_SOURCE_MINECRAFT_STATS;
        }
        if (BLOCK_BREAKDOWN_SOURCE_LOCAL_OBSERVED.equals(source))
        {
            return BLOCK_BREAKDOWN_SOURCE_LOCAL_OBSERVED;
        }
        return "";
    }

    public static String normalizeWebsiteSyncTier(String tier)
    {
        String normalized = tier == null ? "" : tier.trim().toLowerCase();
        return switch (normalized)
        {
            case "supporter", "supporter_plus", "owner" -> normalized;
            default -> "free";
        };
    }

    public static long normalizeWebsiteSyncIntervalMs(long intervalMs)
    {
        if (intervalMs <= 0L)
        {
            return DEFAULT_WEBSITE_SYNC_INTERVAL_MS;
        }
        return Math.max(MIN_WEBSITE_SYNC_INTERVAL_MS, Math.min(MAX_WEBSITE_SYNC_INTERVAL_MS, intervalMs));
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

    public static class WorldStatsEntry
    {
        public String worldId;
        public String displayName;
        public String kind;
        public String host;
        public long totalBlocks;
        public long lastSeenAt;
        public Map<String, Long> blockBreakdown = new LinkedHashMap<>();
        public long blockBreakdownUpdatedAtMs;
        public String blockBreakdownSource = "";
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

    public enum HudAlignment implements IConfigOptionListEntry
    {
        TOP_LEFT("top_left", "Top Left"),
        TOP_RIGHT("top_right", "Top Right"),
        BOTTOM_LEFT("bottom_left", "Bottom Left"),
        BOTTOM_RIGHT("bottom_right", "Bottom Right");

        private final String value;
        private final String displayName;

        HudAlignment(String value, String displayName)
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
            for (HudAlignment alignment : values())
            {
                if (alignment.value.equalsIgnoreCase(value) || alignment.displayName.equalsIgnoreCase(value))
                {
                    return alignment;
                }
            }

            return TOP_LEFT;
        }
    }

    public enum BpsSmoothing implements IConfigOptionListEntry
    {
        UNSTABLE("unstable", "Unstable", 20, 20),
        FAST("fast", "Fast", 60, 40),
        STABLE("stable", "Stable", 100, 20);

        private final String value;
        private final String displayName;
        private final int windowTicks;
        private final int preferredMinimumTicks;

        BpsSmoothing(String value, String displayName, int windowTicks, int preferredMinimumTicks)
        {
            this.value = value;
            this.displayName = displayName;
            this.windowTicks = windowTicks;
            this.preferredMinimumTicks = preferredMinimumTicks;
        }

        public int getWindowTicks()
        {
            return this.windowTicks;
        }

        public int getPreferredMinimumTicks()
        {
            return this.preferredMinimumTicks;
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
            for (BpsSmoothing mode : values())
            {
                if (mode.value.equalsIgnoreCase(value) || mode.displayName.equalsIgnoreCase(value))
                {
                    return mode;
                }
            }

            return FAST;
        }
    }

}
