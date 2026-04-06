package com.miningtrackeraddon.config;

import java.io.IOException;
import java.nio.file.Path;

import com.miningtrackeraddon.gui.GuiConfigs;
import com.miningtrackeraddon.hud.SessionHistoryScreen;
import com.miningtrackeraddon.hud.SummaryScreen;
import com.miningtrackeraddon.tweak.PerimeterWallDigHelper;
import com.miningtrackeraddon.tracker.MiningStats;

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
        PerimeterWallDigHelper.setOutlineBlocks(Configs.Generic.PERIMETER_OUTLINE_BLOCKS_LIST.getStrings());
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
                GuiConfigs gui = new GuiConfigs();
                gui.setParent(client.currentScreen);
                GuiBase.openGui(gui);
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
            if (key == Hotkeys.RESET_SESSION.getKeybind())
            {
                MiningStats.resetSession();
                InfoUtils.printActionbarMessage("Mining session reset");
                return true;
            }
            if (key == Hotkeys.EXPORT_HISTORY.getKeybind())
            {
                try
                {
                    Path exported = com.miningtrackeraddon.storage.SessionHistory.exportToFile();
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
