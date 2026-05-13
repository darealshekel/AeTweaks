package com.mmm.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.mmm.Reference;
import com.mmm.config.Configs;
import com.mmm.hud.HudMoveScreen;
import com.mmm.hud.SessionHistoryScreen;
import com.mmm.tracker.MiningStats;

import fi.dy.masa.malilib.config.IConfigColor;
import fi.dy.masa.malilib.config.IConfigDouble;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigInteger;
import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.config.IConfigResettable;
import fi.dy.masa.malilib.config.IStringRepresentable;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiColorEditorHSV;
import fi.dy.masa.malilib.util.InfoUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public class MmmSettingsScreen extends Screen
{
    private static final int BG = 0xFF050505;
    private static final int TOP_BAR = 0xF0060606;
    private static final int SIDEBAR = 0xE9080808;
    private static final int CARD = 0xF20D0D0D;
    private static final int INSET = 0xFF121212;
    private static final int BORDER = 0xFF1F1F1F;
    private static final int BORDER_SOFT = 0xFF272727;
    private static final int TEXT = 0xFFF5F5F5;
    private static final int MUTED = 0xFF949494;
    private static final int RED = 0xFFE00000;
    private static final int RED_DARK = 0xFFC20000;
    private static final int GREEN = 0xFF43D483;
    private static final int ERROR = 0xFFFF5965;

    private static final int TOP_HEIGHT = 42;
    private static final int SIDEBAR_WIDTH = 150;
    private static final int PAGE_PAD = 14;
    private static final int GAP = 12;
    private static final int CARD_PAD = 12;
    private static final int ROW_HEIGHT = 32;
    private static final int FIELD_HEIGHT = 18;
    private static final int CONTROL_WIDTH = 112;
    private static final int RESET_WIDTH = 18;

    private final Screen parent;
    private final List<SettingsSection> sections = new ArrayList<>();
    private final List<ClickTarget> clickTargets = new ArrayList<>();
    private final List<ScrollTarget> scrollTargets = new ArrayList<>();
    private final Map<IConfigBase, TextFieldWidget> textFields = new IdentityHashMap<>();
    private final Map<SettingsSection, Integer> sectionY = new HashMap<>();
    private double scrollY = 0.0D;
    private int contentHeight = 0;

    public MmmSettingsScreen(Screen parent)
    {
        super(Text.literal("MMM Mod Settings"));
        this.parent = parent;
        this.createSections();
    }

    @Override
    protected void init()
    {
        MmmUi.ensureCursorVisible();
        this.clearChildren();
        this.textFields.clear();

        for (SettingsSection section : this.sections)
        {
            for (SettingRow row : section.rows())
            {
                if (row.kind().usesTextField())
                {
                    TextFieldWidget field = new TextFieldWidget(this.textRenderer, 0, 0, CONTROL_WIDTH, FIELD_HEIGHT, Text.empty());
                    field.setDrawsBackground(false);
                    field.setCentered(false);
                    field.setEditableColor(TEXT);
                    field.setUneditableColor(MUTED);
                    field.setMaxLength(row.kind() == ControlKind.COLOR ? 9 : 64);
                    field.setText(this.getConfigString(row.config()));
                    field.setChangedListener(value -> this.commitTextValue(row, value));
                    this.textFields.put(row.config(), field);
                    this.addDrawableChild(field);
                }
            }
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta)
    {
        MmmUi.ensureCursorVisible();
        this.clickTargets.clear();
        this.scrollTargets.clear();
        this.sectionY.clear();
        this.updateLayout();

        context.fill(0, 0, this.width, this.height, BG);
        this.drawSidebar(context, mouseX, mouseY);
        this.drawTopBar(context, mouseX, mouseY);

        int viewportX = SIDEBAR_WIDTH + PAGE_PAD;
        int viewportY = TOP_HEIGHT;
        int viewportW = this.width - viewportX - PAGE_PAD;
        int viewportH = this.height - TOP_HEIGHT - PAGE_PAD;

        context.enableScissor(viewportX, viewportY, viewportX + viewportW, viewportY + viewportH);
        this.drawMainContent(context, viewportX, viewportY, viewportW, mouseX, mouseY);
        super.render(context, mouseX, mouseY, delta);
        context.disableScissor();

        this.drawScrollbar(context, viewportX + viewportW - 4, viewportY, viewportH);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button)
    {
        if (button == 0 && MmmUi.handleMmmScreensSidebarClick(this, this.parent, mouseX, mouseY, "SETTINGS"))
        {
            return true;
        }

        for (ClickTarget target : List.copyOf(this.clickTargets))
        {
            if (target.contains(mouseX, mouseY))
            {
                target.action().run(mouseX, mouseY);
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount)
    {
        for (ScrollTarget target : List.copyOf(this.scrollTargets))
        {
            if (target.contains(mouseX, mouseY))
            {
                this.adjustNumberByScroll(target.config(), verticalAmount);
                return true;
            }
        }

        int viewportH = this.height - TOP_HEIGHT - PAGE_PAD;
        int maxScroll = Math.max(0, this.contentHeight - viewportH);
        this.scrollY = Math.max(0.0D, Math.min(maxScroll, this.scrollY - verticalAmount * 28.0D));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers)
    {
        if (keyCode == 256)
        {
            this.close();
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void close()
    {
        MinecraftClient.getInstance().setScreen(this.parent);
    }

    @Override
    public boolean shouldPause()
    {
        return false;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta)
    {
    }

    private void drawTopBar(DrawContext context, int mouseX, int mouseY)
    {
        context.fill(0, 0, this.width, TOP_HEIGHT, TOP_BAR);
        context.drawBorder(0, 0, this.width, TOP_HEIGHT, BORDER);
        context.fill(14, 12, 18, 30, RED);
        MmmUi.drawTextWithin(context, this.textRenderer, "MMM", 26, 10, 40, RED, false);
        MmmUi.drawTextWithin(context, this.textRenderer, "Manual Mining Maniacs", 68, 10, Math.max(0, this.width / 2 - 80), TEXT, false);

        String status = this.syncStatusText();
        int statusColor = Configs.Generic.WEBSITE_SYNC_ENABLED.getBooleanValue() ? GREEN : MUTED;
        int closeX = this.width - 32;
        MmmUi.drawTextRightWithin(context, this.textRenderer, status, closeX - 12, 10, Math.max(0, this.width / 2 - 70), statusColor, false);
        this.drawButtonShell(context, closeX, 8, 20, 20, "X", mouseX, mouseY, false);
        this.clickTargets.add(new ClickTarget(closeX, 8, 20, 20, this::close));
    }

    private void drawSidebar(DrawContext context, int mouseX, int mouseY)
    {
        MmmUi.drawMmmScreensSidebar(context, this.textRenderer, this.width, this.height, mouseX, mouseY, "SETTINGS");
    }

    private void drawMainContent(DrawContext context, int x, int viewportY, int width, int mouseX, int mouseY)
    {
        int y = viewportY + 18 - (int) Math.round(this.scrollY);
        MmmUi.drawTextWithin(context, this.textRenderer, "MMM MOD SETTINGS", x, y, width, RED, false);
        MmmUi.drawTextWithin(context, this.textRenderer, "Configure how MMM Mod works in Minecraft", x, y + 16, width, MUTED, false);

        int gridY = y + 44;
        int columnW = Math.max(210, (width - GAP) / 2);
        int rowY = gridY;
        int sectionIndex = 0;

        while (sectionIndex < this.sections.size())
        {
            SettingsSection left = this.sections.get(sectionIndex++);
            SettingsSection right = sectionIndex < this.sections.size() ? this.sections.get(sectionIndex++) : null;
            int leftHeight = this.sectionHeight(left);
            int rightHeight = right == null ? 0 : this.sectionHeight(right);
            int rowHeight = Math.max(leftHeight, rightHeight);

            this.sectionY.put(left, rowY);
            this.drawSection(context, left, x, rowY, columnW, rowHeight, mouseX, mouseY);

            if (right != null)
            {
                this.sectionY.put(right, rowY);
                this.drawSection(context, right, x + columnW + GAP, rowY, columnW, rowHeight, mouseX, mouseY);
            }

            rowY += rowHeight + GAP;
        }
    }

    private void drawSection(DrawContext context, SettingsSection section, int x, int y, int width, int height, int mouseX, int mouseY)
    {
        context.fill(x, y, x + width, y + height, CARD);
        context.drawBorder(x, y, width, height, BORDER);
        MmmUi.drawSectionHeading(context, this.textRenderer, section.title(), x + CARD_PAD, y + 12, width - CARD_PAD * 2);
        MmmUi.drawTextWithin(context, this.textRenderer, section.description(), x + CARD_PAD, y + 28, width - CARD_PAD * 2, MUTED, false);

        int rowY = y + 48;
        for (SettingRow row : section.rows())
        {
            this.drawSettingRow(context, row, x + CARD_PAD, rowY, width - CARD_PAD * 2, mouseX, mouseY);
            rowY += ROW_HEIGHT;
        }
    }

    private void drawSettingRow(DrawContext context, SettingRow row, int x, int y, int width, int mouseX, int mouseY)
    {
        context.fill(x, y, x + width, y + 1, BORDER);
        int labelW = Math.max(80, width - CONTROL_WIDTH - RESET_WIDTH - 24);
        MmmUi.drawTextWithin(context, this.textRenderer, row.label(), x, y + 7, labelW, TEXT, false);
        MmmUi.drawTextWithin(context, this.textRenderer, row.description(), x, y + 18, labelW, MUTED, false);

        int resetX = x + width - RESET_WIDTH;
        int controlX = resetX - CONTROL_WIDTH - 8;
        int controlY = y + 7;

        switch (row.kind())
        {
            case BOOLEAN -> this.drawBooleanControl(context, row.config(), controlX, controlY, CONTROL_WIDTH, mouseX, mouseY);
            case TEXT, NUMBER, COLOR -> this.drawTextControl(context, row, controlX, controlY, CONTROL_WIDTH, mouseX, mouseY);
            case OPTION -> this.drawOptionControl(context, row.config(), controlX, controlY, CONTROL_WIDTH, mouseX, mouseY);
            case ACTION -> this.drawActionButton(context, controlX, controlY, CONTROL_WIDTH, FIELD_HEIGHT, this.actionButtonLabel(row), mouseX, mouseY, () -> {
                if ("Move HUD".equals(row.label()))
                {
                    MinecraftClient.getInstance().setScreen(new HudMoveScreen(this));
                }
                else if ("Generate Dev Run".equals(row.label()))
                {
                    MiningStats.simulateDevFinishedSession();
                    InfoUtils.printActionbarMessage("Generated dev mining run");
                    MinecraftClient.getInstance().setScreen(new SessionHistoryScreen(this.parent));
                }
            });
        }

        if (row.kind() != ControlKind.ACTION && row.config() instanceof IConfigResettable resettable && resettable.isModified())
        {
            this.drawButtonShell(context, resetX, controlY, RESET_WIDTH, FIELD_HEIGHT, "R", mouseX, mouseY, true);
            this.clickTargets.add(new ClickTarget(resetX, controlY, RESET_WIDTH, FIELD_HEIGHT, () -> {
                resettable.resetToDefault();
                this.syncField(row.config());
                Configs.saveToFile();
            }));
        }
    }

    private void drawBooleanControl(DrawContext context, IConfigBase config, int x, int y, int width, int mouseX, int mouseY)
    {
        boolean enabled = config instanceof ConfigBoolean booleanConfig && booleanConfig.getBooleanValue();
        boolean hovered = this.contains(mouseX, mouseY, x, y, width, FIELD_HEIGHT);
        int fill = enabled ? RED : INSET;
        int border = hovered ? RED : BORDER_SOFT;
        context.fill(x, y, x + width, y + FIELD_HEIGHT, fill);
        context.drawBorder(x, y, width, FIELD_HEIGHT, border);
        String label = enabled ? "ON" : "OFF";
        int labelW = this.textRenderer.getWidth(label);
        context.drawText(this.textRenderer, Text.literal(label), x + (width - labelW) / 2, y + 6, enabled ? TEXT : MUTED, false);
        this.clickTargets.add(new ClickTarget(x, y, width, FIELD_HEIGHT, () -> {
            if (config instanceof ConfigBoolean booleanConfig)
            {
                booleanConfig.setBooleanValue(!booleanConfig.getBooleanValue());
                Configs.saveToFile();
            }
        }));
    }

    private void drawOptionControl(DrawContext context, IConfigBase config, int x, int y, int width, int mouseX, int mouseY)
    {
        String label = this.getConfigString(config);
        if (config instanceof ConfigOptionList optionList && optionList.getOptionListValue() instanceof IConfigOptionListEntry entry)
        {
            label = entry.getDisplayName();
        }

        this.drawButtonShell(context, x, y, width, FIELD_HEIGHT, label, mouseX, mouseY, false);
        this.clickTargets.add(new ClickTarget(x, y, width, FIELD_HEIGHT, () -> {
            if (config instanceof ConfigOptionList optionList && optionList.getOptionListValue() instanceof IConfigOptionListEntry entry)
            {
                IConfigOptionListEntry next = entry.cycle(true);
                if (config instanceof IStringRepresentable representable)
                {
                    representable.setValueFromString(next.getStringValue());
                }
                Configs.saveToFile();
            }
        }));
    }

    private void drawTextControl(DrawContext context, SettingRow row, int x, int y, int width, int mouseX, int mouseY)
    {
        TextFieldWidget field = this.textFields.get(row.config());
        if (field == null)
        {
            return;
        }

        int fieldX = x;
        int fieldW = width;
        if (row.kind() == ControlKind.COLOR)
        {
            fieldX = x + FIELD_HEIGHT + 4;
            fieldW = width - FIELD_HEIGHT - 4;
            int preview = parseHexColor(this.getConfigString(row.config()), RED);
            context.fill(x, y, x + FIELD_HEIGHT, y + FIELD_HEIGHT, preview);
            context.drawBorder(x, y, FIELD_HEIGHT, FIELD_HEIGHT, BORDER_SOFT);
            this.clickTargets.add(new ClickTarget(x, y, FIELD_HEIGHT, FIELD_HEIGHT, () -> {
                this.openColorEditor(row.config());
            }));
        }
        else if (row.kind() == ControlKind.NUMBER)
        {
            this.scrollTargets.add(new ScrollTarget(fieldX, y, width, FIELD_HEIGHT, row.config()));
        }

        field.setX(fieldX + 5);
        field.setY(y + 5);
        field.setWidth(Math.max(24, fieldW - 10));
        field.setEditableColor(TEXT);
        field.setUneditableColor(MUTED);
        MmmUi.fieldShell(context, fieldX, y, fieldW, FIELD_HEIGHT, field.isFocused());
    }

    private void openColorEditor(IConfigBase config)
    {
        if (!(config instanceof IConfigColor colorConfig))
        {
            return;
        }

        GuiColorEditorHSV editor = new GuiColorEditorHSV(colorConfig, null, this)
        {
            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button)
            {
                if (button == 0
                        && (mouseX < this.dialogLeft
                        || mouseX >= this.dialogLeft + this.dialogWidth
                        || mouseY < this.dialogTop
                        || mouseY >= this.dialogTop + this.dialogHeight))
                {
                    MinecraftClient.getInstance().setScreen(MmmSettingsScreen.this);
                    return true;
                }
                return super.mouseClicked(mouseX, mouseY, button);
            }

            @Override
            public void removed()
            {
                super.removed();
                Configs.saveToFile();
                MmmSettingsScreen.this.syncField(config);
            }
        };
        GuiBase.openGui(editor);
    }

    private void drawActionButton(DrawContext context, int x, int y, int width, int height, String label, int mouseX, int mouseY, Runnable action)
    {
        this.drawButtonShell(context, x, y, width, height, label, mouseX, mouseY, false);
        this.clickTargets.add(new ClickTarget(x, y, width, height, action));
    }

    private String actionButtonLabel(SettingRow row)
    {
        return "Generate Dev Run".equals(row.label()) ? "RUN" : "OPEN";
    }

    private void drawButtonShell(DrawContext context, int x, int y, int width, int height, String label, int mouseX, int mouseY, boolean subtle)
    {
        boolean hovered = this.contains(mouseX, mouseY, x, y, width, height);
        int fill = subtle ? INSET : hovered ? 0x22E00000 : INSET;
        int border = hovered ? RED : BORDER_SOFT;
        context.fill(x, y, x + width, y + height, fill);
        context.drawBorder(x, y, width, height, border);
        String clipped = MmmUi.truncate(this.textRenderer, label, width - 8);
        int textX = x + Math.max(4, (width - this.textRenderer.getWidth(clipped)) / 2);
        context.drawText(this.textRenderer, Text.literal(clipped), textX, y + 6, hovered ? TEXT : MUTED, false);
    }

    private void drawScrollbar(DrawContext context, int x, int y, int height)
    {
        int maxScroll = Math.max(0, this.contentHeight - height);
        if (maxScroll <= 0)
        {
            return;
        }

        context.fill(x, y, x + 3, y + height, 0x55090909);
        int thumbH = Math.max(28, (int) (height * (height / (double) this.contentHeight)));
        int thumbY = y + (int) ((height - thumbH) * (this.scrollY / maxScroll));
        context.fill(x, thumbY, x + 3, thumbY + thumbH, RED_DARK);
    }

    private boolean isSidebarActive(SidebarItem item)
    {
        int viewportY = TOP_HEIGHT;
        int currentY = viewportY + 44 - (int) Math.round(this.scrollY);
        if (item == SidebarItem.GENERAL)
        {
            return this.scrollY < 42;
        }

        for (SettingsSection section : this.sections)
        {
            Integer y = this.sectionY.get(section);
            if (y != null && section.sidebarItem() == item && y <= TOP_HEIGHT + 64)
            {
                currentY = y;
            }
        }

        SidebarItem active = SidebarItem.GENERAL;
        int best = Integer.MIN_VALUE;
        for (Map.Entry<SettingsSection, Integer> entry : this.sectionY.entrySet())
        {
            int y = entry.getValue();
            if (y <= TOP_HEIGHT + 90 && y > best)
            {
                best = y;
                active = entry.getKey().sidebarItem();
            }
        }
        return active == item && currentY <= TOP_HEIGHT + this.height;
    }

    private void scrollTo(SidebarItem item)
    {
        if (item == SidebarItem.GENERAL)
        {
            this.scrollY = 0.0D;
            return;
        }

        for (SettingsSection section : this.sections)
        {
            if (section.sidebarItem() == item)
            {
                this.scrollY = Math.max(0, section.absoluteOffset());
                return;
            }
        }
    }

    private void updateLayout()
    {
        int rowY = 44;
        int index = 0;

        while (index < this.sections.size())
        {
            SettingsSection left = this.sections.get(index++);
            SettingsSection right = index < this.sections.size() ? this.sections.get(index++) : null;
            int rowHeight = this.sectionHeight(left);
            if (right != null)
            {
                rowHeight = Math.max(rowHeight, this.sectionHeight(right));
            }
            left.setAbsoluteOffset(rowY);
            if (right != null)
            {
                right.setAbsoluteOffset(rowY);
            }
            rowY += rowHeight + GAP;
        }
        this.contentHeight = rowY + 26;

        int maxScroll = Math.max(0, this.contentHeight - (this.height - TOP_HEIGHT - PAGE_PAD));
        this.scrollY = Math.max(0.0D, Math.min(maxScroll, this.scrollY));
    }

    private int sectionHeight(SettingsSection section)
    {
        return 58 + section.rows().size() * ROW_HEIGHT + CARD_PAD;
    }

    private void commitTextValue(SettingRow row, String value)
    {
        try
        {
            if (row.kind() == ControlKind.COLOR && this.isCompleteHex(value) == false)
            {
                return;
            }

            this.setConfigString(row.config(), value);
        }
        catch (RuntimeException ignored)
        {
            // Keep the previous valid config value while the user is typing an incomplete input.
        }
    }

    private void syncField(IConfigBase config)
    {
        TextFieldWidget field = this.textFields.get(config);
        if (field != null)
        {
            field.setText(this.getConfigString(config));
        }
    }

    private String getConfigString(IConfigBase config)
    {
        if (config instanceof IStringRepresentable representable)
        {
            return representable.getStringValue();
        }
        return "";
    }

    private void setConfigString(IConfigBase config, String value)
    {
        if (config instanceof IStringRepresentable representable)
        {
            representable.setValueFromString(value);
            Configs.saveToFile();
        }
    }

    private void adjustNumberByScroll(IConfigBase config, double verticalAmount)
    {
        int direction = verticalAmount > 0 ? 1 : -1;
        if (config instanceof IConfigInteger integerConfig)
        {
            int step = this.integerScrollStep(integerConfig);
            int next = Math.max(integerConfig.getMinIntegerValue(), Math.min(integerConfig.getMaxIntegerValue(), integerConfig.getIntegerValue() + direction * step));
            integerConfig.setIntegerValue(next);
            this.syncField(config);
            Configs.saveToFile();
            return;
        }

        if (config instanceof IConfigDouble doubleConfig)
        {
            double step = this.doubleScrollStep(doubleConfig);
            double raw = doubleConfig.getDoubleValue() + direction * step;
            double next = Math.max(doubleConfig.getMinDoubleValue(), Math.min(doubleConfig.getMaxDoubleValue(), Math.round(raw * 1000.0D) / 1000.0D));
            doubleConfig.setDoubleValue(next);
            this.syncField(config);
            Configs.saveToFile();
        }
    }

    private int integerScrollStep(IConfigInteger config)
    {
        int range = Math.max(1, config.getMaxIntegerValue() - config.getMinIntegerValue());
        if (range > 100_000)
        {
            return 1_000;
        }
        if (range > 1_000)
        {
            return 10;
        }
        return 1;
    }

    private double doubleScrollStep(IConfigDouble config)
    {
        double range = Math.max(0.01D, config.getMaxDoubleValue() - config.getMinDoubleValue());
        if (range <= 2.0D)
        {
            return 0.05D;
        }
        if (range <= 10.0D)
        {
            return 0.1D;
        }
        return 1.0D;
    }

    private boolean isCompleteHex(String value)
    {
        if (value == null)
        {
            return false;
        }
        String hex = value.trim();
        if (hex.startsWith("#"))
        {
            hex = hex.substring(1);
        }
        if (hex.startsWith("0x") || hex.startsWith("0X"))
        {
            hex = hex.substring(2);
        }
        return hex.matches("(?i)[0-9a-f]{6}") || hex.matches("(?i)[0-9a-f]{8}");
    }

    private String syncStatusText()
    {
        if (Configs.Generic.WEBSITE_SYNC_ENABLED.getBooleanValue() == false)
        {
            return "SYNC OFF";
        }
        if (Configs.websiteLastSuccessfulSyncMs <= 0L)
        {
            return "SYNC READY";
        }
        long ageSeconds = Math.max(0L, (System.currentTimeMillis() - Configs.websiteLastSuccessfulSyncMs) / 1000L);
        if (ageSeconds < 60L)
        {
            return "SYNC " + ageSeconds + "S AGO";
        }
        return "SYNC " + (ageSeconds / 60L) + "M AGO";
    }

    private int parseHexColor(String value, int fallback)
    {
        if (value == null)
        {
            return fallback;
        }
        String hex = value.trim();
        if (hex.startsWith("#"))
        {
            hex = hex.substring(1);
        }
        if (hex.length() != 6 && hex.length() != 8)
        {
            return fallback;
        }
        try
        {
            long parsed = Long.parseLong(hex, 16);
            return hex.length() == 8 ? (int) parsed : 0xFF000000 | (int) parsed;
        }
        catch (NumberFormatException exception)
        {
            return fallback;
        }
    }

    private boolean contains(double mouseX, double mouseY, int x, int y, int width, int height)
    {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private void createSections()
    {
        this.sections.clear();
        this.sections.add(SettingsSection.sync(
                new SettingRow("Website Sync", "Send real mining data to MMM.", Configs.Generic.WEBSITE_SYNC_ENABLED, ControlKind.BOOLEAN),
                new SettingRow("Total Digs Sync", "Sync total digs with your website profile.", Configs.Generic.TOTAL_DIGS_SYNC_ENABLED, ControlKind.BOOLEAN),
                new SettingRow("Sync Debug", "Verbose sync logging for troubleshooting.", Configs.Generic.WEBSITE_SYNC_DEBUG, ControlKind.BOOLEAN),
                new SettingRow("Abbreviated Numbers", "Use compact values like 12k and 10M.", Configs.Generic.ABBREVIATED_NUMBERS, ControlKind.BOOLEAN)
        ));
        this.sections.add(SettingsSection.notifications(
                new SettingRow("Daily Goal", "Target blocks mined before daily reset.", Configs.Generic.DAILY_GOAL, ControlKind.NUMBER),
                new SettingRow("Notification Thresholds", "Popup milestones, comma separated.", Configs.Generic.NOTIFICATION_THRESHOLDS, ControlKind.TEXT),
                new SettingRow("Sound Alert Threshold", "Percent reached before sound alert.", Configs.Generic.SOUND_ALERT_THRESHOLD, ControlKind.NUMBER)
        ));
        this.sections.add(SettingsSection.hud(
                new SettingRow("Move HUD", "Open the live HUD placement screen.", null, ControlKind.ACTION),
                new SettingRow("HUD X", "Horizontal HUD position.", Configs.Generic.HUD_X, ControlKind.NUMBER),
                new SettingRow("HUD Y", "Vertical HUD position.", Configs.Generic.HUD_Y, ControlKind.NUMBER),
                new SettingRow("HUD Alignment", "Screen corner anchor for the HUD.", Configs.Generic.HUD_ALIGNMENT, ControlKind.OPTION),
                new SettingRow("HUD Scale", "HUD size multiplier.", Configs.Generic.HUD_SCALE, ControlKind.NUMBER),
                new SettingRow("Text Background", "Draw small backgrounds behind HUD text.", Configs.Generic.HUD_TEXT_BACKGROUND, ControlKind.BOOLEAN)
        ));
        this.sections.add(SettingsSection.colors(
                new SettingRow("HUD Title", "Title color used by the HUD.", Configs.Generic.HUD_TITLE_HEX_COLOR, ControlKind.COLOR),
                new SettingRow("HUD Text", "Label and text color used by the HUD.", Configs.Generic.HUD_TEXT_HEX_COLOR, ControlKind.COLOR),
                new SettingRow("HUD Numbers", "Number color used by HUD values.", Configs.Generic.HUD_NUMBER_HEX_COLOR, ControlKind.COLOR),
                new SettingRow("Inactive Text", "Paused and inactive HUD text color.", Configs.Generic.HUD_INACTIVE_HEX_COLOR, ControlKind.COLOR)
        ));
        this.sections.add(SettingsSection.blockEsp(
                new SettingRow("Color Mode", "Block ESP color mode.", Configs.Generic.BLOCK_ESP_COLOR_MODE, ControlKind.OPTION),
                new SettingRow("Custom Color", "Used when color mode is Single Color.", Configs.Generic.BLOCK_ESP_HEX_COLOR, ControlKind.COLOR),
                new SettingRow("Render Mode", "Block ESP shape rendering.", Configs.Generic.BLOCK_ESP_RENDER_MODE, ControlKind.OPTION),
                new SettingRow("Opacity", "Block ESP opacity percentage.", Configs.Generic.BLOCK_ESP_OPACITY, ControlKind.NUMBER),
                new SettingRow("Rainbow Speed", "Rainbow animation speed multiplier.", Configs.Generic.BLOCK_ESP_RAINBOW_SPEED, ControlKind.NUMBER)
        ));
        this.sections.add(SettingsSection.speedGraph(
                new SettingRow("Background Opacity", "Graph background transparency.", Configs.Generic.GRAPH_BG_OPACITY, ControlKind.NUMBER),
                new SettingRow("Line Color", "Speed graph line color.", Configs.Generic.GRAPH_LINE_HEX_COLOR, ControlKind.COLOR),
                new SettingRow("Fill Color", "Speed graph area fill color.", Configs.Generic.GRAPH_FILL_HEX_COLOR, ControlKind.COLOR),
                new SettingRow("Fill Opacity", "Speed graph fill opacity percentage.", Configs.Generic.GRAPH_FILL_OPACITY, ControlKind.NUMBER),
                new SettingRow("Grid Color", "Speed graph grid line color.", Configs.Generic.GRAPH_GRID_HEX_COLOR, ControlKind.COLOR),
                new SettingRow("Grid Opacity", "Speed graph grid line opacity percentage.", Configs.Generic.GRAPH_GRID_OPACITY, ControlKind.NUMBER),
                new SettingRow("Scale Step", "Y-axis grid interval in blocks/hr.", Configs.Generic.GRAPH_SCALE_STEP, ControlKind.NUMBER)
        ));
        this.sections.add(SettingsSection.performance(
                new SettingRow("BPS Smoothing", "Rolling window used for BPS display.", Configs.Generic.BPS_SMOOTHING, ControlKind.OPTION)
        ));
        this.sections.add(SettingsSection.developer(
                new SettingRow("Generate Dev Run", "Create and end a 3h15m test session.", null, ControlKind.ACTION)
        ));
    }

    private enum SidebarItem
    {
        GENERAL("General"),
        SYNC("Sync"),
        NOTIFICATIONS("Notifications"),
        HUD("HUD"),
        COLORS("Colors"),
        PERFORMANCE("Performance"),
        SPEED_GRAPH("Speed Graph"),
        BLOCK_ESP("Block ESP"),
        ABOUT("About");

        private final String label;

        SidebarItem(String label)
        {
            this.label = label;
        }

        private String label()
        {
            return this.label;
        }
    }

    private enum ControlKind
    {
        BOOLEAN,
        NUMBER,
        TEXT,
        OPTION,
        COLOR,
        ACTION;

        private boolean usesTextField()
        {
            return this == NUMBER || this == TEXT || this == COLOR;
        }
    }

    private record SettingRow(String label, String description, IConfigBase config, ControlKind kind)
    {
    }

    private interface ClickAction
    {
        void run(double mouseX, double mouseY);
    }

    private record ClickTarget(int x, int y, int width, int height, ClickAction action)
    {
        private ClickTarget(int x, int y, int width, int height, Runnable action)
        {
            this(x, y, width, height, (mouseX, mouseY) -> action.run());
        }

        private boolean contains(double mouseX, double mouseY)
        {
            return mouseX >= this.x && mouseX < this.x + this.width && mouseY >= this.y && mouseY < this.y + this.height;
        }
    }

    private record ScrollTarget(int x, int y, int width, int height, IConfigBase config)
    {
        private boolean contains(double mouseX, double mouseY)
        {
            return mouseX >= this.x && mouseX < this.x + this.width && mouseY >= this.y && mouseY < this.y + this.height;
        }
    }

    private static final class SettingsSection
    {
        private final SidebarItem sidebarItem;
        private final String title;
        private final String description;
        private final List<SettingRow> rows;
        private int absoluteOffset;

        private SettingsSection(SidebarItem sidebarItem, String title, String description, List<SettingRow> rows)
        {
            this.sidebarItem = sidebarItem;
            this.title = title;
            this.description = description;
            this.rows = rows;
        }

        private static SettingsSection sync(SettingRow... rows)
        {
            return new SettingsSection(SidebarItem.SYNC, "SYNC", "Website sync and numeric display.", List.of(rows));
        }

        private static SettingsSection notifications(SettingRow... rows)
        {
            return new SettingsSection(SidebarItem.NOTIFICATIONS, "GOALS & NOTIFICATIONS", "Daily goal and alert thresholds.", List.of(rows));
        }

        private static SettingsSection hud(SettingRow... rows)
        {
            return new SettingsSection(SidebarItem.HUD, "HUD LAYOUT", "Position, anchor, scale, and text panels.", List.of(rows));
        }

        private static SettingsSection colors(SettingRow... rows)
        {
            return new SettingsSection(SidebarItem.COLORS, "HUD COLORS", "Colors used by HUD text and values.", List.of(rows));
        }

        private static SettingsSection performance(SettingRow... rows)
        {
            return new SettingsSection(SidebarItem.PERFORMANCE, "PERFORMANCE", "Rolling speed metric behavior.", List.of(rows));
        }

        private static SettingsSection speedGraph(SettingRow... rows)
        {
            return new SettingsSection(SidebarItem.SPEED_GRAPH, "SPEED GRAPH", "Speed graph colors and grid lines.", List.of(rows));
        }

        private static SettingsSection blockEsp(SettingRow... rows)
        {
            return new SettingsSection(SidebarItem.BLOCK_ESP, "BLOCK ESP", "Visual block helper rendering.", List.of(rows));
        }

        private static SettingsSection about(SettingRow... rows)
        {
            return new SettingsSection(SidebarItem.ABOUT, "ABOUT", "Current mod version and existing MMM screens.", List.of(rows));
        }

        private static SettingsSection developer(SettingRow... rows)
        {
            return new SettingsSection(SidebarItem.ABOUT, "DEV TOOLS", "Generate local test data for MMM screens.", List.of(rows));
        }

        private SidebarItem sidebarItem()
        {
            return this.sidebarItem;
        }

        private String title()
        {
            return this.title;
        }

        private String description()
        {
            return this.description;
        }

        private List<SettingRow> rows()
        {
            return this.rows;
        }

        private int absoluteOffset()
        {
            return this.absoluteOffset;
        }

        private void setAbsoluteOffset(int absoluteOffset)
        {
            this.absoluteOffset = absoluteOffset;
        }
    }
}
