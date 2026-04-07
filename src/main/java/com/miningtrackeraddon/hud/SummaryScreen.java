package com.miningtrackeraddon.hud;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.miningtrackeraddon.storage.SessionData;
import com.miningtrackeraddon.storage.WorldSessionContext;
import com.miningtrackeraddon.tracker.MiningStats;
import com.miningtrackeraddon.util.UiFormat;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.Identifier;

public class SummaryScreen extends Screen
{
    private static final int PANEL_MARGIN = 20;
    private static final int PANEL_PADDING = 18;
    private static final int CARD_PADDING = 12;
    private static final int CARD_GAP = 10;
    private static final int BUTTON_HEIGHT = 20;
    private static final int SEARCH_HEIGHT = 20;
    private static final int BREAKDOWN_ROW_HEIGHT = 20;
    private static final int SCROLLBAR_WIDTH = 8;
    private static final int SCROLLBAR_MIN_THUMB = 18;
    private static final int COLOR_OVERLAY = 0xB8121620;
    private static final int COLOR_PANEL = 0xB4171F2D;
    private static final int COLOR_CARD = 0x99202A3B;
    private static final int COLOR_CARD_SOFT = 0x7F182130;
    private static final int COLOR_BORDER = 0xAA42657D;
    private static final int COLOR_ACCENT = 0xFF67E7FF;
    private static final int COLOR_ACCENT_SOFT = 0x5540D7FF;
    private static final int COLOR_GRAPH_FILL = 0xAA5CE1FF;
    private static final int COLOR_GRAPH_GRID = 0x223C5264;
    private static final int COLOR_VALUE = 0xFFF5FBFF;
    private static final int COLOR_LABEL = 0xB6C7D6E7;
    private static final int COLOR_MUTED = 0x8EA5B9CC;
    private static final int COLOR_SUCCESS = 0xFF7EEDAA;
    private static final int COLOR_WARNING = 0xFFF4D56A;
    private static final Map<String, String> NAME_CACHE = new LinkedHashMap<>();
    private static final Map<String, ItemStack> ICON_CACHE = new LinkedHashMap<>();
    private static final String SEARCH_PLACEHOLDER = "Search blocks...";

    private final SessionData session;
    private final Screen parent;
    private final String worldName;
    private final String heading;
    private final List<BlockBreakdownEntry> allEntries = new ArrayList<>();
    private final List<BlockBreakdownEntry> filteredEntries = new ArrayList<>();

    private TextFieldWidget searchField;
    private boolean clipboardMessageVisible;
    private int breakdownScrollOffset;
    private boolean draggingScrollbar;
    private long openedAtMs;

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
        this.clearChildren();
        this.clipboardMessageVisible = false;
        this.openedAtMs = System.currentTimeMillis();
        ensureCursorVisible();
        buildBreakdownEntries();

        Layout layout = computeLayout();
        this.searchField = new TextFieldWidget(this.textRenderer, layout.breakdownX + CARD_PADDING, layout.breakdownY + 28, layout.breakdownWidth - CARD_PADDING * 2, SEARCH_HEIGHT, Text.empty());
        this.searchField.setMaxLength(64);
        this.searchField.setChangedListener(value -> refreshFilteredEntries());
        this.addDrawableChild(this.searchField);

