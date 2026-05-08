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
    private static final int PANEL_PADDING = 18;
    private static final int CARD_PADDING = 12;
    private static final int BUTTON_HEIGHT = 20;
    private static final int FIELD_HEIGHT = 20;

    private final Screen parent;
    private TextFieldWidget dailyGoalField;
    private TextFieldWidget dailyProgressField;
    private ButtonWidget saveButton;
    private ButtonWidget cancelButton;

    public GoalConfigScreen(Screen parent)
    {
        super(Text.literal("Daily Goal Settings"));
        this.parent = parent;
    }

    @Override
    protected void init()
    {
        ensureCursorVisible();
        this.clearChildren();
        Layout layout = layout();

        this.dailyGoalField = createNumericField(layout.fieldX, layout.goalFieldY, layout.fieldWidth, String.valueOf(Configs.Generic.DAILY_GOAL.getIntegerValue()));
        this.dailyProgressField = createNumericField(layout.fieldX, layout.progressFieldY, layout.fieldWidth, String.valueOf(Configs.dailyProgress));

        this.saveButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Save"), button -> saveAndClose()).dimensions(layout.buttonX, layout.buttonY, layout.buttonWidth, BUTTON_HEIGHT).build());
        this.cancelButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), button -> close()).dimensions(layout.buttonX + layout.buttonWidth + 10, layout.buttonY, layout.buttonWidth, BUTTON_HEIGHT).build());
        refreshSaveState();
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
        MmmUi.pill(context, this.textRenderer, layout.contentX, layout.headerY + 18, 116, 16, "Daily Target");

        int summaryY = layout.headerY + 46;
        MmmUi.card(context, layout.contentX, summaryY, layout.contentWidth, 54, MmmUi.CARD, MmmUi.BORDER_SOFT);
        MiningStats.GoalProgress progress = MiningStats.getDailyGoalProgress();
        MmmUi.drawTextWithin(context, this.textRenderer, "Progress", layout.contentX + CARD_PADDING, summaryY + 10, layout.contentWidth - CARD_PADDING * 2, MmmUi.LABEL, false);
        String percent = progress.getPercent() + "%";
        int percentWidth = this.textRenderer.getWidth(percent);
        MmmUi.drawTextWithin(context, this.textRenderer, UiFormat.formatProgress(progress.current(), progress.target()), layout.contentX + CARD_PADDING, summaryY + 24, Math.max(0, layout.contentWidth - CARD_PADDING * 2 - percentWidth - 8), MmmUi.TEXT, false);
        MmmUi.drawTextRightWithin(context, this.textRenderer, percent, layout.contentX + layout.contentWidth - CARD_PADDING, summaryY + 24, percentWidth, MmmUi.ACCENT_BRIGHT, false);

        context.drawText(this.textRenderer, Text.literal("Daily Goal"), layout.fieldX, layout.goalLabelY, MmmUi.LABEL, false);
        context.drawText(this.textRenderer, Text.literal("Current Progress"), layout.fieldX, layout.progressLabelY, MmmUi.LABEL, false);
        MmmUi.fieldShell(context, layout.fieldX - 1, layout.goalFieldY - 1, layout.fieldWidth + 2, FIELD_HEIGHT + 2, this.dailyGoalField != null && this.dailyGoalField.isFocused());
        MmmUi.fieldShell(context, layout.fieldX - 1, layout.progressFieldY - 1, layout.fieldWidth + 2, FIELD_HEIGHT + 2, this.dailyProgressField != null && this.dailyProgressField.isFocused());

        super.render(context, mouseX, mouseY, delta);

        if (!isValidGoal(this.dailyGoalField.getText()) || !isNonNegative(this.dailyProgressField.getText()))
        {
            MmmUi.card(context, layout.contentX, layout.errorY, layout.contentWidth, 24, MmmUi.INSET, MmmUi.ERROR);
            MmmUi.drawTextWithin(context, this.textRenderer, "Goal must be at least 10,000. Progress must be 0 or more.", layout.contentX + 10, layout.errorY + 8, layout.contentWidth - 20, MmmUi.ERROR, false);
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

    private TextFieldWidget createNumericField(int x, int y, int width, String value)
    {
        TextFieldWidget field = new TextFieldWidget(this.textRenderer, x, y, width, 20, Text.empty());
        field.setMaxLength(12);
        field.setDrawsBackground(false);
        field.setCentered(true);
        field.setEditableColor(MmmUi.TEXT);
        field.setUneditableColor(MmmUi.MUTED);
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
            this.saveButton.active = isValidGoal(this.dailyGoalField.getText()) && isNonNegative(this.dailyProgressField.getText());
        }
    }

    private void saveAndClose()
    {
        Configs.Generic.DAILY_GOAL.setIntegerValue(Math.max(Configs.MIN_DAILY_GOAL, Integer.parseInt(this.dailyGoalField.getText())));
        long progress = Long.parseLong(this.dailyProgressField.getText());
        Configs.dailyGoalLastResetMs = System.currentTimeMillis();
        Configs.saveToFile();
        MiningStats.setDailyProgress(progress);
        close();
    }

    private boolean isValidGoal(String value)
    {
        try
        {
            return Integer.parseInt(value) >= Configs.MIN_DAILY_GOAL;
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

    private void updateBounds(Layout layout)
    {
        if (this.dailyGoalField != null)
        {
            this.dailyGoalField.setX(layout.fieldX);
            this.dailyGoalField.setY(layout.goalFieldY);
            this.dailyGoalField.setWidth(layout.fieldWidth);
        }
        if (this.dailyProgressField != null)
        {
            this.dailyProgressField.setX(layout.fieldX);
            this.dailyProgressField.setY(layout.progressFieldY);
            this.dailyProgressField.setWidth(layout.fieldWidth);
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
        int panelWidth = Math.min(420, Math.max(330, this.width - 40));
        int panelHeight = 274;
        int panelX = (this.width - panelWidth) / 2;
        int panelY = (this.height - panelHeight) / 2;
        int contentX = panelX + PANEL_PADDING;
        int contentWidth = panelWidth - PANEL_PADDING * 2;
        int headerY = panelY + PANEL_PADDING;
        int fieldWidth = Math.min(240, contentWidth - CARD_PADDING * 2);
        int fieldX = contentX + (contentWidth - fieldWidth) / 2;
        int goalLabelY = headerY + 116;
        int goalFieldY = goalLabelY + 14;
        int progressLabelY = goalFieldY + 32;
        int progressFieldY = progressLabelY + 14;
        int totalButtonWidth = Math.min(230, contentWidth - CARD_PADDING * 2);
        int buttonWidth = (totalButtonWidth - 10) / 2;
        int buttonX = contentX + (contentWidth - totalButtonWidth) / 2;
        int buttonY = progressFieldY + 36;
        int errorY = buttonY + 30;
        return new Layout(panelX, panelY, panelWidth, panelHeight, contentX, contentWidth, headerY, fieldX, fieldWidth, goalLabelY, goalFieldY, progressLabelY, progressFieldY, buttonX, buttonY, buttonWidth, errorY);
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
            int goalLabelY,
            int goalFieldY,
            int progressLabelY,
            int progressFieldY,
            int buttonX,
            int buttonY,
            int buttonWidth,
            int errorY)
    {
    }
}
