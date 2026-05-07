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
import com.miningtrackeraddon.ui.MmmUi;
import com.miningtrackeraddon.ui.PlayerProfileScreen;
import com.miningtrackeraddon.ui.ProjectManagerScreen;
import com.miningtrackeraddon.ui.WebsiteLinkScreen;

import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigResettable;
import fi.dy.masa.malilib.config.options.BooleanHotkeyGuiWrapper;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ConfigButtonKeybind;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import fi.dy.masa.malilib.gui.widgets.WidgetConfigOption;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptions;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptionsBase;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

public class GuiConfigs extends GuiConfigsBase
{
    public static ImmutableList<FeatureToggle> TWEAK_LIST = FeatureToggle.VALUES;
    private static final int TAB_Y = 28;
    private static final int TAB_HEIGHT = 18;
    private static final int LIST_Y = TAB_Y + TAB_HEIGHT + 8;
    private static ConfigGuiTab tab = ConfigGuiTab.TWEAKS;

    public GuiConfigs()
    {
        super(10, LIST_Y, Reference.MOD_ID, null, Reference.MOD_NAME + " %s", String.format("%s", Reference.MOD_VERSION));
    }

    @Override
    public void initGui()
    {
        super.initGui();
        this.clearOptions();

        ConfigGuiTab[] tabs = ConfigGuiTab.values();
        int gap = 4;
        int availableWidth = Math.max(1, this.width - 20 - gap * (tabs.length - 1));
        int tabWidth = Math.max(34, Math.min(82, availableWidth / tabs.length));
        int rowWidth = tabs.length * tabWidth + gap * (tabs.length - 1);
        int x = Math.max(10, (this.width - rowWidth) / 2);
        int y = TAB_Y;
        for (ConfigGuiTab configTab : tabs)
        {
            this.createTabButton(x, y, tabWidth, configTab);
            x += tabWidth + gap;
        }

    }

    @Override
    protected WidgetListConfigOptions createListWidget(int listX, int listY)
    {
        return new MmmConfigListWidget(listX, listY, this.getBrowserWidth(), this.getBrowserHeight(), this.getConfigWidth(), 0.0F, this.useKeybindSearch(), this);
    }

    @Override
    protected void drawScreenBackground(DrawContext context, int mouseX, int mouseY)
    {
        MmmUi.backdrop(context, this.width, this.height);
    }

    @Override
    protected void drawTitle(DrawContext context, int mouseX, int mouseY, float partialTicks)
    {
        int headerWidth = Math.max(1, this.width - 20);
        MmmUi.card(context, 10, 8, headerWidth, 16, MmmUi.PANEL, MmmUi.BORDER);
        context.drawText(this.textRenderer, Text.literal("MMM"), 16, 12, MmmUi.ACCENT_BRIGHT, false);
        context.drawText(this.textRenderer, Text.literal(tab.getDisplayName()), 52, 12, MmmUi.LABEL, false);
        context.drawText(this.textRenderer, Text.literal(Reference.MOD_VERSION), this.width - 16 - this.textRenderer.getWidth(Reference.MOD_VERSION), 12, MmmUi.MUTED, false);
    }

    @Override
    public void drawContents(DrawContext context, int mouseX, int mouseY, float partialTicks)
    {
        int panelX = Math.max(6, this.getListX() - 6);
        int panelY = Math.max(TAB_Y + TAB_HEIGHT + 4, this.getListY() - 6);
        int panelWidth = Math.max(1, Math.min(this.width - panelX - 6, this.getBrowserWidth() + 12));
        int panelHeight = Math.max(1, Math.min(this.height - panelY - 6, this.getBrowserHeight() + 12));

        MmmUi.card(context, panelX, panelY, panelWidth, panelHeight, MmmUi.CARD_SOFT, MmmUi.BORDER_SOFT);
        super.drawContents(context, mouseX, mouseY, partialTicks);
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
        ButtonGeneric button = new MmmTabButton(x, y, width, TAB_HEIGHT, configTab.getDisplayName(), tab == configTab);
        button.setEnabled(tab != configTab);
        this.addButton(button, new TabButtonListener(configTab, this));
    }

    private static class MmmConfigListWidget extends WidgetListConfigOptions
    {
        private MmmConfigListWidget(int x, int y, int width, int height, int configWidth, float zLevel, boolean useKeybindSearch, GuiConfigsBase parent)
        {
            super(x, y, width, height, configWidth, zLevel, useKeybindSearch, parent);
        }

