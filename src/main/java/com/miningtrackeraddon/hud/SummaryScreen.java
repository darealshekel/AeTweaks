package com.miningtrackeraddon.hud;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.miningtrackeraddon.tracker.MiningStats;
import com.miningtrackeraddon.storage.SessionData;
import com.miningtrackeraddon.storage.WorldSessionContext;
import com.miningtrackeraddon.util.UiFormat;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class SummaryScreen extends Screen
{
    private static final int OUTER_PADDING = 16;
    private static final int SECTION_PADDING = 10;
    private static final int SECTION_GAP = 10;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BREAKDOWN_ROW_HEIGHT = 20;
    private static final int SEARCH_HEIGHT = 20;
    private static final int SCROLLBAR_WIDTH = 8;
    private static final int SCROLLBAR_MIN_THUMB = 18;
    private static final Map<String, String> NAME_CACHE = new LinkedHashMap<>();
    private static final Map<String, ItemStack> ICON_CACHE = new LinkedHashMap<>();

    private final SessionData session;
    private final Screen parent;
    private final String worldName;
    private final String heading;
    private final List<BlockBreakdownEntry> allEntries = new ArrayList<>();
    private final List<BlockBreakdownEntry> filteredEntries = new ArrayList<>();
    private boolean clipboardMessageVisible;
    private TextFieldWidget searchField;
    private int breakdownScrollOffset;
    private boolean draggingScrollbar;

    public SummaryScreen(SessionData session, Screen parent)
    {
        this(session, parent, resolveWorldName(), "Session Summary");
    }

    public SummaryScreen(SessionData session, Screen parent, String worldName, String heading)
    {
        super(Text.literal(heading));
        this.session = session;
        this.parent = parent;
        this.worldName = worldName;
        this.heading = heading;
    }

    @Override
    protected void init()
    {
        buildBreakdownEntries();

        int panelWidth = getPanelWidth();
        int panelHeight = getPanelHeight();
        int panelX = (this.width - panelWidth) / 2;
        int panelY = (this.height - panelHeight) / 2;
        int buttonY = panelY + panelHeight - OUTER_PADDING - BUTTON_HEIGHT;
        int contentX = panelX + OUTER_PADDING;
        int breakdownY = getBreakdownSectionY(panelY);

        this.searchField = new TextFieldWidget(this.textRenderer, contentX + SECTION_PADDING, breakdownY + 28, panelWidth - OUTER_PADDING * 2 - SECTION_PADDING * 2, SEARCH_HEIGHT, Text.empty());
        this.searchField.setMaxLength(64);
        this.searchField.setChangedListener(text -> refreshFilteredEntries());
        this.addDrawableChild(this.searchField);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> close()).dimensions(panelX + OUTER_PADDING, buttonY, 120, BUTTON_HEIGHT).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Copy Summary"), button ->
        {
            MinecraftClient.getInstance().keyboard.setClipboard(buildShareText());
            clipboardMessageVisible = true;
        }).dimensions(panelX + panelWidth - OUTER_PADDING - 120, buttonY, 120, BUTTON_HEIGHT).build());

        refreshFilteredEntries();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta)
    {
        context.fill(0, 0, this.width, this.height, 0xFF101010);

        int panelWidth = getPanelWidth();
        int panelHeight = getPanelHeight();
        int panelX = (this.width - panelWidth) / 2;
        int panelY = (this.height - panelHeight) / 2;
        int contentX = panelX + OUTER_PADDING;
        int contentWidth = panelWidth - OUTER_PADDING * 2;
        int buttonY = panelY + panelHeight - OUTER_PADDING - BUTTON_HEIGHT;
        int y = panelY + OUTER_PADDING;

        context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xFF161616);
        context.drawBorder(panelX, panelY, panelWidth, panelHeight, 0xFF8F7A55);

        context.drawText(this.textRenderer, Text.literal(this.heading), contentX, y, UiFormat.YELLOW, true);
        y += 16;
        context.drawText(this.textRenderer, Text.literal(this.worldName), contentX, y, UiFormat.TEXT_MUTED, false);
        y += 18;

        int sessionHeight = getSessionSectionHeight();
        drawSectionBackground(context, contentX, y, contentWidth, sessionHeight);
        drawSessionSection(context, contentX + SECTION_PADDING, y + SECTION_PADDING);
        y += sessionHeight + SECTION_GAP;

        MiningStats.GoalProgress dailyGoal = MiningStats.getDailyGoalProgress();
        if (dailyGoal.enabled())
        {
            drawSectionBackground(context, contentX, y, contentWidth, 52);
            drawGoalSection(context, contentX + SECTION_PADDING, y + SECTION_PADDING, contentWidth - SECTION_PADDING * 2, dailyGoal);
            y += 52 + SECTION_GAP;
        }

        int breakdownHeight = buttonY - y - SECTION_GAP;
        drawSectionBackground(context, contentX, y, contentWidth, breakdownHeight);
        drawBreakdownSection(context, contentX + SECTION_PADDING, y + SECTION_PADDING, contentWidth - SECTION_PADDING * 2, breakdownHeight - SECTION_PADDING * 2, mouseX, mouseY);

        if (searchField != null)
        {
            searchField.setY(y + SECTION_PADDING + 28);
            searchField.setX(contentX + SECTION_PADDING);
            searchField.setWidth(contentWidth - SECTION_PADDING * 2);
        }

        if (clipboardMessageVisible)
        {
            context.drawText(this.textRenderer, Text.literal("Summary copied to clipboard."), contentX, buttonY - 14, UiFormat.LIGHT_GREEN, false);
        }

        super.render(context, mouseX, mouseY, delta);

        if (searchField != null && searchField.getText().isBlank() && searchField.isFocused() == false)
        {
            context.drawText(this.textRenderer, Text.literal("Search blocks..."), searchField.getX() + 4, searchField.getY() + 6, UiFormat.TEXT_MUTED, false);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount)
    {
        if (isMouseInsideBreakdown(mouseX, mouseY))
        {
            setBreakdownScrollOffset(breakdownScrollOffset + (verticalAmount < 0 ? BREAKDOWN_ROW_HEIGHT : -BREAKDOWN_ROW_HEIGHT));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button)
    {
        if (button == 0 && isOverScrollbar(mouseX, mouseY))
        {
            draggingScrollbar = true;
            updateScrollFromMouse(mouseY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY)
    {
        if (draggingScrollbar)
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
            draggingScrollbar = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void close()
    {
        MinecraftClient.getInstance().setScreen(parent);
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

    private void buildBreakdownEntries()
    {
        this.allEntries.clear();
        for (Map.Entry<String, Long> entry : MiningStats.getSortedBreakdown(session).entrySet())
        {
            String blockId = entry.getKey();
            this.allEntries.add(new BlockBreakdownEntry(blockId, getCachedBlockName(blockId), entry.getValue(), getCachedIcon(blockId)));
        }
        this.allEntries.sort(Comparator.comparingLong(BlockBreakdownEntry::count).reversed().thenComparing(BlockBreakdownEntry::name));
    }

    private void refreshFilteredEntries()
    {
        this.filteredEntries.clear();
        String query = searchField == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        for (BlockBreakdownEntry entry : this.allEntries)
        {
            if (query.isBlank() || entry.searchName().contains(query))
            {
                this.filteredEntries.add(entry);
            }
        }
        setBreakdownScrollOffset(0);
    }

    private void drawSectionBackground(DrawContext context, int x, int y, int width, int height)
    {
        context.fill(x, y, x + width, y + height, 0xFF202020);
        context.drawBorder(x, y, width, height, 0x553C3C3C);
    }

    private void drawSessionSection(DrawContext context, int x, int y)
    {
        context.drawText(this.textRenderer, Text.literal("Session Stats"), x, y, UiFormat.YELLOW, false);
        y += 18;
        context.drawItem(new ItemStack(Blocks.DIAMOND_ORE), x, y - 2);
        context.drawText(this.textRenderer, Text.literal("Total Mined: " + UiFormat.formatBlocks(session.totalBlocks)), x + 22, y + 1, UiFormat.TEXT_PRIMARY, false);
        y += 20;
        context.drawText(this.textRenderer, Text.literal("Session Time: " + formatDuration(session.getDurationMs())), x, y, UiFormat.TEXT_PRIMARY, false);
        y += 20;
        context.drawText(this.textRenderer, Text.literal("Average Rate: " + UiFormat.formatBlocksPerHour(session.getAverageBlocksPerHour())), x, y, UiFormat.TEXT_PRIMARY, false);
        y += 20;
        context.drawText(this.textRenderer, Text.literal("Best Streak: " + session.bestStreakSeconds + "s"), x, y, UiFormat.TEXT_PRIMARY, false);
    }

    private void drawGoalSection(DrawContext context, int x, int y, int width, MiningStats.GoalProgress progress)
    {
        context.drawText(this.textRenderer, Text.literal("Daily Goal"), x, y, UiFormat.YELLOW, false);
        context.drawText(this.textRenderer, Text.literal(UiFormat.formatProgress(progress.current(), progress.target())), x + 86, y, UiFormat.TEXT_PRIMARY, false);
        String percentText = progress.getPercent() + "%";
        int percentX = x + width - this.textRenderer.getWidth(Text.literal(percentText));
        int fillColor = UiFormat.getGoalColor(progress);
        context.drawText(this.textRenderer, Text.literal(percentText), percentX, y, fillColor, false);

        int barY = y + 18;
        int fillWidth = progress.target() <= 0 ? 0 : (int) Math.min(width, (width * (double) progress.current()) / progress.target());
        context.fill(x, barY, x + width, barY + 8, 0xFF343434);
        context.fill(x, barY, x + fillWidth, barY + 8, fillColor);
        context.drawBorder(x, barY, width, 8, 0xFF707070);
    }

    private void drawBreakdownSection(DrawContext context, int x, int y, int width, int height, int mouseX, int mouseY)
    {
        context.drawText(this.textRenderer, Text.literal("Block Breakdown"), x, y, UiFormat.YELLOW, false);

        int listY = y + 54;
        int listHeight = Math.max(56, height - 54);
        int listWidth = width - SCROLLBAR_WIDTH - 6;

        context.drawBorder(x, listY, width, listHeight, 0x443C3C3C);
        context.enableScissor(x, listY, x + listWidth, listY + listHeight);

        int drawY = listY + 4 - breakdownScrollOffset;
        if (filteredEntries.isEmpty())
        {
            context.drawText(this.textRenderer, Text.literal("No matching blocks found."), x + 6, drawY + 2, UiFormat.TEXT_MUTED, false);
        }
        else
        {
            for (BlockBreakdownEntry entry : filteredEntries)
            {
                if (drawY + BREAKDOWN_ROW_HEIGHT >= listY && drawY <= listY + listHeight)
                {
                    context.drawItem(entry.icon(), x + 4, drawY);
                    context.drawText(this.textRenderer, Text.literal(entry.name() + ": " + UiFormat.formatCompact(entry.count())), x + 26, drawY + 4, UiFormat.TEXT_PRIMARY, false);
                }
                drawY += BREAKDOWN_ROW_HEIGHT;
            }
        }
        context.disableScissor();
        drawScrollbar(context, x + width - SCROLLBAR_WIDTH, listY, listHeight, mouseX, mouseY);
    }

    private void drawScrollbar(DrawContext context, int x, int y, int height, int mouseX, int mouseY)
    {
        int maxScroll = getMaxBreakdownScroll();
        if (maxScroll <= 0)
        {
            return;
        }

        int thumbHeight = getScrollbarThumbHeight(height);
        int thumbY = y + getScrollbarThumbOffset(height, thumbHeight);
        context.fill(x, y, x + SCROLLBAR_WIDTH, y + height, 0x88303030);
        context.drawBorder(x, y, SCROLLBAR_WIDTH, height, 0xFF5A5A5A);
        boolean hovered = isOverScrollbar(mouseX, mouseY);
        int thumbColor = draggingScrollbar ? 0xFFE0E0E0 : hovered ? 0xFFC2C2C2 : 0xFF9D9D9D;
        context.fill(x + 1, thumbY, x + SCROLLBAR_WIDTH - 1, thumbY + thumbHeight, thumbColor);
        context.drawBorder(x, thumbY, SCROLLBAR_WIDTH, thumbHeight, 0xFF2A2A2A);
    }

    private boolean isMouseInsideBreakdown(double mouseX, double mouseY)
    {
        int panelWidth = getPanelWidth();
        int panelHeight = getPanelHeight();
        int panelX = (this.width - panelWidth) / 2;
        int panelY = (this.height - panelHeight) / 2;
        int contentX = panelX + OUTER_PADDING + SECTION_PADDING;
        int breakdownY = getBreakdownSectionY(panelY) + SECTION_PADDING + 54;
        int contentWidth = panelWidth - OUTER_PADDING * 2 - SECTION_PADDING * 2;
        int buttonY = panelY + panelHeight - OUTER_PADDING - BUTTON_HEIGHT;
        int breakdownHeight = buttonY - getBreakdownSectionY(panelY) - SECTION_GAP - SECTION_PADDING * 2 - 54;
        return mouseX >= contentX && mouseX <= contentX + contentWidth && mouseY >= breakdownY && mouseY <= breakdownY + breakdownHeight;
    }

    private boolean isOverScrollbar(double mouseX, double mouseY)
    {
        int[] metrics = getScrollbarMetrics();
        return mouseX >= metrics[0] && mouseX <= metrics[0] + SCROLLBAR_WIDTH && mouseY >= metrics[1] && mouseY <= metrics[1] + metrics[2];
    }

    private void updateScrollFromMouse(double mouseY)
    {
        int[] metrics = getScrollbarMetrics();
        int trackY = metrics[1];
        int trackHeight = metrics[2];
        int thumbHeight = getScrollbarThumbHeight(trackHeight);
        int travel = Math.max(1, trackHeight - thumbHeight);
        double thumbTop = mouseY - thumbHeight / 2.0D;
        double normalized = Math.max(0.0D, Math.min(1.0D, (thumbTop - trackY) / travel));
        setBreakdownScrollOffset((int) Math.round(normalized * getMaxBreakdownScroll()));
    }

    private void setBreakdownScrollOffset(int offset)
    {
        this.breakdownScrollOffset = Math.max(0, Math.min(getMaxBreakdownScroll(), offset));
    }

    private int getMaxBreakdownScroll()
    {
        int totalContentHeight = filteredEntries.size() * BREAKDOWN_ROW_HEIGHT + 8;
        return Math.max(0, totalContentHeight - getBreakdownListHeight());
    }

    private int getBreakdownListHeight()
    {
        int panelHeight = getPanelHeight();
        int panelY = (this.height - panelHeight) / 2;
        int buttonY = panelY + panelHeight - OUTER_PADDING - BUTTON_HEIGHT;
        int breakdownY = getBreakdownSectionY(panelY);
        return Math.max(56, buttonY - breakdownY - SECTION_GAP - SECTION_PADDING * 2 - 54);
    }

    private int[] getScrollbarMetrics()
    {
        int panelWidth = getPanelWidth();
        int panelHeight = getPanelHeight();
        int panelX = (this.width - panelWidth) / 2;
        int panelY = (this.height - panelHeight) / 2;
        int breakdownY = getBreakdownSectionY(panelY);
        int contentX = panelX + OUTER_PADDING + SECTION_PADDING;
        int contentWidth = panelWidth - OUTER_PADDING * 2 - SECTION_PADDING * 2;
        int listY = breakdownY + SECTION_PADDING + 54;
        return new int[] { contentX + contentWidth - SCROLLBAR_WIDTH, listY, getBreakdownListHeight() };
    }

    private int getScrollbarThumbHeight(int trackHeight)
    {
        int totalContentHeight = filteredEntries.size() * BREAKDOWN_ROW_HEIGHT + 8;
        if (totalContentHeight <= 0)
        {
            return trackHeight;
        }
        int thumbHeight = (int) Math.round((getBreakdownListHeight() / (double) totalContentHeight) * trackHeight);
        return Math.max(SCROLLBAR_MIN_THUMB, Math.min(trackHeight, thumbHeight));
    }

    private int getScrollbarThumbOffset(int trackHeight, int thumbHeight)
    {
        int maxScroll = getMaxBreakdownScroll();
        if (maxScroll <= 0)
        {
            return 0;
        }
        return (int) Math.round((breakdownScrollOffset / (double) maxScroll) * (trackHeight - thumbHeight));
    }

    private int getPanelWidth()
    {
        return Math.min(560, Math.max(420, this.width - 40));
    }

    private int getPanelHeight()
    {
        return Math.min(this.height - 24, 460);
    }

    private int getSessionSectionHeight()
    {
        return SECTION_PADDING * 2 + 18 + 4 * 20;
    }

    private int getBreakdownSectionY(int panelY)
    {
        int y = panelY + OUTER_PADDING + 34 + getSessionSectionHeight() + SECTION_GAP;
        if (MiningStats.getDailyGoalProgress().enabled())
        {
            y += 52 + SECTION_GAP;
        }
        return y;
    }

    private String buildShareText()
    {
        StringBuilder builder = new StringBuilder();
        builder.append(heading).append('\n');
        builder.append("World/Server: ").append(worldName).append('\n');
        builder.append("Total Mined: ").append(UiFormat.formatBlocks(session.totalBlocks)).append('\n');
        builder.append("Session Time: ").append(formatDuration(session.getDurationMs())).append('\n');
        builder.append("Average Rate: ").append(UiFormat.formatBlocksPerHour(session.getAverageBlocksPerHour())).append('\n');
        builder.append("Best Streak: ").append(session.bestStreakSeconds).append("s\n");
        MiningStats.GoalProgress dailyGoal = MiningStats.getDailyGoalProgress();
        if (dailyGoal.enabled())
        {
            builder.append(dailyGoal.label()).append(": ").append(UiFormat.formatProgress(dailyGoal.current(), dailyGoal.target())).append(" (").append(dailyGoal.getPercent()).append("%)\n");
        }
        for (BlockBreakdownEntry entry : allEntries)
        {
            builder.append(entry.name()).append(": ").append(entry.count()).append('\n');
        }
        return builder.toString().trim();
    }

    private String formatDuration(long durationMs)
    {
        long totalSeconds = durationMs / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    private String getCachedBlockName(String id)
    {
        return NAME_CACHE.computeIfAbsent(id, key -> resolveBlock(key).getName().getString());
    }

    private ItemStack getCachedIcon(String id)
    {
        ItemStack cached = ICON_CACHE.get(id);
        if (cached != null)
        {
            return cached.copy();
        }
        Block block = resolveBlock(id);
        Item item = block.asItem();
        ItemStack stack = item == Items.AIR ? new ItemStack(Blocks.STONE) : new ItemStack(item);
        ICON_CACHE.put(id, stack.copy());
        return stack;
    }

    private Block resolveBlock(String id)
    {
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null)
        {
            return Blocks.STONE;
        }
        Block block = Registries.BLOCK.get(identifier);
        return block == null ? Blocks.STONE : block;
    }

    private static String resolveWorldName()
    {
        MinecraftClient client = MinecraftClient.getInstance();
        WorldSessionContext.update(client);
        return WorldSessionContext.getCurrentWorldName();
    }

    private record BlockBreakdownEntry(String id, String name, long count, ItemStack icon)
    {
        private String searchName()
        {
            return name.toLowerCase(Locale.ROOT);
        }
    }
}
