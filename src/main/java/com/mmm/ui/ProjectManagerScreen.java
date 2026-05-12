package com.mmm.ui;

import java.util.List;

import com.mmm.config.Configs;
import com.mmm.config.Configs.ProjectEntry;
import com.mmm.tracker.MiningStats;
import com.mmm.util.UiFormat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

public class ProjectManagerScreen extends Screen
{
    private static final int PANEL_MARGIN = 20;
    private static final int PANEL_PADDING = 18;
    private static final int CARD_PADDING = 12;
    private static final int CARD_GAP = 10;
    private static final int BUTTON_HEIGHT = 20;
    private static final int ROW_HEIGHT = 32;
    private static final int SCROLLBAR_WIDTH = 8;
    private static final int SCROLLBAR_MIN_THUMB = 18;
    private static final int COLOR_PANEL = MmmUi.PANEL;
    private static final int COLOR_CARD = MmmUi.CARD;
    private static final int COLOR_CARD_SOFT = MmmUi.CARD_SOFT;
    private static final int COLOR_INSET = MmmUi.INSET;
    private static final int COLOR_BORDER = MmmUi.BORDER;
    private static final int COLOR_BORDER_SOFT = MmmUi.BORDER_SOFT;
    private static final int COLOR_ACCENT = MmmUi.ACCENT;
    private static final int COLOR_VALUE = MmmUi.TEXT;
    private static final int COLOR_LABEL = MmmUi.LABEL;
    private static final int COLOR_MUTED = MmmUi.MUTED;
    private static final int COLOR_SUCCESS = MmmUi.SUCCESS;
    private static final int COLOR_ROW_SELECTED = MmmUi.ROW_SELECTED;
    private static final int COLOR_ROW_HOVER = MmmUi.ROW_HOVER;
    private static final int COLOR_ROW_ALT = MmmUi.ROW_ALT;

    private final Screen parent;
    private int selectedIndex;
    private int scrollOffset;
    private boolean draggingScrollbar;
    private boolean deleteConfirm;
    private long openedAtMs;

    private TextFieldWidget nameField;
    private ButtonWidget applyButton;
    private ButtonWidget deleteButton;
    private ButtonWidget setActiveButton;

    public ProjectManagerScreen(Screen parent)
    {
        super(Text.literal("Projects"));
        this.parent = parent;
    }

