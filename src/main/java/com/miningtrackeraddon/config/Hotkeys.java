package com.miningtrackeraddon.config;

import java.util.List;

import com.google.common.collect.ImmutableList;

import fi.dy.masa.malilib.config.options.ConfigHotkey;

public final class Hotkeys
{
    public static final ConfigHotkey OPEN_CONFIG_GUI = new ConfigHotkey("openConfigGui", "X,V", "Open the AeTweaks config GUI");
    public static final ConfigHotkey OPEN_SUMMARY = new ConfigHotkey("openSummary", "LEFT_ALT,S", "Open the current session summary");
    public static final ConfigHotkey OPEN_HISTORY = new ConfigHotkey("openHistory", "LEFT_ALT,H", "Open the saved session history");
    public static final ConfigHotkey PAUSE_SESSION = new ConfigHotkey("pauseSession", "", "Pause or resume the current mining session");
    public static final ConfigHotkey TOGGLE_SESSION = new ConfigHotkey("toggleSession", "", "Start or end the current mining session");
    public static final ConfigHotkey EXPORT_HISTORY = new ConfigHotkey("exportHistory", "", "Export session history for the current world/server");

    public static final List<ConfigHotkey> HOTKEY_LIST = ImmutableList.of(
            OPEN_CONFIG_GUI,
            OPEN_SUMMARY,
            OPEN_HISTORY,
            PAUSE_SESSION,
            TOGGLE_SESSION,
            EXPORT_HISTORY
    );

    private Hotkeys()
    {
    }
}
