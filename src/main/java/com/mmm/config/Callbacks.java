package com.mmm.config;

import java.io.IOException;
import java.nio.file.Path;

import com.mmm.hud.SessionHistoryScreen;
import com.mmm.hud.SummaryScreen;
import com.mmm.tracker.MiningStats;
import com.mmm.tweak.BlockEspRenderer;
import com.mmm.tweak.PerimeterWallDigHelper;
import com.mmm.ui.MmmSettingsScreen;

import fi.dy.masa.malilib.config.IConfigBoolean;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.hotkeys.KeyCallbackAdjustable;
import fi.dy.masa.malilib.util.InfoUtils;
import net.minecraft.client.MinecraftClient;

public final class Callbacks
{
    private Callbacks()
    {
    }

    public static void init()
    {
        IHotkeyCallback genericCallback = new KeyCallbackHotkeysGeneric();

        for (FeatureToggle toggle : FeatureToggle.values())
        {
            toggle.getKeybind().setCallback(KeyCallbackAdjustableFeature.create(toggle));
            toggle.setValueChangeCallback(config -> Configs.saveToFile());
        }

        for (var hotkey : Hotkeys.HOTKEY_LIST)
        {
            hotkey.getKeybind().setCallback(genericCallback);
        }

        Configs.Generic.PERIMETER_OUTLINE_BLOCKS_LIST.setValueChangeCallback(config -> {
            PerimeterWallDigHelper.setOutlineBlocks(config.getStrings());
            Configs.saveToFile();
        });
        PerimeterWallDigHelper.refreshFromConfig();
        Configs.Generic.BLOCK_ESP_HEX_COLOR.setValueChangeCallback(config -> {
            config.setValueFromString(Configs.normalizeBlockEspHexColor(config.getStringValue()));
            Configs.saveToFile();
        });
        Configs.Generic.HUD_TITLE_HEX_COLOR.setValueChangeCallback(config -> {
            config.setValueFromString(Configs.normalizeHexColor(config.getStringValue(), Configs.Generic.DEFAULT_HUD_TITLE_HEX_COLOR));
            Configs.saveToFile();
        });
        Configs.Generic.HUD_TEXT_HEX_COLOR.setValueChangeCallback(config -> {
            config.setValueFromString(Configs.normalizeHexColor(config.getStringValue(), Configs.Generic.DEFAULT_HUD_TEXT_HEX_COLOR));
            Configs.saveToFile();
        });
        Configs.Generic.HUD_NUMBER_HEX_COLOR.setValueChangeCallback(config -> {
            config.setValueFromString(Configs.normalizeHexColor(config.getStringValue(), Configs.Generic.DEFAULT_HUD_NUMBER_HEX_COLOR));
            Configs.saveToFile();
        });
        Configs.Generic.HUD_INACTIVE_HEX_COLOR.setValueChangeCallback(config -> {
            config.setValueFromString(Configs.normalizeHexColor(config.getStringValue(), Configs.Generic.DEFAULT_HUD_INACTIVE_HEX_COLOR));
            Configs.saveToFile();
        });
        Configs.Generic.BPS_SMOOTHING.setValueChangeCallback(config -> {
            MiningStats.onBpsSmoothingChanged();
            Configs.saveToFile();
        });
        Configs.Generic.BLOCK_ESP_COLOR_MODE.setValueChangeCallback(config -> Configs.saveToFile());
        Configs.Generic.BLOCK_ESP_RENDER_MODE.setValueChangeCallback(config -> Configs.saveToFile());
        Configs.Generic.BLOCK_ESP_OPACITY.setValueChangeCallback(config -> Configs.saveToFile());
        Configs.Generic.BLOCK_ESP_RAINBOW_SPEED.setValueChangeCallback(config -> Configs.saveToFile());
        Configs.Generic.GRAPH_LINE_HEX_COLOR.setValueChangeCallback(config -> {
            config.setValueFromString(Configs.normalizeHexColor(config.getStringValue(), Configs.Generic.DEFAULT_GRAPH_LINE_HEX_COLOR));
            Configs.saveToFile();
        });
        Configs.Generic.GRAPH_FILL_HEX_COLOR.setValueChangeCallback(config -> {
            config.setValueFromString(Configs.normalizeHexColor(config.getStringValue(), Configs.Generic.DEFAULT_GRAPH_FILL_HEX_COLOR));
            Configs.saveToFile();
        });
        Configs.Generic.GRAPH_GRID_HEX_COLOR.setValueChangeCallback(config -> {
            config.setValueFromString(Configs.normalizeHexColor(config.getStringValue(), Configs.Generic.DEFAULT_GRAPH_GRID_HEX_COLOR));
            Configs.saveToFile();
        });
        Configs.Generic.GRAPH_FILL_OPACITY.setValueChangeCallback(config -> Configs.saveToFile());
        Configs.Generic.GRAPH_GRID_OPACITY.setValueChangeCallback(config -> Configs.saveToFile());
        Configs.Generic.GRAPH_SCALE_STEP.setValueChangeCallback(config -> Configs.saveToFile());
        BlockEspRenderer.refreshConfig();
    }

