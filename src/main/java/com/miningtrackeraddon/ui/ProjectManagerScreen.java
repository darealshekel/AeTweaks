package com.miningtrackeraddon.ui;

import java.util.List;

import com.miningtrackeraddon.config.Configs;
import com.miningtrackeraddon.config.Configs.ProjectEntry;
import com.miningtrackeraddon.tracker.MiningStats;
import com.miningtrackeraddon.util.UiFormat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public class ProjectManagerScreen extends Screen
{
    private final Screen parent;
    private int selectedIndex;
    private TextFieldWidget nameField;
    private TextFieldWidget progressField;
    private ButtonWidget applyButton;
    private ButtonWidget deleteButton;
    private ButtonWidget setActiveButton;
    private boolean deleteConfirm;

    public ProjectManagerScreen(Screen parent)
    {
        super(Text.literal("Projects"));
        this.parent = parent;
    }

    @Override
    protected void init()
    {
        this.selectedIndex = Math.min(this.selectedIndex, Math.max(0, Configs.PROJECTS.size() - 1));

        int centerX = this.width / 2;
        int panelWidth = 560;
        int panelX = centerX - panelWidth / 2;
        int listX = panelX + 28;
        int formX = centerX + 16;
        int topY = 96;

        int rowY = topY;
        for (int index = 0; index < Math.min(Configs.PROJECTS.size(), 7); index++)
        {
            int currentIndex = index;
            this.addDrawableChild(ButtonWidget.builder(Text.literal(projectRowLabel(Configs.PROJECTS.get(index))), button -> {
                applyCurrentEdits();
                this.selectedIndex = currentIndex;
                this.deleteConfirm = false;
                populateFields();
                refreshButtons();
            }).dimensions(listX, rowY, 210, 20).build());
            rowY += 24;
        }

        this.addDrawableChild(ButtonWidget.builder(Text.literal("New Project"), button -> {
            applyCurrentEdits();
            ProjectEntry entry = Configs.createProject("Project " + (Configs.PROJECTS.size() + 1));
            this.selectedIndex = Configs.PROJECTS.indexOf(entry);
            Configs.activeProjectId = entry.id;
            Configs.saveToFile();
            MinecraftClient.getInstance().setScreen(new ProjectManagerScreen(this.parent));
        }).dimensions(listX, topY + 194, 102, 20).build());

        this.deleteButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Remove"), button -> handleDelete()).dimensions(listX + 108, topY + 194, 102, 20).build());
        this.nameField = createField(formX, topY + 22, 250, 64);
        this.progressField = createField(formX, topY + 78, 250, 16);

        this.applyButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Apply Changes"), button -> {
            applyCurrentEdits();
            Configs.saveToFile();
            MinecraftClient.getInstance().setScreen(new ProjectManagerScreen(this.parent));
        }).dimensions(formX, topY + 122, 250, 20).build());

        this.setActiveButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Set Active"), button -> {
            applyCurrentEdits();
            ProjectEntry selected = getSelectedProject();
            if (selected != null)
            {
                Configs.activeProjectId = selected.id;
                Configs.saveToFile();
                MinecraftClient.getInstance().setScreen(new ProjectManagerScreen(this.parent));
            }
        }).dimensions(formX, topY + 148, 250, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> close()).dimensions(centerX - 50, this.height - 34, 100, 20).build());

        populateFields();
        refreshButtons();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta)
    {
        context.fill(0, 0, this.width, this.height, 0xFF101010);
        int centerX = this.width / 2;
        int panelWidth = 560;
        int panelX = centerX - panelWidth / 2;
        int panelTop = 52;
        int panelHeight = Math.min(410, this.height - 92);
        int formX = centerX + 16;

        context.drawBorder(panelX, panelTop, panelWidth, panelHeight, 0xFF5F532E);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, 28, UiFormat.YELLOW);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Projects track named mining progress totals with persistent saved data."), centerX, 44, UiFormat.TEXT_MUTED);
        context.drawText(this.textRenderer, Text.literal("Project Name"), formX, 96, UiFormat.YELLOW, false);
        context.drawText(this.textRenderer, Text.literal("Blocks Mined"), formX, 152, UiFormat.YELLOW, false);

        ProjectEntry selected = getSelectedProject();
        if (selected != null && selected.id.equals(Configs.activeProjectId))
        {
            context.drawText(this.textRenderer, Text.literal("Active Project"), formX, 180, UiFormat.LIGHT_GREEN, false);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close()
    {
        applyCurrentEdits();
        Configs.saveToFile();
        MinecraftClient.getInstance().setScreen(this.parent);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta)
    {
    }

    private TextFieldWidget createField(int x, int y, int width, int maxLength)
    {
        TextFieldWidget field = new TextFieldWidget(this.textRenderer, x, y, width, 20, Text.empty());
        field.setMaxLength(maxLength);
        field.setChangedListener(text -> refreshButtons());
        this.addDrawableChild(field);
        return field;
    }

    private void populateFields()
    {
        ProjectEntry selected = getSelectedProject();
        if (selected == null)
        {
            return;
        }
        this.nameField.setText(selected.name);
        this.progressField.setText(String.valueOf(selected.progress));
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

        if (isNonNegative(this.progressField.getText()))
        {
            long progress = Long.parseLong(this.progressField.getText());
            selected.progress = progress;
            if (selected.id.equals(Configs.activeProjectId))
            {
                MiningStats.setActiveProjectProgress(progress);
            }
        }
    }

    private void refreshButtons()
    {
        ProjectEntry selected = getSelectedProject();
        boolean hasSelected = selected != null;
        boolean validProgress = isNonNegative(this.progressField == null ? "" : this.progressField.getText());
        boolean validName = this.nameField == null || !this.nameField.getText().trim().isEmpty();

        if (this.applyButton != null)
        {
            this.applyButton.active = hasSelected && validProgress && validName;
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

    private String projectRowLabel(ProjectEntry project)
    {
        return UiFormat.truncate(project.name, 17) + " | " + UiFormat.formatBlocks(project.progress);
    }
}