        @Override
        public void drawContents(DrawContext context, int mouseX, int mouseY, float partialTicks)
        {
            if (this.widgetSearchBar != null)
            {
                MmmUi.fieldShell(context, this.widgetSearchBar.getX() - 2, this.widgetSearchBar.getY() - 2, this.widgetSearchBar.getWidth() + 4, this.widgetSearchBar.getHeight() + 4, this.widgetSearchBar.hasFilter());
            }

            super.drawContents(context, mouseX, mouseY, partialTicks);
        }

        @Override
        protected WidgetConfigOption createListEntryWidget(int x, int y, int listIndex, boolean isOdd, ConfigOptionWrapper entry)
        {
            return new MmmConfigOptionWidget(x, y, this.browserEntryWidth, this.browserEntryHeight, this.maxLabelWidth, this.configWidth, entry, listIndex, this.parent, this);
        }
    }

    private static class MmmConfigOptionWidget extends WidgetConfigOption
    {
        private MmmConfigOptionWidget(int x, int y, int width, int height, int maxNameLength, int configWidth, ConfigOptionWrapper wrapper, int listIndex, GuiConfigsBase host, WidgetListConfigOptionsBase<?, ?> parent)
        {
            super(x, y, width, height, maxNameLength, configWidth, wrapper, listIndex, host, parent);
            this.styleGeneratedButtons();
        }

        @Override
        protected void addConfigButtonEntry(int x, int y, IConfigResettable config, ButtonBase button)
        {
            this.styleButton(button);
            super.addConfigButtonEntry(x, y, config, button);
        }

        @Override
        protected void addKeybindResetButton(int x, int y, IKeybind keybind, ConfigButtonKeybind button)
        {
            this.styleButton(button);
            super.addKeybindResetButton(x, y, keybind, button);
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean selected)
        {
            int fill = selected ? MmmUi.ROW_SELECTED : this.listIndex % 2 == 0 ? MmmUi.CARD_SOFT : MmmUi.ROW_ALT;
            if (this.isMouseOver(mouseX, mouseY))
            {
                fill = MmmUi.ROW_HOVER;
            }

            MmmUi.card(context, this.x + 1, this.y, Math.max(1, this.width - 2), Math.max(1, this.height - 1), fill, MmmUi.BORDER_SOFT);
            this.drawStyledButtonShells(context, mouseX, mouseY);
            super.render(context, mouseX, mouseY, selected);
        }

        private void styleGeneratedButtons()
        {
            for (WidgetBase widget : this.subWidgets)
            {
                if (widget instanceof ButtonBase button)
                {
                    this.styleButton(button);
                }
            }
        }

        private void styleButton(ButtonBase button)
        {
            if (button instanceof ButtonGeneric generic)
            {
                generic.setRenderDefaultBackground(false);
                generic.setTextCentered(true);
            }
        }

        private void drawStyledButtonShells(DrawContext context, int mouseX, int mouseY)
        {
            for (WidgetBase widget : this.subWidgets)
            {
                if (widget instanceof ButtonBase button && button.getWidth() > 0 && button.getHeight() > 0)
                {
                    boolean hovered = button.isMouseOver(mouseX, mouseY);
                    MmmUi.card(context, button.getX(), button.getY(), button.getWidth(), button.getHeight(), hovered ? MmmUi.CARD : MmmUi.INSET, hovered ? MmmUi.ACCENT_BRIGHT : MmmUi.BORDER);
                }
            }
        }
    }

    private static class MmmTabButton extends ButtonGeneric
    {
        private final boolean selected;

        private MmmTabButton(int x, int y, int width, int height, String label, boolean selected)
        {
            super(x, y, width, height, label);
            this.selected = selected;
            this.setRenderDefaultBackground(false);
            this.setTextCentered(true);
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean selected)
        {
            if (!this.visible)
            {
                return;
            }

            boolean hovered = this.enabled && this.isMouseOver(mouseX, mouseY);
            int fill = this.selected ? MmmUi.ACCENT_SOFT : hovered ? MmmUi.CARD : MmmUi.CARD_SOFT;
            int border = this.selected ? MmmUi.ACCENT : hovered ? MmmUi.ACCENT_BRIGHT : MmmUi.BORDER;
            int textColor = this.selected || hovered ? MmmUi.TEXT : MmmUi.LABEL;
            String label = MmmUi.truncate(this.textRenderer, this.displayString, this.width - 8);
            int textX = this.x + Math.max(4, (this.width - this.textRenderer.getWidth(label)) / 2);
            int textY = this.y + (this.height - this.fontHeight) / 2 + 1;

            MmmUi.card(context, this.x, this.y, this.width, this.height, fill, border);
            context.drawText(this.textRenderer, Text.literal(label), textX, textY, textColor, false);
        }
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