    private static class KeyCallbackHotkeysGeneric implements IHotkeyCallback
    {
        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key)
        {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null)
            {
                return false;
            }

            if (key == Hotkeys.OPEN_CONFIG_GUI.getKeybind())
            {
                client.setScreen(new MmmSettingsScreen(client.currentScreen));
                return true;
            }
            if (key == Hotkeys.OPEN_SUMMARY.getKeybind())
            {
                client.setScreen(new SummaryScreen(MiningStats.getCurrentSession(), client.currentScreen));
                return true;
            }
            if (key == Hotkeys.OPEN_HISTORY.getKeybind())
            {
                client.setScreen(new SessionHistoryScreen(client.currentScreen));
                return true;
            }
            if (key == Hotkeys.PAUSE_SESSION.getKeybind())
            {
                if (MiningStats.isSessionActive() == false)
                {
                    InfoUtils.printActionbarMessage("No active mining session");
                    return true;
                }

                boolean paused = MiningStats.togglePauseSession();
                InfoUtils.printActionbarMessage(paused ? "Mining session paused" : "Mining session resumed");
                return true;
            }
            if (key == Hotkeys.TOGGLE_SESSION.getKeybind())
            {
                boolean active = MiningStats.toggleSession();
                InfoUtils.printActionbarMessage(active ? "Mining session started" : "Mining session ended");
                return true;
            }
            if (key == Hotkeys.EXPORT_HISTORY.getKeybind())
            {
                try
                {
                    Path exported = com.mmm.storage.SessionHistory.exportToFile();
                    InfoUtils.printActionbarMessage("Exported mining history to %s", exported.getFileName().toString());
                }
                catch (IOException exception)
                {
                    InfoUtils.showGuiOrInGameMessage(fi.dy.masa.malilib.gui.Message.MessageType.ERROR, "Failed to export mining history");
                }
                return true;
            }
            return false;
        }
    }

    private record KeyCallbackAdjustableFeature(IConfigBoolean config) implements IHotkeyCallback
    {
        private static IHotkeyCallback create(IConfigBoolean config)
        {
            return new KeyCallbackAdjustable(config, new KeyCallbackAdjustableFeature(config));
        }

        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key)
        {
            this.config.toggleBooleanValue();
            Configs.saveToFile();

            boolean enabled = this.config.getBooleanValue();
            String status = enabled ? GuiBase.TXT_GREEN + "ON" + GuiBase.TXT_RST : GuiBase.TXT_RED + "OFF" + GuiBase.TXT_RST;
            InfoUtils.printActionbarMessage("Toggled %s %s", this.config.getPrettyName(), status);
            return true;
        }
    }
}
