package com.miningtrackeraddon.gui;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;

import com.google.common.collect.ImmutableList;
import com.miningtrackeraddon.Reference;
import com.miningtrackeraddon.config.Configs;
import com.miningtrackeraddon.config.FeatureToggle;
import com.miningtrackeraddon.config.Hotkeys;
import com.miningtrackeraddon.hud.SessionHistoryScreen;
import com.miningtrackeraddon.hud.SummaryScreen;
import com.miningtrackeraddon.tracker.MiningStats;
import com.miningtrackeraddon.ui.PlayerProfileScreen;
import com.miningtrackeraddon.ui.ProjectManagerScreen;
import com.miningtrackeraddon.ui.WebsiteLinkScreen;

import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.options.BooleanHotkeyGuiWrapper;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.MinecraftClient;

public class GuiConfigs extends GuiConfigsBase
{
    public static ImmutableList<FeatureToggle> TWEAK_LIST = FeatureToggle.VALUES;
    private static ConfigGuiTab tab = ConfigGuiTab.TWEAKS;

    public GuiConfigs()
    {
        super(10, 72, Reference.MOD_ID, null, Reference.MOD_NAME + " %s", String.format("%s", Reference.MOD_VERSION));
    }

    @Override
    public void initGui()
    {
        super.initGui();
        this.clearOptions();

        ConfigGuiTab[] tabs = ConfigGuiTab.values();
        int gap = 2;
        int availableWidth = Math.max(1, this.width - 20 - gap * (tabs.length - 1));
        int tabWidth = Math.max(34, Math.min(74, availableWidth / tabs.length));
        int rowWidth = tabs.length * tabWidth + gap * (tabs.length - 1);
        int x = Math.max(10, (this.width - rowWidth) / 2);
        int y = 18;
        for (ConfigGuiTab configTab : tabs)
        {
            this.createTabButton(x, y, tabWidth, configTab);
            x += tabWidth + gap;
        }

    }

    @Override
    protected int getConfigWidth()
    {
        return tab == ConfigGuiTab.GENERIC ? 180 : 260;
    }

    @Override
    protected boolean useKeybindSearch()
    {
        return tab == ConfigGuiTab.TWEAKS || tab == ConfigGuiTab.HOTKEYS;
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs()
    {
        List<? extends IConfigBase> configs;

        if (tab == ConfigGuiTab.GENERIC)
        {
            configs = Configs.Generic.OPTIONS;
        }
        else if (tab == ConfigGuiTab.TWEAKS)
        {
            List<ConfigOptionWrapper> wrappers = new ArrayList<>();
            for (FeatureToggle toggle : TWEAK_LIST)
            {
                wrappers.addAll(ConfigOptionWrapper.createFor(List.of(wrapConfig(toggle))));
                if (toggle == FeatureToggle.TWEAK_PERIMETER_WALL_DIG_HELPER)
                {
                    wrappers.addAll(ConfigOptionWrapper.createFor(List.of(Configs.Generic.PERIMETER_OUTLINE_BLOCKS_LIST)));
                }
            }
            return wrappers;
        }
        else if (tab == ConfigGuiTab.HOTKEYS)
        {
            configs = Hotkeys.HOTKEY_LIST;
        }
        else
        {
            return Collections.emptyList();
        }

        return ConfigOptionWrapper.createFor(configs);
    }

    protected BooleanHotkeyGuiWrapper wrapConfig(FeatureToggle config)
    {
        return new BooleanHotkeyGuiWrapper(config.getName(), config, config.getKeybind());
    }

    private void createTabButton(int x, int y, int width, ConfigGuiTab configTab)
    {
        ButtonGeneric button = new ButtonGeneric(x, y, width, 16, configTab.getDisplayName());
        button.setEnabled(tab != configTab);
        this.addButton(button, new TabButtonListener(configTab, this));
    }

    private static class TabButtonListener implements IButtonActionListener
    {
        private final ConfigGuiTab tab;
        private final GuiConfigs parent;

        private TabButtonListener(ConfigGuiTab tab, GuiConfigs parent)
        {
            this.tab = tab;
            this.parent = parent;
        }

        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            if (this.tab == ConfigGuiTab.PROJECTS)
            {
                MinecraftClient.getInstance().setScreen(new ProjectManagerScreen(this.parent));
                return;
            }

            if (this.tab == ConfigGuiTab.PROFILE)
            {
                MinecraftClient.getInstance().setScreen(new PlayerProfileScreen(this.parent));
                return;
            }

            if (this.tab == ConfigGuiTab.WEBSITE_LINK)
            {
                MinecraftClient.getInstance().setScreen(new WebsiteLinkScreen(this.parent));
                return;
            }

            if (this.tab == ConfigGuiTab.SUMMARY)
            {
                MinecraftClient.getInstance().setScreen(new SummaryScreen(MiningStats.getCurrentSession(), this.parent));
                return;
            }

            if (this.tab == ConfigGuiTab.HISTORY)
            {
                MinecraftClient.getInstance().setScreen(new SessionHistoryScreen(this.parent));
                return;
            }

            GuiConfigs.tab = this.tab;
            this.parent.reCreateListWidget();
            this.parent.getListWidget().resetScrollbarPosition();
            this.parent.initGui();
        }
    }

    private enum ConfigGuiTab
    {
        GENERIC("Generic"),
        TWEAKS("Toggles"),
        HOTKEYS("Hotkeys"),
        PROJECTS("Projects"),
        PROFILE("Profile"),
        WEBSITE_LINK("Website"),
        SUMMARY("Summary"),
        HISTORY("History");

        private final String displayName;

        ConfigGuiTab(String displayName)
        {
            this.displayName = displayName;
        }

        public String getDisplayName()
        {
            return StringUtils.translate(this.displayName);
        }
    }
}