    @Override
    protected void init()
    {
        this.clearChildren();
        this.openedAtMs = System.currentTimeMillis();
        ensureCursorVisible();
        this.selectedIndex = Math.min(this.selectedIndex, Math.max(0, Configs.PROJECTS.size() - 1));

        Layout layout = computeLayout();
        this.nameField = createField(getDetailFieldX(layout), layout.detailY + 50, getDetailFieldWidth(layout), 64);

        this.applyButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Apply Changes"), button ->
        {
            applyCurrentEdits();
            Configs.saveToFile();
            MinecraftClient.getInstance().setScreen(new ProjectManagerScreen(this.parent));
        }).dimensions(layout.detailX + CARD_PADDING, layout.detailY + 92, layout.detailWidth - CARD_PADDING * 2, BUTTON_HEIGHT).build());

        this.setActiveButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Set Active"), button ->
        {
            applyCurrentEdits();
            ProjectEntry selected = getSelectedProject();
            if (selected != null)
            {
                Configs.activeProjectId = selected.id;
                Configs.saveToFile();
                MinecraftClient.getInstance().setScreen(new ProjectManagerScreen(this.parent));
            }
        }).dimensions(layout.detailX + CARD_PADDING, layout.detailY + 116, layout.detailWidth - CARD_PADDING * 2, BUTTON_HEIGHT).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("New Project"), button ->
        {
            applyCurrentEdits();
            ProjectEntry entry = Configs.createProject("Project " + (Configs.PROJECTS.size() + 1));
            this.selectedIndex = Configs.PROJECTS.indexOf(entry);
            Configs.activeProjectId = entry.id;
            Configs.saveToFile();
            MinecraftClient.getInstance().setScreen(new ProjectManagerScreen(this.parent));
        }).dimensions(getFooterButtonX(layout, true), getFooterButtonY(layout), 104, BUTTON_HEIGHT).build());

        this.deleteButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Remove"), button -> handleDelete()).dimensions(getFooterButtonX(layout, false), getFooterButtonY(layout), 104, BUTTON_HEIGHT).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> close()).dimensions(layout.panelRight - 74, layout.headerY - 2, 64, BUTTON_HEIGHT).build());

        populateFields();
        refreshButtons();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta)
    {
        ensureCursorVisible();
        Layout layout = computeLayout();
        float animation = getOpenAnimationProgress();
        int animatedPanelY = layout.panelY + Math.round((1.0F - animation) * 14.0F);
        Layout animatedLayout = layout.withPanelY(animatedPanelY);
        updateFieldPositions(animatedLayout);

        MmmUi.backdrop(context, this.width, this.height);
        MmmUi.drawMmmScreensSidebar(context, this.textRenderer, this.width, this.height, mouseX, mouseY, "PROJECTS");
        fillCard(context, animatedLayout.panelX, animatedLayout.panelY, animatedLayout.panelWidth, animatedLayout.panelHeight, COLOR_PANEL, COLOR_BORDER);

        drawHeader(context, animatedLayout);
        drawProjectList(context, animatedLayout, mouseX, mouseY);
        drawDetailCard(context, animatedLayout);
        drawFieldShell(context, this.nameField);

        super.render(context, mouseX, mouseY, delta);
        MmmUi.drawMmmTopBar(context, this.textRenderer, this.width);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount)
    {
        if (isMouseInsideList(mouseX, mouseY))
        {
            setScrollOffset(this.scrollOffset + (verticalAmount < 0 ? 1 : -1));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button)
    {
        if (button == 0 && MmmUi.handleMmmScreensSidebarClick(this, this.parent, mouseX, mouseY, "PROJECTS"))
        {
            return true;
        }

        if (button == 0 && isOverScrollbar(mouseX, mouseY))
        {
            this.draggingScrollbar = true;
            updateScrollFromMouse(mouseY);
            return true;
        }

        Layout layout = computeLayout();
        int listX = layout.listX + CARD_PADDING;
        int listY = layout.listY + 44;
        int listWidth = layout.listWidth - CARD_PADDING * 2;
        int drawY = listY + 6;
        int visibleRows = getVisibleRowCount(layout);

        for (int row = 0; row < visibleRows; row++)
        {
            int projectIndex = this.scrollOffset + row;
            if (projectIndex >= Configs.PROJECTS.size())
            {
                break;
            }

            int rowY = drawY + row * ROW_HEIGHT;
            if (mouseX >= listX && mouseX <= listX + listWidth && mouseY >= rowY && mouseY <= rowY + ROW_HEIGHT - 4)
            {
                applyCurrentEdits();
                this.selectedIndex = projectIndex;
                this.deleteConfirm = false;
                populateFields();
                refreshButtons();
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY)
    {
        if (this.draggingScrollbar)
        {
            updateScrollFromMouse(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button)
    {
        if (button == 0)
        {
            this.draggingScrollbar = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void close()
    {
        applyCurrentEdits();
        Configs.saveToFile();
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

    private void ensureCursorVisible()
    {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.mouse != null)
        {
            client.mouse.unlockCursor();
        }
    }

    private void drawHeader(DrawContext context, Layout layout)
    {
        MmmUi.drawTextWithin(context, this.textRenderer, this.title.getString(), layout.contentX, layout.headerY, layout.contentWidth, COLOR_VALUE, true);
        drawPill(context, layout.contentX, layout.headerY + 18, Math.min(220, layout.contentWidth / 2), 16, "Project Progress", COLOR_CARD, COLOR_ACCENT);
        MmmUi.drawTextWithin(context, this.textRenderer, "Stored progress and active project edits stay in one clean panel.", layout.contentX + 2, layout.headerY + 38, layout.contentWidth - 4, COLOR_LABEL, false);
    }

    private void drawProjectList(DrawContext context, Layout layout, int mouseX, int mouseY)
    {
        fillCard(context, layout.listX, layout.listY, layout.listWidth, layout.listHeight, COLOR_CARD_SOFT, COLOR_BORDER);
        MmmUi.drawTextWithin(context, this.textRenderer, "Projects", layout.listX + CARD_PADDING, layout.listY + 10, layout.listWidth - CARD_PADDING * 2, COLOR_VALUE, false);
        MmmUi.drawTextWithin(context, this.textRenderer, "Choose a project to edit or switch active progress.", layout.listX + CARD_PADDING, layout.listY + 24, layout.listWidth - CARD_PADDING * 2, COLOR_MUTED, false);

        int listX = layout.listX + CARD_PADDING;
        int listY = layout.listY + 44;
        int listWidth = layout.listWidth - CARD_PADDING * 2;
        int listHeight = layout.listHeight - 56 - BUTTON_HEIGHT - 10;
        int viewportWidth = listWidth - SCROLLBAR_WIDTH - 6;

        context.fill(listX, listY, listX + listWidth, listY + listHeight, COLOR_INSET);
        context.drawBorder(listX, listY, listWidth, listHeight, COLOR_BORDER_SOFT);

        int visibleRows = getVisibleRowCount(layout);
        this.scrollOffset = Math.max(0, Math.min(this.scrollOffset, Math.max(0, Configs.PROJECTS.size() - visibleRows)));
        context.enableScissor(listX, listY, listX + viewportWidth, listY + listHeight);

        int drawY = listY + 6;
        for (int row = 0; row < visibleRows; row++)
        {
            int projectIndex = this.scrollOffset + row;
            if (projectIndex >= Configs.PROJECTS.size())
            {
                break;
            }

            ProjectEntry project = Configs.PROJECTS.get(projectIndex);
            int rowY = drawY + row * ROW_HEIGHT;
            boolean hovered = mouseX >= listX && mouseX <= listX + viewportWidth && mouseY >= rowY && mouseY <= rowY + ROW_HEIGHT - 4;
            int rowColor = projectIndex == this.selectedIndex ? COLOR_ROW_SELECTED : hovered ? COLOR_ROW_HOVER : ((row & 1) == 0 ? COLOR_ROW_ALT : COLOR_INSET);
            context.fill(listX + 4, rowY, listX + viewportWidth - 4, rowY + ROW_HEIGHT - 4, rowColor);

            String stats = UiFormat.formatCompact(project.progress) + " blocks";
            int statsWidth = this.textRenderer.getWidth(stats);
            int statsMaxWidth = Math.min(statsWidth, Math.max(42, viewportWidth / 2 - 16));
            int nameMaxWidth = Math.max(0, viewportWidth - statsMaxWidth - 36);
            MmmUi.drawTextWithin(context, this.textRenderer, project.name, listX + 12, rowY + 6, nameMaxWidth, COLOR_VALUE, false);
            MmmUi.drawTextRightWithin(context, this.textRenderer, stats, listX + viewportWidth - 12, rowY + 6, statsMaxWidth, COLOR_ACCENT, false);

            String state = project.id.equals(Configs.activeProjectId) ? "Active" : "Stored";
            MmmUi.drawTextWithin(context, this.textRenderer, state, listX + 12, rowY + 18, viewportWidth - 24, project.id.equals(Configs.activeProjectId) ? COLOR_SUCCESS : COLOR_MUTED, false);
        }

        context.disableScissor();
        drawScrollbar(context, listX + listWidth - SCROLLBAR_WIDTH, listY, listHeight, mouseX, mouseY, visibleRows);
    }

    private void drawDetailCard(DrawContext context, Layout layout)
    {
        fillCard(context, layout.detailX, layout.detailY, layout.detailWidth, layout.detailHeight, COLOR_CARD, COLOR_BORDER);
        MmmUi.drawTextWithin(context, this.textRenderer, "Project Detail", layout.detailX + CARD_PADDING, layout.detailY + 10, layout.detailWidth - CARD_PADDING * 2, COLOR_VALUE, false);
        MmmUi.drawTextWithin(context, this.textRenderer, "Edit the saved project name. Progress updates from mined blocks.", layout.detailX + CARD_PADDING, layout.detailY + 24, layout.detailWidth - CARD_PADDING * 2, COLOR_MUTED, false);

        ProjectEntry selected = getSelectedProject();
        if (selected == null)
        {
            MmmUi.drawTextWithin(context, this.textRenderer, "No project selected.", layout.detailX + CARD_PADDING, layout.detailY + 34, layout.detailWidth - CARD_PADDING * 2, COLOR_MUTED, false);
            return;
        }

        int detailX = layout.detailX + CARD_PADDING;
        int detailWidth = layout.detailWidth - CARD_PADDING * 2;
        context.drawText(this.textRenderer, Text.literal("Project Name"), detailX, layout.detailY + 34, COLOR_LABEL, false);

        drawStatusChip(context, detailX, layout.detailY + 150, selected.id.equals(Configs.activeProjectId) ? "Active Project" : "Stored Project", selected.id.equals(Configs.activeProjectId) ? COLOR_SUCCESS : COLOR_MUTED);

        int statsY = layout.detailY + 178;
        int statWidth = (detailWidth - CARD_GAP) / 2;
        drawStatCard(context, detailX, statsY, statWidth, 46, "Project Total", UiFormat.formatCompact(selected.progress), "blocks");
        drawStatCard(context, detailX + statWidth + CARD_GAP, statsY, statWidth, 46, "Current Session", UiFormat.formatCompact(MiningStats.getSessionBlocksMined()), "blocks");
    }

    private void updateFieldPositions(Layout layout)
    {
        if (this.nameField != null)
        {
            this.nameField.setX(getDetailFieldX(layout) + FIELD_PAD_X);
            this.nameField.setY(layout.detailY + 50 + FIELD_PAD_Y);
            this.nameField.setWidth(getDetailFieldWidth(layout) - FIELD_PAD_X * 2);
        }
        if (this.applyButton != null)
        {
            this.applyButton.setX(layout.detailX + CARD_PADDING);
            this.applyButton.setY(layout.detailY + 92);
            this.applyButton.setWidth(layout.detailWidth - CARD_PADDING * 2);
        }
        if (this.setActiveButton != null)
        {
            this.setActiveButton.setX(layout.detailX + CARD_PADDING);
            this.setActiveButton.setY(layout.detailY + 116);
            this.setActiveButton.setWidth(layout.detailWidth - CARD_PADDING * 2);
        }
        if (this.deleteButton != null)
        {
            this.deleteButton.setX(getFooterButtonX(layout, false));
            this.deleteButton.setY(getFooterButtonY(layout));
        }
    }

    private int getFooterButtonX(Layout layout, boolean leftButton)
    {
        int innerWidth = layout.listWidth - CARD_PADDING * 2;
        int totalWidth = 104 + 14 + 104;
        int startX = layout.listX + CARD_PADDING + Math.max(0, (innerWidth - totalWidth) / 2);
        return leftButton ? startX : startX + 118;
    }

    private int getFooterButtonY(Layout layout)
    {
        return layout.listY + layout.listHeight - CARD_PADDING - BUTTON_HEIGHT - 4;
    }

    private static final int FIELD_SHELL_HEIGHT = 22;
    private static final int FIELD_PAD_X = 5;
    private static final int FIELD_PAD_Y = 7;

    private TextFieldWidget createField(int x, int y, int width, int maxLength)
    {
        TextFieldWidget field = new TextFieldWidget(this.textRenderer, x + FIELD_PAD_X, y + FIELD_PAD_Y, width - FIELD_PAD_X * 2, 20, Text.empty());
        field.setMaxLength(maxLength);
        field.setDrawsBackground(false);
        field.setCentered(false);
        field.setEditableColor(COLOR_VALUE);
        field.setUneditableColor(COLOR_MUTED);
        field.setChangedListener(value -> refreshButtons());
        this.addDrawableChild(field);
        return field;
    }

    private void drawFieldShell(DrawContext context, TextFieldWidget field)
    {
        if (field == null)
        {
            return;
        }

        int shellX = field.getX() - FIELD_PAD_X;
        int shellY = field.getY() - FIELD_PAD_Y;
        fillCard(context, shellX, shellY, field.getWidth() + FIELD_PAD_X * 2, FIELD_SHELL_HEIGHT, COLOR_INSET, field.isFocused() ? COLOR_ACCENT : COLOR_BORDER_SOFT);
    }

    private int getDetailFieldWidth(Layout layout)
    {
        return Math.min(260, layout.detailWidth - CARD_PADDING * 2);
    }

    private int getDetailFieldX(Layout layout)
    {
        return layout.detailX + (layout.detailWidth - getDetailFieldWidth(layout)) / 2;
    }

    private void populateFields()
    {
        ProjectEntry selected = getSelectedProject();
        if (selected == null)
        {
            return;
        }
        this.nameField.setText(selected.name);
    }

    private void applyCurrentEdits()
    {
        ProjectEntry selected = getSelectedProject();
        if (selected == null)
        {
            return;
        }

        String name = this.nameField.getText().trim();
        if (!name.isEmpty())
        {
            selected.name = name;
        }

    }

    private void refreshButtons()
    {
        ProjectEntry selected = getSelectedProject();
        boolean hasSelected = selected != null;
        boolean validName = this.nameField == null || !this.nameField.getText().trim().isEmpty();

        if (this.applyButton != null)
        {
            this.applyButton.active = hasSelected && validName;
        }
        if (this.deleteButton != null)
        {
            this.deleteButton.active = hasSelected && Configs.PROJECTS.size() > 1;
            this.deleteButton.setMessage(Text.literal(this.deleteConfirm ? "Confirm Delete" : "Remove"));
        }
        if (this.setActiveButton != null)
        {
            this.setActiveButton.active = hasSelected && !selected.id.equals(Configs.activeProjectId);
        }
    }

    private ProjectEntry getSelectedProject()
    {
        List<ProjectEntry> projects = Configs.PROJECTS;
        if (projects.isEmpty())
        {
            return null;
        }

        this.selectedIndex = Math.max(0, Math.min(this.selectedIndex, projects.size() - 1));
        return projects.get(this.selectedIndex);
    }

    private void handleDelete()
    {
        ProjectEntry selected = getSelectedProject();
        if (selected == null || Configs.PROJECTS.size() <= 1)
        {
            return;
        }

        if (!this.deleteConfirm)
        {
            this.deleteConfirm = true;
            refreshButtons();
            return;
        }

        Configs.PROJECTS.remove(selected);
        if (selected.id.equals(Configs.activeProjectId))
        {
            Configs.activeProjectId = Configs.PROJECTS.getFirst().id;
        }
        this.deleteConfirm = false;
        Configs.saveToFile();
        MinecraftClient.getInstance().setScreen(new ProjectManagerScreen(this.parent));
    }

    private void drawStatusChip(DrawContext context, int x, int y, String label, int accentColor)
    {
        int width = this.textRenderer.getWidth(label) + 14;
        fillCard(context, x, y, width, 16, COLOR_INSET, accentColor);
        MmmUi.drawTextWithin(context, this.textRenderer, label, x + 7, y + 4, width - 14, accentColor, false);
    }

    private void drawStatCard(DrawContext context, int x, int y, int width, int height, String label, String value, String suffix)
    {
        fillCard(context, x, y, width, height, COLOR_CARD_SOFT, COLOR_BORDER_SOFT);
        int textWidth = width - CARD_PADDING * 2;
        MmmUi.drawTextWithin(context, this.textRenderer, label, x + CARD_PADDING, y + 7, textWidth, COLOR_LABEL, false);
        MmmUi.drawTextWithin(context, this.textRenderer, value, x + CARD_PADDING, y + 20, textWidth, COLOR_VALUE, false);
        MmmUi.drawTextWithin(context, this.textRenderer, suffix, x + CARD_PADDING, y + 32, textWidth, COLOR_MUTED, false);
    }

    private void drawPill(DrawContext context, int x, int y, int width, int height, String text, int fillColor, int borderColor)
    {
        fillCard(context, x, y, width, height, fillColor, borderColor);
        String clipped = MmmUi.truncate(this.textRenderer, text, width - 8);
        int textX = x + Math.max(4, (width - this.textRenderer.getWidth(clipped)) / 2);
        context.drawText(this.textRenderer, Text.literal(clipped), textX, y + 4, COLOR_ACCENT, false);
    }

    private void fillCard(DrawContext context, int x, int y, int width, int height, int fillColor, int borderColor)
    {
        MmmUi.card(context, x, y, width, height, fillColor, borderColor);
    }

    private void drawScrollbar(DrawContext context, int x, int y, int height, int mouseX, int mouseY, int visibleRows)
    {
        int maxScroll = Math.max(0, Configs.PROJECTS.size() - visibleRows);
        if (maxScroll <= 0)
        {
            return;
        }

        int thumbHeight = getScrollbarThumbHeight(height, visibleRows);
        int thumbY = y + getScrollbarThumbOffset(height, thumbHeight, maxScroll);
        context.fill(x, y, x + SCROLLBAR_WIDTH, y + height, MmmUi.SCROLLBAR_TRACK);
        context.drawBorder(x, y, SCROLLBAR_WIDTH, height, COLOR_BORDER_SOFT);
        int thumbColor = this.draggingScrollbar ? MmmUi.SCROLLBAR_THUMB_ACTIVE : isOverScrollbar(mouseX, mouseY) ? MmmUi.SCROLLBAR_THUMB_HOVER : MmmUi.SCROLLBAR_THUMB;
        context.fill(x + 1, thumbY, x + SCROLLBAR_WIDTH - 1, thumbY + thumbHeight, thumbColor);
    }

    private int getVisibleRowCount(Layout layout)
    {
        return Math.max(1, (layout.listHeight - 68 - BUTTON_HEIGHT - 10) / ROW_HEIGHT);
    }

    private boolean isMouseInsideList(double mouseX, double mouseY)
    {
        Layout layout = computeLayout();
        return mouseX >= layout.listX + CARD_PADDING
                && mouseX <= layout.listX + layout.listWidth - CARD_PADDING
                && mouseY >= layout.listY + 44
                && mouseY <= layout.listY + layout.listHeight - BUTTON_HEIGHT - 16;
    }

    private boolean isOverScrollbar(double mouseX, double mouseY)
    {
        Layout layout = computeLayout();
        int listX = layout.listX + CARD_PADDING;
        int listY = layout.listY + 44;
        int listWidth = layout.listWidth - CARD_PADDING * 2;
        int listHeight = layout.listHeight - 56 - BUTTON_HEIGHT - 10;
        return mouseX >= listX + listWidth - SCROLLBAR_WIDTH
                && mouseX <= listX + listWidth
                && mouseY >= listY
                && mouseY <= listY + listHeight;
    }

    private void updateScrollFromMouse(double mouseY)
    {
        Layout layout = computeLayout();
        int visibleRows = getVisibleRowCount(layout);
        int maxScroll = Math.max(0, Configs.PROJECTS.size() - visibleRows);
        if (maxScroll <= 0)
        {
            this.scrollOffset = 0;
            return;
        }

        int trackY = layout.listY + 44;
        int trackHeight = layout.listHeight - 56 - BUTTON_HEIGHT - 10;
        int thumbHeight = getScrollbarThumbHeight(trackHeight, visibleRows);
        int travel = Math.max(1, trackHeight - thumbHeight);
        double thumbTop = mouseY - thumbHeight / 2.0D;
        double normalized = Math.max(0.0D, Math.min(1.0D, (thumbTop - trackY) / travel));
        setScrollOffset((int) Math.round(normalized * maxScroll));
    }

    private int getScrollbarThumbHeight(int trackHeight, int visibleRows)
    {
        if (Configs.PROJECTS.isEmpty())
        {
            return trackHeight;
        }
        int thumbHeight = (int) Math.round((visibleRows / (double) Configs.PROJECTS.size()) * trackHeight);
        return Math.max(SCROLLBAR_MIN_THUMB, Math.min(trackHeight, thumbHeight));
    }

    private int getScrollbarThumbOffset(int trackHeight, int thumbHeight, int maxScroll)
    {
        if (maxScroll <= 0)
        {
            return 0;
        }
        return (int) Math.round((this.scrollOffset / (double) maxScroll) * (trackHeight - thumbHeight));
    }

    private void setScrollOffset(int offset)
    {
        Layout layout = computeLayout();
        int maxScroll = Math.max(0, Configs.PROJECTS.size() - getVisibleRowCount(layout));
        this.scrollOffset = Math.max(0, Math.min(maxScroll, offset));
    }

    private float getOpenAnimationProgress()
    {
        if (this.openedAtMs <= 0L)
        {
            return 1.0F;
        }
        long elapsed = System.currentTimeMillis() - this.openedAtMs;
        float normalized = MathHelper.clamp(elapsed / 280.0F, 0.0F, 1.0F);
        return normalized * normalized * (3.0F - 2.0F * normalized);
    }

    private String truncateToWidth(String value, int maxWidth)
    {
        return MmmUi.truncate(this.textRenderer, value, maxWidth);
    }

    private Layout computeLayout()
    {
        int availableWidth = Math.max(320, MmmUi.contentWidth(this.width) - PANEL_MARGIN);
        int panelWidth = Math.min(760, Math.max(520, availableWidth));
        int topY = MmmUi.TOP_BAR_HEIGHT + 10;
        int availableHeight = Math.max(260, this.height - topY - 12);
        int panelHeight = Math.min(520, Math.max(360, availableHeight));
        panelHeight = Math.min(panelHeight, availableHeight);
        int panelX = MmmUi.centerContentX(this.width, panelWidth);
        int panelY = topY + Math.max(0, (availableHeight - panelHeight) / 2);
        int contentX = panelX + PANEL_PADDING;
        int contentWidth = panelWidth - PANEL_PADDING * 2;
        int headerY = panelY + PANEL_PADDING;
        int listY = headerY + 58;
        int listHeight = panelHeight - (listY - panelY) - PANEL_PADDING;
        int listWidth = Math.max(300, (int) (contentWidth * 0.48F));
        int detailWidth = contentWidth - listWidth - CARD_GAP;

        return new Layout(
                panelX,
                panelY,
                panelWidth,
                panelHeight,
                panelX + panelWidth,
                panelY + panelHeight,
                contentX,
                contentWidth,
                headerY,
                contentX,
                listY,
                listWidth,
                listHeight,
                contentX + listWidth + CARD_GAP,
                listY,
                detailWidth,
                listHeight);
    }

    private record Layout(
            int panelX,
            int panelY,
            int panelWidth,
            int panelHeight,
            int panelRight,
            int panelBottom,
            int contentX,
            int contentWidth,
            int headerY,
            int listX,
            int listY,
            int listWidth,
            int listHeight,
            int detailX,
            int detailY,
            int detailWidth,
            int detailHeight)
    {
        private Layout withPanelY(int newPanelY)
        {
            int delta = newPanelY - this.panelY;
            return new Layout(
                    this.panelX,
                    newPanelY,
                    this.panelWidth,
                    this.panelHeight,
                    this.panelRight,
                    this.panelBottom + delta,
                    this.contentX,
                    this.contentWidth,
                    this.headerY + delta,
                    this.listX,
                    this.listY + delta,
                    this.listWidth,
                    this.listHeight,
                    this.detailX,
                    this.detailY + delta,
                    this.detailWidth,
                    this.detailHeight);
        }
    }
}
