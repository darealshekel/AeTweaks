package com.miningtrackeraddon.ui;

import java.util.ArrayList;
import java.util.List;

import com.miningtrackeraddon.config.Configs;
import com.miningtrackeraddon.util.UiFormat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public class NotificationSettingsScreen extends Screen
{
    private final Screen parent;
    private TextFieldWidget thresholdField;
    private TextFieldWidget soundThresholdField;
    private ButtonWidget saveButton;

    public NotificationSettingsScreen(Screen parent)
    {
        super(Text.literal("Goal Notifications"));
        this.parent = parent;
    }

    @Override
    protected void init()
    {
        ensureCursorVisible();
        int centerX = this.width / 2;
        int fieldWidth = 240;
        int fieldX = centerX - fieldWidth / 2;
        int startY = this.height / 2 - 56;

        this.thresholdField = createField(fieldX, startY + 24, fieldWidth, joinThresholds(Configs.getNotificationThresholds()));
        this.soundThresholdField = createField(fieldX, startY + 78, fieldWidth, String.valueOf(Configs.Generic.SOUND_ALERT_THRESHOLD.getIntegerValue()));

        this.saveButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Save"), button -> saveAndClose()).dimensions(centerX - 106, startY + 124, 100, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), button -> close()).dimensions(centerX + 6, startY + 124, 100, 20).build());
        refreshState();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta)
    {
        ensureCursorVisible();
        context.fill(0, 0, this.width, this.height, 0xFF101010);
        int centerX = this.width / 2;
        int panelWidth = 360;
        int panelHeight = 214;
        int panelX = centerX - panelWidth / 2;
        int panelY = this.height / 2 - panelHeight / 2;

        context.drawBorder(panelX, panelY, panelWidth, panelHeight, 0xFF5F532E);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, panelY + 18, UiFormat.YELLOW);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Set popup milestones like 25,50,75,100 and a separate sound trigger percent."), centerX, panelY + 36, UiFormat.TEXT_MUTED);
        context.drawText(this.textRenderer, Text.literal("Popup Thresholds"), panelX + 40, panelY + 70, UiFormat.YELLOW, false);
        context.drawText(this.textRenderer, Text.literal("Sound Threshold"), panelX + 40, panelY + 124, UiFormat.YELLOW, false);

        super.render(context, mouseX, mouseY, delta);
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
}
