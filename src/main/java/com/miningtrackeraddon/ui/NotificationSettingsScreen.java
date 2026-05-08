package com.miningtrackeraddon.ui;

import java.util.ArrayList;
import java.util.List;

import com.miningtrackeraddon.config.Configs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public class NotificationSettingsScreen extends Screen
{
    private static final int PANEL_PADDING = 18;
    private static final int CARD_PADDING = 12;
    private static final int BUTTON_HEIGHT = 20;
    private static final int FIELD_HEIGHT = 20;

    private final Screen parent;
    private TextFieldWidget thresholdField;
    private TextFieldWidget soundThresholdField;
    private ButtonWidget saveButton;
    private ButtonWidget cancelButton;

    public NotificationSettingsScreen(Screen parent)
    {
        super(Text.literal("Goal Notifications"));
        this.parent = parent;
    }

    @Override
    protected void init()
    {
        ensureCursorVisible();
        this.clearChildren();
        Layout layout = layout();

        this.thresholdField = createField(layout.fieldX, layout.thresholdFieldY, layout.fieldWidth, joinThresholds(Configs.getNotificationThresholds()));
        this.soundThresholdField = createField(layout.fieldX, layout.soundFieldY, layout.fieldWidth, String.valueOf(Configs.Generic.SOUND_ALERT_THRESHOLD.getIntegerValue()));

        this.saveButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Save"), button -> saveAndClose()).dimensions(layout.buttonX, layout.buttonY, layout.buttonWidth, BUTTON_HEIGHT).build());
        this.cancelButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), button -> close()).dimensions(layout.buttonX + layout.buttonWidth + 10, layout.buttonY, layout.buttonWidth, BUTTON_HEIGHT).build());
        refreshState();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta)
    {
        ensureCursorVisible();
        Layout layout = layout();
        updateBounds(layout);
        MmmUi.backdrop(context, this.width, this.height);
        MmmUi.card(context, layout.panelX, layout.panelY, layout.panelWidth, layout.panelHeight, MmmUi.PANEL, MmmUi.BORDER);
        MmmUi.drawTextWithin(context, this.textRenderer, this.title.getString(), layout.contentX, layout.headerY, layout.contentWidth, MmmUi.TEXT, true);
        MmmUi.pill(context, this.textRenderer, layout.contentX, layout.headerY + 18, 136, 16, "Goal Alerts");

        MmmUi.card(context, layout.contentX, layout.headerY + 46, layout.contentWidth, 48, MmmUi.CARD, MmmUi.BORDER_SOFT);
        MmmUi.wrappedText(context, this.textRenderer, "Popup milestones and the sound trigger are saved as real config values.", layout.contentX + CARD_PADDING, layout.headerY + 58, layout.contentWidth - CARD_PADDING * 2, MmmUi.LABEL);

        context.drawText(this.textRenderer, Text.literal("Popup Thresholds"), layout.fieldX, layout.thresholdLabelY, MmmUi.LABEL, false);
        context.drawText(this.textRenderer, Text.literal("Sound Threshold"), layout.fieldX, layout.soundLabelY, MmmUi.LABEL, false);
        MmmUi.fieldShell(context, layout.fieldX - 1, layout.thresholdFieldY - 1, layout.fieldWidth + 2, FIELD_HEIGHT + 2, this.thresholdField != null && this.thresholdField.isFocused());
        MmmUi.fieldShell(context, layout.fieldX - 1, layout.soundFieldY - 1, layout.fieldWidth + 2, FIELD_HEIGHT + 2, this.soundThresholdField != null && this.soundThresholdField.isFocused());

        super.render(context, mouseX, mouseY, delta);

        if (parseThresholds(this.thresholdField.getText()) == null || parseSound(this.soundThresholdField.getText()) == null)
        {
            MmmUi.card(context, layout.contentX, layout.errorY, layout.contentWidth, 24, MmmUi.INSET, MmmUi.ERROR);
            MmmUi.drawTextWithin(context, this.textRenderer, "Use percentages from 1 to 100, separated with commas.", layout.contentX + 10, layout.errorY + 8, layout.contentWidth - 20, MmmUi.ERROR, false);
        }
    }

    @Override
    public void close()
    {
        MinecraftClient.getInstance().setScreen(this.parent);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta)
    {
    }

    @Override
    public boolean shouldPause()
    {
        return false;
    }

    private void ensureCursorVisible()
    {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.mouse != null)
        {
            client.mouse.unlockCursor();
        }
    }

    private TextFieldWidget createField(int x, int y, int width, String value)
    {
        TextFieldWidget field = new TextFieldWidget(this.textRenderer, x, y, width, 20, Text.empty());
        field.setMaxLength(64);
        field.setDrawsBackground(false);
        field.setCentered(true);
        field.setEditableColor(MmmUi.TEXT);
        field.setUneditableColor(MmmUi.MUTED);
        field.setText(value);
        field.setChangedListener(text -> refreshState());
        this.addDrawableChild(field);
        return field;
    }

    private void refreshState()
    {
        if (this.saveButton != null)
        {
            this.saveButton.active = parseThresholds(this.thresholdField.getText()) != null && parseSound(this.soundThresholdField.getText()) != null;
        }
    }

    private void saveAndClose()
    {
        List<Integer> thresholds = parseThresholds(this.thresholdField.getText());
        Integer sound = parseSound(this.soundThresholdField.getText());
        if (thresholds == null || sound == null)
        {
            return;
        }

        Configs.Generic.NOTIFICATION_THRESHOLDS.setValueFromString(joinThresholds(thresholds));
        Configs.Generic.SOUND_ALERT_THRESHOLD.setIntegerValue(sound);
        Configs.saveToFile();
        close();
    }

    private String joinThresholds(List<Integer> thresholds)
    {
        return String.join(",", thresholds.stream().map(String::valueOf).toList());
    }

    private List<Integer> parseThresholds(String text)
    {
        try
        {
            List<Integer> values = new ArrayList<>();
            for (String part : text.split(","))
            {
                String trimmed = part.trim();
                if (trimmed.isEmpty())
                {
                    continue;
                }
                int value = Integer.parseInt(trimmed);
                if (value <= 0 || value > 100)
                {
                    return null;
                }
                values.add(value);
            }
            values.sort(Integer::compareTo);
            return values.isEmpty() ? null : values;
        }
        catch (NumberFormatException exception)
        {
            return null;
        }
    }

    private Integer parseSound(String text)
    {
        try
        {
            int value = Integer.parseInt(text.trim());
            return value > 0 && value <= 100 ? value : null;
        }
        catch (NumberFormatException exception)
        {
            return null;
        }
    }

    private void updateBounds(Layout layout)
    {
        if (this.thresholdField != null)
        {
            this.thresholdField.setX(layout.fieldX);
            this.thresholdField.setY(layout.thresholdFieldY);
            this.thresholdField.setWidth(layout.fieldWidth);
        }
        if (this.soundThresholdField != null)
        {
            this.soundThresholdField.setX(layout.fieldX);
            this.soundThresholdField.setY(layout.soundFieldY);
            this.soundThresholdField.setWidth(layout.fieldWidth);
        }
        if (this.saveButton != null)
        {
            this.saveButton.setX(layout.buttonX);
            this.saveButton.setY(layout.buttonY);
            this.saveButton.setWidth(layout.buttonWidth);
        }
        if (this.cancelButton != null)
        {
            this.cancelButton.setX(layout.buttonX + layout.buttonWidth + 10);
            this.cancelButton.setY(layout.buttonY);
            this.cancelButton.setWidth(layout.buttonWidth);
        }
    }

    private Layout layout()
    {
        int panelWidth = Math.min(430, Math.max(340, this.width - 40));
        int panelHeight = 278;
        int panelX = (this.width - panelWidth) / 2;
        int panelY = (this.height - panelHeight) / 2;
        int contentX = panelX + PANEL_PADDING;
        int contentWidth = panelWidth - PANEL_PADDING * 2;
        int headerY = panelY + PANEL_PADDING;
        int fieldWidth = Math.min(260, contentWidth - CARD_PADDING * 2);
        int fieldX = contentX + (contentWidth - fieldWidth) / 2;
        int thresholdLabelY = headerY + 112;
        int thresholdFieldY = thresholdLabelY + 14;
        int soundLabelY = thresholdFieldY + 36;
        int soundFieldY = soundLabelY + 14;
        int totalButtonWidth = Math.min(232, contentWidth - CARD_PADDING * 2);
        int buttonWidth = (totalButtonWidth - 10) / 2;
        int buttonX = contentX + (contentWidth - totalButtonWidth) / 2;
        int buttonY = soundFieldY + 36;
        int errorY = buttonY + 30;
        return new Layout(panelX, panelY, panelWidth, panelHeight, contentX, contentWidth, headerY, fieldX, fieldWidth, thresholdLabelY, thresholdFieldY, soundLabelY, soundFieldY, buttonX, buttonY, buttonWidth, errorY);
    }

    private record Layout(
            int panelX,
            int panelY,
            int panelWidth,
            int panelHeight,
            int contentX,
            int contentWidth,
            int headerY,
            int fieldX,
            int fieldWidth,
            int thresholdLabelY,
            int thresholdFieldY,
            int soundLabelY,
            int soundFieldY,
            int buttonX,
            int buttonY,
            int buttonWidth,
            int errorY)
    {
    }
}
