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
import fi.dy.masa.malilib.config.options.BooleanHotkeyGuiWrapper;
import fi.dy.masa.malilib.config.options.ConfigDouble;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigString;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.JsonUtils;
import com.miningtrackeraddon.Reference;

public class Configs implements IConfigHandler
{
    private static final String CONFIG_FILE_NAME = Reference.MOD_ID + ".json";

    public static class Generic
    {
        public static final ConfigInteger DAILY_GOAL = new ConfigInteger("dailyGoal", 1000, 1, Integer.MAX_VALUE, "Daily goal target.");
        public static final ConfigString NOTIFICATION_THRESHOLDS = new ConfigString("notificationThresholds", "25,50,75,100", "Popup threshold percentages, comma separated.");
        public static final ConfigInteger SOUND_ALERT_THRESHOLD = new ConfigInteger("soundAlertThreshold", 100, 1, 100, "Sound alert threshold percentage.");
        public static final ConfigInteger HUD_X = new ConfigInteger("hudX", 4, 0, 10000, "Mining HUD horizontal position.");
        public static final ConfigInteger HUD_Y = new ConfigInteger("hudY", 4, 0, 10000, "Mining HUD vertical position.");
        public static final ConfigDouble HUD_SCALE = new ConfigDouble("hudScale", 1.0D, 0.75D, 1.75D, "Mining HUD scale.");
        public static final ConfigString MMM_SYNC_URL = new ConfigString("mmmSyncUrl", "", "Backend endpoint for secure MMM dig syncing.");
        public static final ConfigString MMM_SYNC_API_KEY = new ConfigString("mmmSyncApiKey", "", "Optional API key sent to the MMM sync backend.");
        public static final ConfigString MMM_BREAKDOWN_LABELS = new ConfigString(
                "mmmBreakdownLabels",
                "AitorLAB,AoLAB,Sigma SMP,FouLAB,Chronos,HenLAB,Neo Bismuth,RODLAB,OG Bismuth,Hypnos,IktLAB,Bullet,LukLAB,Aeternum,RobLAB,Epsilon,AkaLAB,MineWave,GkeLAB,Hermitcraft,AntLAB,MinLAB,SMP Technique,EndTech,Enigma,TecnicPhantoms SMP,TTLAB,E ndLAB",
                "Comma separated MMM breakdown source labels in column order.");

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                DAILY_GOAL,
                NOTIFICATION_THRESHOLDS,
                SOUND_ALERT_THRESHOLD,
                HUD_X,
                HUD_Y,
                HUD_SCALE,
                MMM_SYNC_URL,
                MMM_SYNC_API_KEY,
                MMM_BREAKDOWN_LABELS
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
    }

    public static void loadFromFile()
    {
        File configFile = new File(FileUtils.getConfigDirectory(), CONFIG_FILE_NAME);
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
        File dir = FileUtils.getConfigDirectory();
        if ((dir.exists() && dir.isDirectory()) || dir.mkdirs())
        {
            JsonObject root = new JsonObject();
            ConfigUtils.writeConfigBase(root, "Generic", Generic.OPTIONS);
            ConfigUtils.writeHotkeys(root, "GenericHotkeys", Hotkeys.HOTKEY_LIST);
            ConfigUtils.writeHotkeyToggleOptions(root, "TweakHotkeys", "TweakToggles", FeatureToggle.VALUES);
            writeCustomState(root);
            JsonUtils.writeJsonToFile(root, new File(dir, CONFIG_FILE_NAME));
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

    public static List<String> getMmmBreakdownLabels()
    {
        List<String> values = new ArrayList<>();
        for (String part : Generic.MMM_BREAKDOWN_LABELS.getStringValue().split(","))
        {
            String trimmed = part.trim();
            if (!trimmed.isEmpty())
            {
                values.add(trimmed);
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
}
