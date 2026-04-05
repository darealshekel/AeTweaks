package com.miningtrackeraddon.ui;

import com.miningtrackeraddon.config.Configs;
import com.miningtrackeraddon.tracker.MiningStats;
import com.miningtrackeraddon.util.UiFormat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public class GoalConfigScreen extends Screen
{
    private final Screen parent;
    private TextFieldWidget dailyGoalField;
    private TextFieldWidget dailyProgressField;
    private ButtonWidget saveButton;

    public GoalConfigScreen(Screen parent)
    {
        super(Text.literal("Daily Goal Settings"));
        this.parent = parent;
    }

    @Override
    protected void init()
    {
        int centerX = this.width / 2;
        int fieldWidth = 180;
        int inputX = centerX - fieldWidth / 2;
        int topY = this.height / 2 - 38;

        this.dailyGoalField = createNumericField(inputX, topY + 16, fieldWidth, String.valueOf(Configs.Generic.DAILY_GOAL.getIntegerValue()));
        this.dailyProgressField = createNumericField(inputX, topY + 60, fieldWidth, String.valueOf(Configs.dailyProgress));

        this.saveButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Save"), button -> saveAndClose()).dimensions(centerX - 104, topY + 100, 100, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), button -> close()).dimensions(centerX + 4, topY + 100, 100, 20).build());
        refreshSaveState();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta)
    {
        context.fill(0, 0, this.width, this.height, 0xFF101010);
        int centerX = this.width / 2;
        int fieldWidth = 180;
        int inputX = centerX - fieldWidth / 2;
        int topY = this.height / 2 - 58;

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, topY, UiFormat.YELLOW);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Edit the goal target and current daily progress."), centerX, topY + 16, UiFormat.TEXT_MUTED);
        context.drawText(this.textRenderer, Text.literal("Daily Goal"), inputX, topY + 40, UiFormat.YELLOW, false);
        context.drawText(this.textRenderer, Text.literal("Current Progress"), inputX, topY + 84, UiFormat.YELLOW, false);

        super.render(context, mouseX, mouseY, delta);

        if (!isPositive(this.dailyGoalField.getText()) || !isNonNegative(this.dailyProgressField.getText()))
        {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Goal must be above 0. Progress must be 0 or more."), centerX, topY + 160, UiFormat.RED);
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

    private TextFieldWidget createNumericField(int x, int y, int width, String value)
    {
        TextFieldWidget field = new TextFieldWidget(this.textRenderer, x, y, width, 20, Text.empty());
        field.setMaxLength(12);
        field.setText(value);
        field.setChangedListener(text -> {
            String sanitized = text.replaceAll("[^0-9]", "");
            if (!sanitized.equals(text))
            {
                field.setText(sanitized);
                return;
            }
            refreshSaveState();
        });
        this.addDrawableChild(field);
        return field;
    }

    private void refreshSaveState()
    {
        if (this.saveButton != null)
        {
            this.saveButton.active = isPositive(this.dailyGoalField.getText()) && isNonNegative(this.dailyProgressField.getText());
        }
    }

    private void saveAndClose()
    {
        Configs.Generic.DAILY_GOAL.setIntegerValue(Integer.parseInt(this.dailyGoalField.getText()));
        long progress = Long.parseLong(this.dailyProgressField.getText());
        Configs.dailyGoalLastResetMs = System.currentTimeMillis();
        Configs.saveToFile();
        MiningStats.setDailyProgress(progress);
        close();
    }

    private boolean isPositive(String value)
    {
        try
        {
            return Integer.parseInt(value) > 0;
        }
        catch (NumberFormatException exception)
        {
            return false;
        }
    }

    private boolean isNonNegative(String value)
    {
        try
        {
            return Long.parseLong(value) >= 0L;
        }
        catch (NumberFormatException exception)
        {
            return false;
        }
    }
}