        int actionY = layout.headerY;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Copy"), button ->
        {
            MinecraftClient.getInstance().keyboard.setClipboard(buildShareText());
            clipboardMessageVisible = true;
        }).dimensions(layout.panelRight - 148, actionY - 2, 64, BUTTON_HEIGHT).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> close()).dimensions(layout.panelRight - 74, actionY - 2, 64, BUTTON_HEIGHT).build());

        refreshFilteredEntries();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta)
    {
        ensureCursorVisible();
        Layout layout = computeLayout();
        float animation = getOpenAnimationProgress();
        int animatedPanelY = layout.panelY + Math.round((1.0F - animation) * 14.0F);
        Layout animatedLayout = layout.withPanelY(animatedPanelY);
        updateDynamicWidgetBounds(animatedLayout);

        context.fill(0, 0, this.width, this.height, COLOR_OVERLAY);
        fillRoundedCard(context, layout.panelX, animatedPanelY, layout.panelWidth, layout.panelHeight, COLOR_PANEL, COLOR_BORDER);

        drawHeader(context, animatedLayout);
        drawStatCards(context, animatedLayout);
        drawGraphCard(context, animatedLayout, mouseX, mouseY, animation);
        drawGoalCard(context, animatedLayout);
        drawBreakdownCard(context, animatedLayout, mouseX, mouseY);

        super.render(context, mouseX, mouseY, delta);

        if (this.searchField != null && this.searchField.getText().isBlank() && this.searchField.isFocused() == false)
        {
            context.drawText(this.textRenderer, Text.literal(SEARCH_PLACEHOLDER), this.searchField.getX() + 6, this.searchField.getY() + 6, COLOR_MUTED, false);
        }

        if (this.clipboardMessageVisible)
        {
            context.drawText(this.textRenderer, Text.literal("Summary copied to clipboard."), animatedLayout.breakdownX, animatedLayout.panelBottom - 14, COLOR_SUCCESS, false);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount)
    {
        if (isMouseInsideBreakdown(mouseX, mouseY))
        {
            setBreakdownScrollOffset(this.breakdownScrollOffset + (verticalAmount < 0 ? BREAKDOWN_ROW_HEIGHT : -BREAKDOWN_ROW_HEIGHT));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button)
    {
        if (button == 0 && isOverScrollbar(mouseX, mouseY))
        {
            this.draggingScrollbar = true;
            updateScrollFromMouse(mouseY);
            return true;
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
        context.drawText(this.textRenderer, Text.literal(this.heading), layout.contentX, layout.headerY, COLOR_VALUE, true);
        drawPill(context, layout.contentX, layout.headerY + 18, Math.min(200, layout.graphWidth - 20), 16, this.worldName, COLOR_CARD, COLOR_ACCENT);
        context.drawText(this.textRenderer, Text.literal("Beautifully readable session stats with a live pace graph."), layout.contentX + 2, layout.headerY + 40, COLOR_LABEL, false);
    }

    private void drawStatCards(DrawContext context, Layout layout)
    {
        int cardY = layout.statY;
        int cardWidth = (layout.contentWidth - CARD_GAP * 3) / 4;

        drawStatCard(context, layout.contentX, cardY, cardWidth, 54, "Total Mined", UiFormat.formatCompact(this.session.totalBlocks), "blocks");
        drawStatCard(context, layout.contentX + (cardWidth + CARD_GAP), cardY, cardWidth, 54, "Active Time", formatClock(this.session.getDurationMs()), "pauses excluded");
        drawStatCard(context, layout.contentX + (cardWidth + CARD_GAP) * 2, cardY, cardWidth, 54, "Avg Rate", UiFormat.formatCompact(this.session.getAverageBlocksPerHour()), "blocks/hr");
        drawStatCard(context, layout.contentX + (cardWidth + CARD_GAP) * 3, cardY, cardWidth, 54, "Peak Rate", UiFormat.formatCompact(this.session.peakBlocksPerHour), "blocks/hr");
    }

    private void drawGraphCard(DrawContext context, Layout layout, int mouseX, int mouseY, float animation)
    {
        fillRoundedCard(context, layout.graphX, layout.graphY, layout.graphWidth, layout.graphHeight, COLOR_CARD, COLOR_BORDER);
        context.drawText(this.textRenderer, Text.literal("Mining Rate"), layout.graphX + CARD_PADDING, layout.graphY + 10, COLOR_VALUE, false);
        context.drawText(this.textRenderer, Text.literal("Blocks per hour over active session time"), layout.graphX + CARD_PADDING, layout.graphY + 24, COLOR_LABEL, false);

        int chartX = layout.graphX + CARD_PADDING;
        int chartY = layout.graphY + 42;
        int chartWidth = layout.graphWidth - CARD_PADDING * 2;
        int chartHeight = layout.graphHeight - 56;
        drawMiningRateGraph(context, chartX, chartY, chartWidth, chartHeight, animation);
    }

    private void drawGoalCard(DrawContext context, Layout layout)
    {
        fillRoundedCard(context, layout.goalX, layout.goalY, layout.goalWidth, layout.goalHeight, COLOR_CARD, COLOR_BORDER);
        context.drawText(this.textRenderer, Text.literal("Daily Goal"), layout.goalX + CARD_PADDING, layout.goalY + 10, COLOR_VALUE, false);

        MiningStats.GoalProgress progress = MiningStats.getDailyGoalProgress();
        if (progress.enabled() == false)
        {
            context.drawText(this.textRenderer, Text.literal("Disabled"), layout.goalX + CARD_PADDING, layout.goalY + 32, COLOR_MUTED, false);
            return;
        }

        int goalColor = UiFormat.getGoalColor(progress);
        int barX = layout.goalX + CARD_PADDING;
        int barY = layout.goalY + 42;
        int barWidth = layout.goalWidth - CARD_PADDING * 2;
        int percentWidth = this.textRenderer.getWidth(progress.getPercent() + "%");
        context.drawText(this.textRenderer, Text.literal(UiFormat.formatProgress(progress.current(), progress.target())), barX, layout.goalY + 27, COLOR_VALUE, false);
        context.drawText(this.textRenderer, Text.literal(progress.getPercent() + "%"), barX + barWidth - percentWidth, layout.goalY + 27, goalColor, false);

        int fillWidth = progress.target() <= 0 ? 0 : (int) Math.round(barWidth * Math.min(1.0D, progress.current() / (double) progress.target()));
        context.fill(barX, barY, barX + barWidth, barY + 8, 0x33293B4E);
        context.fill(barX, barY, barX + fillWidth, barY + 8, goalColor);
        context.drawBorder(barX, barY, barWidth, 8, COLOR_BORDER);

        String etaText = "ETA: " + MiningStats.getEstimatedTimeToDailyGoal();
        String streakText = "Best Streak: " + this.session.bestStreakSeconds + "s";
        context.drawText(this.textRenderer, Text.literal(etaText), barX, barY + 16, COLOR_LABEL, false);
        context.drawText(this.textRenderer, Text.literal(streakText), barX, barY + 30, COLOR_MUTED, false);
    }

    private void drawBreakdownCard(DrawContext context, Layout layout, int mouseX, int mouseY)
    {
        fillRoundedCard(context, layout.breakdownX, layout.breakdownY, layout.breakdownWidth, layout.breakdownHeight, COLOR_CARD_SOFT, COLOR_BORDER);
        context.drawText(this.textRenderer, Text.literal("Block Breakdown"), layout.breakdownX + CARD_PADDING, layout.breakdownY + 10, COLOR_VALUE, false);
        context.drawText(this.textRenderer, Text.literal("Search and scan the blocks that shaped the run."), layout.breakdownX + CARD_PADDING, layout.breakdownY + 21, COLOR_MUTED, false);

        int listX = layout.breakdownX + CARD_PADDING;
        int listY = layout.breakdownY + 60;
        int listWidth = layout.breakdownWidth - CARD_PADDING * 2;
        int listHeight = layout.breakdownHeight - 72;
        int viewportWidth = listWidth - SCROLLBAR_WIDTH - 6;

        context.fill(listX, listY, listX + listWidth, listY + listHeight, 0x22131B27);
        context.drawBorder(listX, listY, listWidth, listHeight, 0x663A5368);

        context.enableScissor(listX, listY, listX + viewportWidth, listY + listHeight);
        int drawY = listY + 6 - this.breakdownScrollOffset;
        if (this.filteredEntries.isEmpty())
        {
            context.drawText(this.textRenderer, Text.literal("No matching blocks found."), listX + 8, drawY + 4, COLOR_MUTED, false);
        }
        else
        {
            for (BlockBreakdownEntry entry : this.filteredEntries)
            {
                if (drawY + BREAKDOWN_ROW_HEIGHT >= listY && drawY <= listY + listHeight)
                {
                    drawBreakdownRow(context, listX + 6, drawY, viewportWidth - 12, entry);
                }
                drawY += BREAKDOWN_ROW_HEIGHT;
            }
        }
        context.disableScissor();
        drawScrollbar(context, listX + listWidth - SCROLLBAR_WIDTH, listY, listHeight, mouseX, mouseY);
    }

    private void drawBreakdownRow(DrawContext context, int x, int y, int width, BlockBreakdownEntry entry)
    {
        int rowColor = ((y / BREAKDOWN_ROW_HEIGHT) & 1) == 0 ? 0x141B2431 : 0x0D151F2A;
        context.fill(x - 2, y - 1, x + width, y + BREAKDOWN_ROW_HEIGHT - 2, rowColor);
        context.drawItem(entry.icon(), x, y + 1);

        String countText = UiFormat.formatCompact(entry.count());
        int countWidth = this.textRenderer.getWidth(countText);
        int countX = x + width - countWidth - 4;
        int nameWidth = Math.max(40, countX - (x + 24) - 8);
        String blockName = truncateToWidth(this.textRenderer, entry.name(), nameWidth);

        context.drawText(this.textRenderer, Text.literal(blockName), x + 22, y + 6, COLOR_VALUE, false);
        context.drawText(this.textRenderer, Text.literal(countText), countX, y + 6, COLOR_ACCENT, false);
    }

    private void drawMiningRateGraph(DrawContext context, int x, int y, int width, int height, float animation)
    {
        List<Double> rates = buildGraphRates();
        context.fill(x, y, x + width, y + height, 0x1C0B121C);

        for (int i = 0; i < 4; i++)
        {
            int lineY = y + (height * i) / 4;
            context.fill(x, lineY, x + width, lineY + 1, COLOR_GRAPH_GRID);
        }

        if (rates.isEmpty())
        {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Mine blocks to build a pace graph"), x + width / 2, y + height / 2 - 4, COLOR_MUTED);
            return;
        }

        int columns = Math.max(1, Math.min(width / 4, rates.size()));
        double maxRate = 0.0D;
        double[] columnRates = new double[columns];
        for (int column = 0; column < columns; column++)
        {
            int startIndex = (int) Math.floor((column * rates.size()) / (double) columns);
            int endIndex = (int) Math.floor(((column + 1) * rates.size()) / (double) columns);
            if (endIndex <= startIndex)
            {
                endIndex = Math.min(rates.size(), startIndex + 1);
            }

            double total = 0.0D;
            int count = 0;
            for (int i = startIndex; i < endIndex; i++)
            {
                total += rates.get(i);
                count++;
            }

            double rate = count <= 0 ? 0.0D : total / count;
            columnRates[column] = rate;
            maxRate = Math.max(maxRate, rate);
        }

        maxRate = Math.max(60.0D, maxRate);
        int chartBottom = y + height - 14;
        int usableHeight = Math.max(16, height - 22);
        int revealColumns = Math.max(1, (int) Math.ceil(columns * animation));
        float[] pointsY = new float[columns];
        int[] pointsX = new int[columns];
        for (int column = 0; column < columns; column++)
        {
            float normalized = (float) (columnRates[column] / maxRate);
            pointsY[column] = chartBottom - normalized * usableHeight;
            pointsX[column] = x + Math.round((column / (float) Math.max(1, columns - 1)) * (width - 1));
        }

        int fillBaseY = chartBottom;
        for (int column = 0; column < revealColumns; column++)
        {
            int pointX = pointsX[column];
            int pointY = Math.round(pointsY[column]);
            context.fill(pointX, pointY, pointX + 2, fillBaseY, 0x1426D6FF);
        }

        for (int column = 0; column < revealColumns - 1; column++)
        {
            float previousY = column > 0 ? pointsY[column - 1] : pointsY[column];
            float nextY = column + 2 < columns ? pointsY[column + 2] : pointsY[column + 1];
            drawCurveSegment(context, pointsX[column], pointsY[column], pointsX[column + 1], pointsY[column + 1], previousY, nextY);
        }

        int lastVisibleIndex = revealColumns - 1;
        if (lastVisibleIndex >= 0)
        {
            int markerX = pointsX[lastVisibleIndex];
            int markerY = Math.round(pointsY[lastVisibleIndex]);
            context.fill(markerX - 2, markerY - 2, markerX + 3, markerY + 3, COLOR_VALUE);
            context.fill(markerX - 1, markerY - 1, markerX + 2, markerY + 2, COLOR_ACCENT);
        }

        String maxLabel = UiFormat.formatCompact(Math.round(maxRate)) + "/hr";
        context.drawText(this.textRenderer, Text.literal(maxLabel), x + 4, y + 4, COLOR_LABEL, false);
        context.drawText(this.textRenderer, Text.literal("0"), x + 4, chartBottom - 8, COLOR_MUTED, false);

        String startLabel = "0m";
        String endLabel = formatGraphTimeLabel(Math.max(1, rates.size()) * 60L);
        int endWidth = this.textRenderer.getWidth(endLabel);
        context.drawText(this.textRenderer, Text.literal(startLabel), x, y + height - 10, COLOR_MUTED, false);
        context.drawText(this.textRenderer, Text.literal(endLabel), x + width - endWidth, y + height - 10, COLOR_MUTED, false);
    }

    private void drawStatCard(DrawContext context, int x, int y, int width, int height, String label, String value, String suffix)
    {
        fillRoundedCard(context, x, y, width, height, COLOR_CARD_SOFT, 0x663C556C);
        context.drawText(this.textRenderer, Text.literal(label), x + CARD_PADDING, y + 9, COLOR_LABEL, false);
        context.drawText(this.textRenderer, Text.literal(value), x + CARD_PADDING, y + 24, COLOR_VALUE, false);
        if (suffix.isBlank() == false)
        {
            context.drawText(this.textRenderer, Text.literal(suffix), x + CARD_PADDING, y + 38, COLOR_MUTED, false);
        }
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
        context.fill(x, y, x + SCROLLBAR_WIDTH, y + height, 0x33203042);
        context.drawBorder(x, y, SCROLLBAR_WIDTH, height, 0x66506A7F);
        int thumbColor = this.draggingScrollbar ? 0xFFD8FCFF : isOverScrollbar(mouseX, mouseY) ? 0xFFACF3FF : 0xFF7BDCEA;
        context.fill(x + 1, thumbY, x + SCROLLBAR_WIDTH - 1, thumbY + thumbHeight, thumbColor);
    }

    private void drawPill(DrawContext context, int x, int y, int width, int height, String text, int fillColor, int borderColor)
    {
        fillRoundedCard(context, x, y, width, height, fillColor, borderColor);
        int textX = x + (width - this.textRenderer.getWidth(text)) / 2;
        context.drawText(this.textRenderer, Text.literal(text), textX, y + 4, COLOR_VALUE, false);
    }

    private void fillRoundedCard(DrawContext context, int x, int y, int width, int height, int fillColor, int borderColor)
    {
        context.fill(x, y, x + width, y + height, fillColor);
        context.fill(x + 1, y + 1, x + width - 1, y + 2, COLOR_ACCENT_SOFT);
        context.drawBorder(x, y, width, height, borderColor);
    }

    private void buildBreakdownEntries()
    {
        this.allEntries.clear();
        for (Map.Entry<String, Long> entry : MiningStats.getSortedBreakdown(this.session).entrySet())
        {
            String blockId = entry.getKey();
            this.allEntries.add(new BlockBreakdownEntry(blockId, getCachedBlockName(blockId), entry.getValue(), getCachedIcon(blockId)));
        }
        this.allEntries.sort(Comparator.comparingLong(BlockBreakdownEntry::count).reversed().thenComparing(BlockBreakdownEntry::name));
    }

    private void refreshFilteredEntries()
    {
        this.filteredEntries.clear();
        String query = this.searchField == null ? "" : this.searchField.getText().trim().toLowerCase(Locale.ROOT);
        for (BlockBreakdownEntry entry : this.allEntries)
        {
            if (query.isBlank() || entry.searchName().contains(query))
            {
                this.filteredEntries.add(entry);
            }
        }
        setBreakdownScrollOffset(0);
    }

    private void updateDynamicWidgetBounds(Layout layout)
    {
        if (this.searchField != null)
        {
            this.searchField.setX(layout.breakdownX + CARD_PADDING);
            this.searchField.setY(layout.breakdownY + 32);
            this.searchField.setWidth(layout.breakdownWidth - CARD_PADDING * 2);
        }
    }

    private void drawCurveSegment(DrawContext context, float startX, float startY, float endX, float endY, float previousY, float nextY)
    {
        int steps = Math.max(6, Math.round(Math.abs(endX - startX) * 1.5F));
        for (int step = 0; step <= steps; step++)
        {
            float t = step / (float) steps;
            float pointX = MathHelper.lerp(t, startX, endX);
            float tangentStart = (endY - previousY) * 0.5F;
            float tangentEnd = (nextY - startY) * 0.5F;
            float t2 = t * t;
            float t3 = t2 * t;
            float pointY =
                    (2.0F * t3 - 3.0F * t2 + 1.0F) * startY +
                    (t3 - 2.0F * t2 + t) * tangentStart +
                    (-2.0F * t3 + 3.0F * t2) * endY +
                    (t3 - t2) * tangentEnd;
            int ix = Math.round(pointX);
            int iy = Math.round(pointY);
            context.fill(ix, iy, ix + 2, iy + 2, COLOR_ACCENT);
            context.fill(ix, iy + 2, ix + 2, iy + 3, COLOR_GRAPH_FILL);
        }
    }

    private List<Double> buildGraphRates()
    {
        List<Double> rates = new ArrayList<>();
        for (int bucket : this.session.miningRateBuckets)
        {
            rates.add(bucket * 60.0D);
        }

        if (isViewingLiveSession() == false)
        {
            return rates;
        }

        long activeMs = Math.max(0L, this.session.getDurationMs());
        long partialMs = activeMs % 60_000L;
        if (partialMs <= 0L)
        {
            return rates;
        }

        int currentBucketIndex = (int) (activeMs / 60_000L);
        while (rates.size() <= currentBucketIndex)
        {
            rates.add(0.0D);
        }

        int bucketBlocks = currentBucketIndex < this.session.miningRateBuckets.size() ? this.session.miningRateBuckets.get(currentBucketIndex) : 0;
        double partialRate = bucketBlocks * (3_600_000.0D / Math.max(1L, partialMs));
        rates.set(currentBucketIndex, Math.min(72_000.0D, partialRate));
        return rates;
    }

    private boolean isViewingLiveSession()
    {
        return this.session == MiningStats.getCurrentSession() && MiningStats.isSessionActive();
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

    private Layout computeLayout()
    {
        int panelWidth = Math.min(760, Math.max(560, this.width - PANEL_MARGIN * 2));
        int panelHeight = Math.min(540, Math.max(420, this.height - 28));
        int panelX = (this.width - panelWidth) / 2;
        int panelY = (this.height - panelHeight) / 2;
        int contentX = panelX + PANEL_PADDING;
        int contentWidth = panelWidth - PANEL_PADDING * 2;
        int headerY = panelY + PANEL_PADDING;
        int statY = headerY + 58;
        int cardWidth = (contentWidth - CARD_GAP * 3) / 4;
        int graphY = statY + 54 + CARD_GAP;
        int goalWidth = Math.min(190, Math.max(172, contentWidth / 4));
        int graphWidth = contentWidth - goalWidth - CARD_GAP;
        int graphHeight = 140;
        int breakdownY = graphY + graphHeight + CARD_GAP;
        int breakdownHeight = panelY + panelHeight - PANEL_PADDING - breakdownY;

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
                statY,
                cardWidth,
                contentX,
                graphY,
                graphWidth,
                graphHeight,
                contentX + graphWidth + CARD_GAP,
                graphY,
                goalWidth,
                graphHeight,
                contentX,
                breakdownY,
                contentWidth,
                breakdownHeight);
    }

    private boolean isMouseInsideBreakdown(double mouseX, double mouseY)
    {
        Layout layout = computeLayout();
        int listX = layout.breakdownX + CARD_PADDING;
        int listY = layout.breakdownY + 60;
        int listWidth = layout.breakdownWidth - CARD_PADDING * 2;
        int listHeight = layout.breakdownHeight - 72;
        return mouseX >= listX && mouseX <= listX + listWidth && mouseY >= listY && mouseY <= listY + listHeight;
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
        int totalContentHeight = this.filteredEntries.size() * BREAKDOWN_ROW_HEIGHT + 12;
        return Math.max(0, totalContentHeight - getBreakdownListHeight());
    }

    private int getBreakdownListHeight()
    {
        Layout layout = computeLayout();
        return layout.breakdownHeight - 72;
    }

    private int[] getScrollbarMetrics()
    {
        Layout layout = computeLayout();
        int listX = layout.breakdownX + CARD_PADDING;
        int listY = layout.breakdownY + 60;
        int listWidth = layout.breakdownWidth - CARD_PADDING * 2;
        return new int[] { listX + listWidth - SCROLLBAR_WIDTH, listY, getBreakdownListHeight() };
    }

    private int getScrollbarThumbHeight(int trackHeight)
    {
        int totalContentHeight = this.filteredEntries.size() * BREAKDOWN_ROW_HEIGHT + 12;
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
        return (int) Math.round((this.breakdownScrollOffset / (double) maxScroll) * (trackHeight - thumbHeight));
    }

    private String buildShareText()
    {
        StringBuilder builder = new StringBuilder();
        builder.append(this.heading).append('\n');
        builder.append("World/Server: ").append(this.worldName).append('\n');
        builder.append("Total Mined: ").append(UiFormat.formatBlocks(this.session.totalBlocks)).append('\n');
        builder.append("Session Time: ").append(formatClock(this.session.getDurationMs())).append('\n');
        builder.append("Average Rate: ").append(UiFormat.formatBlocksPerHour(this.session.getAverageBlocksPerHour())).append('\n');
        builder.append("Peak Rate: ").append(UiFormat.formatBlocksPerHour(this.session.peakBlocksPerHour)).append('\n');
        builder.append("Best Streak: ").append(this.session.bestStreakSeconds).append("s\n");

        MiningStats.GoalProgress dailyGoal = MiningStats.getDailyGoalProgress();
        if (dailyGoal.enabled())
        {
            builder.append(dailyGoal.label()).append(": ").append(UiFormat.formatProgress(dailyGoal.current(), dailyGoal.target())).append(" (").append(dailyGoal.getPercent()).append("%)\n");
        }

        for (BlockBreakdownEntry entry : this.allEntries)
        {
            builder.append(entry.name()).append(": ").append(entry.count()).append('\n');
        }
        return builder.toString().trim();
    }

    private String formatClock(long durationMs)
    {
        long totalSeconds = Math.max(0L, durationMs / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    private String formatGraphTimeLabel(long totalSeconds)
    {
        long minutes = totalSeconds / 60L;
        if (minutes < 60L)
        {
            return minutes + "m";
        }

        long hours = minutes / 60L;
        long remainingMinutes = minutes % 60L;
        if (remainingMinutes == 0L)
        {
            return hours + "h";
        }

        return hours + "h " + remainingMinutes + "m";
    }

    private String truncateToWidth(TextRenderer renderer, String value, int maxWidth)
    {
        if (renderer.getWidth(value) <= maxWidth)
        {
            return value;
        }

        String ellipsis = "...";
        String trimmed = value;
        while (trimmed.length() > 1 && renderer.getWidth(trimmed + ellipsis) > maxWidth)
        {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        return trimmed + ellipsis;
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
            return this.name.toLowerCase(Locale.ROOT);
        }
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
            int statY,
            int statCardWidth,
            int graphX,
            int graphY,
            int graphWidth,
            int graphHeight,
            int goalX,
            int goalY,
            int goalWidth,
            int goalHeight,
            int breakdownX,
            int breakdownY,
            int breakdownWidth,
            int breakdownHeight)
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
                    this.statY + delta,
                    this.statCardWidth,
                    this.graphX,
                    this.graphY + delta,
                    this.graphWidth,
                    this.graphHeight,
                    this.goalX,
                    this.goalY + delta,
                    this.goalWidth,
                    this.goalHeight,
                    this.breakdownX,
                    this.breakdownY + delta,
                    this.breakdownWidth,
                    this.breakdownHeight);
        }
    }
}
