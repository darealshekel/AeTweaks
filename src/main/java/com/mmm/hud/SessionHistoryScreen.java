package com.mmm.hud;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.mmm.config.Configs;
import com.mmm.storage.SessionData;
import com.mmm.storage.SessionHistory;
import com.mmm.storage.WorldSessionContext;
import com.mmm.ui.MmmUi;
import com.mmm.util.UiFormat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

public class SessionHistoryScreen extends Screen
{
    private static final int M = 20;
    private static final int P = 18;
    private static final int C = 12;
    private static final int G = 10;
    private static final int BH = 20;
    private static final int RH = 34;
    private static final int TH = 22;
    private static final int SBW = 8;
    private static final int SBM = 18;
    private static final int PACE_CARD_HEIGHT = 132;
    private static final int PACE_CHART_HEIGHT = 88;
    private static final int PACE_AXIS_LEFT_MIN = 30;
    private static final int PACE_AXIS_LEFT_MAX = 42;
    private static final int PACE_AXIS_TOP = 4;
    private static final int PACE_AXIS_RIGHT = 4;
    private static final int PACE_AXIS_BOTTOM = 13;
    private static final int BREAKDOWN_HEIGHT = 108;
    private static final int PANEL = MmmUi.PANEL;
    private static final int CARD = MmmUi.CARD;
    private static final int SOFT = MmmUi.CARD_SOFT;
    private static final int INSET = MmmUi.INSET;
    private static final int BORDER = MmmUi.BORDER;
    private static final int BORDER_SOFT = MmmUi.BORDER_SOFT;
    private static final int ACCENT = MmmUi.ACCENT;
    private static final int TEXT = MmmUi.TEXT;
    private static final int LABEL = MmmUi.LABEL;
    private static final int MUTED = MmmUi.MUTED;
    private static final int INACTIVE = MmmUi.INACTIVE;
    private static final int ROW_SEL = MmmUi.ROW_SELECTED;
    private static final int ROW_HOVER = MmmUi.ROW_HOVER;
    private static final int ROW_ALT = MmmUi.ROW_ALT;
    private static final int GRAPH_FILL = MmmUi.GRAPH_FILL;
    private static final int GRAPH_GRID = MmmUi.GRAPH_GRID;
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("dd/MM/yy  HH:mm");

    private final Screen parent;
    private final List<SessionHistory.WorldHistory> worlds;
    private List<SessionData> sessions = List.of();
    private String worldName = "";
    private int selectedWorldIndex;
    private int selectedIndex = -1;
    private int listScroll;
    private int detailScroll;
    private int breakdownScroll;
    private boolean draggingList;
    private boolean draggingDetail;
    private boolean draggingBreakdown;
    private long openedAtMs;
    private int breakdownListX;
    private int breakdownListY;
    private int breakdownListHeight;
    private int breakdownViewportWidth;
    private int breakdownScrollbarX;
    private int breakdownEntryCount;

    public SessionHistoryScreen(Screen parent)
    {
        super(Text.literal("Session History"));
        this.parent = parent;
        this.worlds = SessionHistory.getWorldHistories();
        applyWorldSelection(resolveInitialWorldIndex(), false);
    }

    @Override
    protected void init()
    {
        this.clearChildren();
        this.openedAtMs = System.currentTimeMillis();
        ensureCursorVisible();
        Layout l = layout();
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> close()).dimensions(l.panelRight - 74, l.headerY - 2, 64, BH).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta)
    {
        ensureCursorVisible();
        Layout l = layout();
        float anim = openAnim();
        int panelY = l.panelY + Math.round((1.0F - anim) * 14.0F);
        l = l.move(panelY - l.panelY);
        MmmUi.backdrop(context, this.width, this.height);
        MmmUi.drawMmmScreensSidebar(context, this.textRenderer, this.width, this.height, mouseX, mouseY, "HISTORY");
        card(context, l.panelX, l.panelY, l.panelWidth, l.panelHeight, PANEL, BORDER);
        context.drawText(this.textRenderer, Text.literal("Session History"), l.contentX, l.headerY, TEXT, true);
        pill(context, l.contentX, l.headerY + 18, Math.min(220, l.contentWidth / 2), 16, this.worldName);
        context.drawText(this.textRenderer, Text.literal("Review previous runs with the same MMM website visual system."), l.contentX + 2, l.headerY + 40, LABEL, false);
        drawWorldTabs(context, l, mouseX, mouseY);
        drawList(context, l, mouseX, mouseY);
        drawDetail(context, l, mouseX, mouseY);
        super.render(context, mouseX, mouseY, delta);
        MmmUi.drawMmmTopBar(context, this.textRenderer, this.width);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount)
    {
        if (isInBreakdown(mouseX, mouseY))
        {
            setBreakdownScroll(this.breakdownScroll + (verticalAmount < 0 ? 16 : -16));
            return true;
        }
        if (isInDetail(mouseX, mouseY))
        {
            setDetailScroll(this.detailScroll + (verticalAmount < 0 ? 24 : -24));
            return true;
        }
        if (isInList(mouseX, mouseY))
        {
            setListScroll(this.listScroll + (verticalAmount < 0 ? 1 : -1));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button)
    {
        if (button == 0 && MmmUi.handleMmmScreensSidebarClick(this, this.parent, mouseX, mouseY, "HISTORY"))
        {
            return true;
        }

        int worldTab = getHoveredWorldTab(mouseX, mouseY);
        if (button == 0 && worldTab >= 0)
        {
            applyWorldSelection(worldTab, true);
            return true;
        }

        if (button == 0 && isOverBreakdownBar(mouseX, mouseY))
        {
            this.draggingBreakdown = true;
            dragBreakdown(mouseY);
            return true;
        }
        if (button == 0 && isOverDetailBar(mouseX, mouseY))
        {
            this.draggingDetail = true;
            dragDetail(mouseY);
            return true;
        }
        if (button == 0 && isOverListBar(mouseX, mouseY))
        {
            this.draggingList = true;
            dragList(mouseY);
            return true;
        }
        Layout l = layout();
        int listX = l.contentX + C;
        int listY = l.contentY + 44;
        int listWidth = l.leftWidth - C * 2 - SBW - 6;
        int drawY = listY + 6;
        int visibleRows = getVisibleRows(l);
        for (int row = 0; row < visibleRows; row++)
        {
            int index = this.listScroll + row;
            if (index >= this.sessions.size()) break;
            int rowY = drawY + row * RH;
            if (mouseX >= listX && mouseX <= listX + listWidth && mouseY >= rowY && mouseY <= rowY + RH - 4)
            {
                select(index);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY){ if(draggingBreakdown){dragBreakdown(mouseY); return true;} if(draggingDetail){dragDetail(mouseY); return true;} if(draggingList){dragList(mouseY); return true;} return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY); }
    @Override public boolean mouseReleased(double mouseX, double mouseY, int button){ if(button==0){draggingList=false; draggingDetail=false; draggingBreakdown=false;} return super.mouseReleased(mouseX, mouseY, button); }
    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers){ if(keyCode==256){close(); return true;} if(keyCode==264||keyCode==341){moveSelection(1); return true;} if(keyCode==265||keyCode==328){moveSelection(-1); return true;} return super.keyPressed(keyCode, scanCode, modifiers); }
    @Override public void close(){ MinecraftClient.getInstance().setScreen(this.parent); }
    @Override public boolean shouldPause(){ return false; }
    @Override public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta){}

    private void ensureCursorVisible(){ MinecraftClient client=MinecraftClient.getInstance(); if(client!=null&&client.mouse!=null) client.mouse.unlockCursor(); }

    private void drawWorldTabs(DrawContext context, Layout l, int mouseX, int mouseY)
    {
        if (this.worlds.isEmpty())
        {
            return;
        }

        int count = this.worlds.size();
        int gap = 4;
        int tabWidth = getWorldTabWidth(l, count, gap);
        int totalWidth = count * tabWidth + Math.max(0, count - 1) * gap;
        int x = l.contentX + Math.max(0, (l.contentWidth - totalWidth) / 2);
        int y = getWorldTabsY(l);

        for (int i = 0; i < count; i++)
        {
            SessionHistory.WorldHistory world = this.worlds.get(i);
            int tabX = x + i * (tabWidth + gap);
            boolean selected = i == this.selectedWorldIndex;
            boolean hovered = mouseX >= tabX && mouseX <= tabX + tabWidth && mouseY >= y && mouseY <= y + TH;
            int fill = selected ? MmmUi.ROW_SELECTED : hovered ? MmmUi.ROW_HOVER : MmmUi.INSET;
            int border = selected ? ACCENT : BORDER_SOFT;
            context.fill(tabX, y, tabX + tabWidth, y + TH, fill);
            context.drawBorder(tabX, y, tabWidth, TH, border);

            String label = truncate(world.displayName(), tabWidth - 14);
            int color = selected ? TEXT : LABEL;
            context.drawText(this.textRenderer, Text.literal(label), tabX + Math.max(5, (tabWidth - this.textRenderer.getWidth(label)) / 2), y + 7, color, false);
        }
    }

    private int getHoveredWorldTab(double mouseX, double mouseY)
    {
        if (this.worlds.isEmpty())
        {
            return -1;
        }

        Layout l = layout();
        int count = this.worlds.size();
        int gap = 4;
        int tabWidth = getWorldTabWidth(l, count, gap);
        int totalWidth = count * tabWidth + Math.max(0, count - 1) * gap;
        int x = l.contentX + Math.max(0, (l.contentWidth - totalWidth) / 2);
        int y = getWorldTabsY(l);
        if (mouseY < y || mouseY > y + TH)
        {
            return -1;
        }

        for (int i = 0; i < count; i++)
        {
            int tabX = x + i * (tabWidth + gap);
            if (mouseX >= tabX && mouseX <= tabX + tabWidth)
            {
                return i;
            }
        }
        return -1;
    }

    private int getWorldTabWidth(Layout l, int count, int gap)
    {
        return Math.max(48, Math.min(150, (l.contentWidth - Math.max(0, count - 1) * gap) / Math.max(1, count)));
    }

    private int getWorldTabsY(Layout l)
    {
        return l.headerY + 60;
    }

    private int resolveInitialWorldIndex()
    {
        String currentWorldId = SessionHistory.getCurrentWorldId();
        if (currentWorldId == null || currentWorldId.isBlank())
        {
            currentWorldId = WorldSessionContext.getCurrentWorldId();
        }

        for (int i = 0; i < this.worlds.size(); i++)
        {
            if (currentWorldId.equals(this.worlds.get(i).worldId()))
            {
                return i;
            }
        }
        return 0;
    }

    private void applyWorldSelection(int index, boolean playSound)
    {
        if (this.worlds.isEmpty())
        {
            this.sessions = newestFirst(SessionHistory.getHistory());
            this.worldName = WorldSessionContext.getCurrentWorldName();
        }
        else
        {
            this.selectedWorldIndex = Math.max(0, Math.min(this.worlds.size() - 1, index));
            SessionHistory.WorldHistory world = this.worlds.get(this.selectedWorldIndex);
            this.sessions = newestFirst(world.sessions());
            this.worldName = world.displayName();
        }

        this.selectedIndex = this.sessions.isEmpty() ? -1 : 0;
        this.listScroll = 0;
        this.detailScroll = 0;
        this.breakdownScroll = 0;
        if (playSound)
        {
            playClick();
        }
    }

    private static List<SessionData> newestFirst(List<SessionData> source)
    {
        List<SessionData> sorted = new ArrayList<>(source);
        sorted.sort(Comparator.comparingLong((SessionData session) -> session.startTimeMs).reversed());
        return sorted;
    }

    private void drawList(DrawContext context, Layout l, int mouseX, int mouseY)
    {
        card(context, l.contentX, l.contentY, l.leftWidth, l.contentHeight, SOFT, BORDER);
        MmmUi.drawTextWithin(context, this.textRenderer, "Runs", l.contentX + C, l.contentY + 10, l.leftWidth - C * 2, TEXT, false);
        int x = l.contentX + C;
        int y = l.contentY + 44;
        int width = l.leftWidth - C * 2;
        int height = l.contentHeight - 56;
        int viewportWidth = width - SBW - 6;
        context.fill(x, y, x + width, y + height, INSET);
        context.drawBorder(x, y, width, height, BORDER_SOFT);
        if (this.sessions.isEmpty()) { context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Mine some blocks and your history will show up here"), x + width / 2, y + height / 2 - 4, MUTED); return; }
        int visibleRows = getVisibleRows(l);
        this.listScroll = Math.max(0, Math.min(this.listScroll, Math.max(0, this.sessions.size() - visibleRows)));
        context.enableScissor(x, y, x + viewportWidth, y + height);
        int drawY = y + 6;
        for (int row = 0; row < visibleRows; row++)
        {
            int index = this.listScroll + row; if (index >= this.sessions.size()) break;
            SessionData session = this.sessions.get(index);
            int rowY = drawY + row * RH;
            boolean hovered = mouseX >= x && mouseX <= x + viewportWidth && mouseY >= rowY && mouseY <= rowY + RH - 4;
            int rowColor = index == this.selectedIndex ? ROW_SEL : hovered ? ROW_HOVER : ((row & 1) == 0 ? ROW_ALT : INSET);
            context.fill(x + 4, rowY, x + viewportWidth - 4, rowY + RH - 4, rowColor);
            MmmUi.drawTextWithin(context, this.textRenderer, "#" + (index + 1) + "  " + DATE_FMT.format(new Date(session.startTimeMs)), x + 12, rowY + 6, viewportWidth - 24, TEXT, false);
            MmmUi.drawTextWithin(context, this.textRenderer, UiFormat.formatCompact(session.totalBlocks) + " blocks  |  " + session.getDurationString() + "  |  " + UiFormat.formatCompact(session.getAverageBlocksPerHour()) + "/hr", x + 12, rowY + 19, viewportWidth - 24, MUTED, false);
        }
        context.disableScissor();
        drawListBar(context, x + width - SBW, y, height, mouseX, mouseY, visibleRows);
    }

    private void drawDetail(DrawContext context, Layout l, int mouseX, int mouseY)
    {
        card(context, l.detailX, l.contentY, l.detailWidth, l.contentHeight, CARD, BORDER);
        MmmUi.drawTextWithin(context, this.textRenderer, "Session Detail", l.detailX + C, l.contentY + 10, l.detailWidth - C * 2, TEXT, false);
        SessionData session = getSelected();
        if (session == null) { MmmUi.drawTextWithin(context, this.textRenderer, "Select a run to inspect it.", l.detailX + C, l.contentY + 34, l.detailWidth - C * 2, MUTED, false); return; }
        int vx = l.detailX + C;
        int vy = l.contentY + 28;
        int vw = l.detailWidth - C * 2 - SBW - 6;
        int vh = l.contentHeight - 40;
        int drawY = vy - this.detailScroll;
        context.enableScissor(vx, vy, vx + vw, vy + vh);
        MmmUi.drawTextWithin(context, this.textRenderer, DATE_FMT.format(new Date(session.startTimeMs)), vx, drawY, vw, MUTED, false);
        int cardWidth = (vw - G) / 2;
        int statY = drawY + 16;
        stat(context, vx, statY, cardWidth, 50, "Total Mined", UiFormat.formatCompact(session.totalBlocks), "blocks");
        stat(context, vx + cardWidth + G, statY, cardWidth, 50, "Active Time", formatClock(session.getDurationMs()), "session");
        stat(context, vx, statY + 56, cardWidth, 50, "Avg Rate", UiFormat.formatCompact(session.getAverageBlocksPerHour()), "blocks/hr");
        stat(context, vx + cardWidth + G, statY + 56, cardWidth, 50, "Peak Rate", UiFormat.formatCompact(session.getPeakBlocksPerHour()), "blocks/hr");
        int graphY = statY + 118;
        card(context, vx, graphY, vw, PACE_CARD_HEIGHT, SOFT, BORDER_SOFT);
        MmmUi.drawTextWithin(context, this.textRenderer, "Session Pace", vx + 10, graphY + 8, vw - 20, TEXT, false);
        MmmUi.drawTextWithin(context, this.textRenderer, "Blocks per hour across the session", vx + 10, graphY + 20, vw - 20, MUTED, false);
        String rateLabel = drawGraph(context, vx + 10, graphY + 34, vw - 20, PACE_CHART_HEIGHT, session, mouseX, mouseY);
        if (rateLabel != null)
        {
            MmmUi.drawTextRightWithin(context, this.textRenderer, rateLabel, vx + vw - 10, graphY + 8, Math.max(72, vw / 2), Configs.getHudNumberColor(), false);
        }
        int infoY = graphY + PACE_CARD_HEIGHT + 10;
        row(context, vx, vx + vw, infoY, "Best Streak", session.bestStreakSeconds + "s");
        row(context, vx, vx + vw, infoY + 16, "Top Block", getTopBlock(session));
        drawBreakdown(context, vx, infoY + 38, vw, session, mouseX, mouseY);
        context.disableScissor();
        drawDetailBar(context, l, mouseX, mouseY);
    }
    private void drawBreakdown(DrawContext context, int x, int y, int width, SessionData session, int mouseX, int mouseY)
    {
        context.drawText(this.textRenderer, Text.literal("Block Breakdown"), x, y, TEXT, false);
        int cardY = y + 14;
        card(context, x, cardY, width, BREAKDOWN_HEIGHT, SOFT, BORDER_SOFT);
        List<Map.Entry<String, Long>> entries = new ArrayList<>(session.blockBreakdown.entrySet());
        entries.sort((left, right) -> Long.compare(right.getValue(), left.getValue()));
        int listX = x + 8;
        int listY = cardY + 8;
        int listHeight = BREAKDOWN_HEIGHT - 16;
        int viewportWidth = width - 16 - SBW - 4;
        this.breakdownListX = listX;
        this.breakdownListY = listY;
        this.breakdownListHeight = listHeight;
        this.breakdownViewportWidth = viewportWidth;
        this.breakdownScrollbarX = x + width - 8;
        this.breakdownEntryCount = entries.size();
        if (entries.isEmpty()) { MmmUi.drawTextWithin(context, this.textRenderer, "No block breakdown recorded.", listX, listY + 2, viewportWidth, MUTED, false); return; }
        context.enableScissor(listX, listY, listX + viewportWidth, listY + listHeight);
        int rowY = listY - this.breakdownScroll;
        for (Map.Entry<String, Long> entry : entries)
        {
            if (rowY + 14 >= listY && rowY <= listY + listHeight)
            {
                String count = UiFormat.formatCompact(entry.getValue());
                int countWidth = this.textRenderer.getWidth(count);
                String name = truncate(resolveName(entry.getKey()), Math.max(40, viewportWidth - countWidth - 8));
                context.drawText(this.textRenderer, Text.literal(name), listX, rowY, LABEL, false);
                context.drawText(this.textRenderer, Text.literal(count), listX + viewportWidth - countWidth - 2, rowY, Configs.getHudNumberColor(), false);
            }
            rowY += 14;
        }
        context.disableScissor();
        if (getBreakdownMax(entries.size(), listHeight) > 0)
        {
            simpleBar(context, this.breakdownScrollbarX, listY, listHeight, getBreakdownThumb(listHeight, entries.size()), getBreakdownOffset(listHeight, entries.size()), this.draggingBreakdown || isOverBreakdownBar(mouseX, mouseY));
        }
    }

    private String drawGraph(DrawContext c, int x, int y, int w, int h, SessionData s, int mouseX, int mouseY)
    {
        c.fill(x, y, x + w, y + h, INSET);
        double axisMax = roundPaceCeiling(Math.max(60d, s.getPeakBlocksPerHour()));
        int axisLeft = getPaceAxisLeft(axisMax);
        int plotX = x + axisLeft;
        int plotY = y + PACE_AXIS_TOP;
        int plotW = Math.max(24, w - axisLeft - PACE_AXIS_RIGHT);
        int plotH = Math.max(18, h - PACE_AXIS_TOP - PACE_AXIS_BOTTOM);

        if (s.miningRateBuckets.isEmpty())
        {
            c.drawCenteredTextWithShadow(this.textRenderer, Text.literal("No graph data saved for this run"), plotX + plotW / 2, plotY + plotH / 2 - 4, MUTED);
            drawGraphAxes(c, plotX, plotY, plotW, plotH, axisLeft, axisMax, s);
            return null;
        }

        int cols = Math.max(1, Math.min(plotW / 4, s.miningRateBuckets.size()));
        double[] rates = new double[cols];
        double maxRate = Math.max(60d, s.getPeakBlocksPerHour());
        for (int col = 0; col < cols; col++)
        {
            int start = (int) Math.floor((col * s.miningRateBuckets.size()) / (double) cols);
            int end = (int) Math.floor(((col + 1) * s.miningRateBuckets.size()) / (double) cols);
            if (end <= start)
            {
                end = Math.min(s.miningRateBuckets.size(), start + 1);
            }

            double total = 0d;
            int count = 0;
            for (int i = start; i < end; i++)
            {
                total += s.getBucketBlocksPerHour(s.miningRateBuckets.get(i));
                count++;
            }

            rates[col] = count <= 0 ? 0d : total / count;
            maxRate = Math.max(maxRate, rates[col]);
        }
        axisMax = roundPaceCeiling(maxRate);

        for (int i = 0; i <= 4; i++)
        {
            int ly = plotY + (plotH * i) / 4;
            c.fill(plotX, ly, plotX + plotW, ly + 1, GRAPH_GRID);
        }

        int bottom = plotY + plotH - 1;
        int usable = Math.max(12, plotH - 1);
        float[] py = new float[cols];
        int[] px = new int[cols];
        for (int col = 0; col < cols; col++)
        {
            float n = (float) (rates[col] / axisMax);
            py[col] = bottom - n * usable;
            px[col] = plotX + Math.round((col / (float) Math.max(1, cols - 1)) * (plotW - 1));
        }

        for (int col = 0; col < cols - 1; col++)
        {
            float prev = col > 0 ? py[col - 1] : py[col];
            float next = col + 2 < cols ? py[col + 2] : py[col + 1];
            curve(c, px[col], py[col], px[col + 1], py[col + 1], prev, next);
        }

        int hoveredColumn = -1;
        if (mouseX >= plotX && mouseX <= plotX + plotW && mouseY >= plotY && mouseY <= plotY + plotH)
        {
            int bestDistance = Integer.MAX_VALUE;
            for (int col = 0; col < cols; col++)
            {
                int distance = Math.abs(mouseX - px[col]);
                if (distance < bestDistance)
                {
                    bestDistance = distance;
                    hoveredColumn = col;
                }
            }
        }

        if (hoveredColumn >= 0)
        {
            int markerX = px[hoveredColumn];
            int markerY = Math.round(py[hoveredColumn]);
            c.fill(markerX, plotY, markerX + 1, plotY + plotH, GRAPH_GRID);
            c.fill(markerX - 3, markerY - 3, markerX + 4, markerY + 4, TEXT);
            c.fill(markerX - 2, markerY - 2, markerX + 3, markerY + 3, ACCENT);
        }

        drawGraphAxes(c, plotX, plotY, plotW, plotH, axisLeft, axisMax, s);
        return (hoveredColumn >= 0
                ? UiFormat.formatCompact(Math.round(rates[hoveredColumn])) + "/hr @ " + formatGraphTimeLabel(timeForColumn(hoveredColumn, cols, s.getDurationMs()))
                : "Peak " + UiFormat.formatCompact(Math.round(maxRate)) + "/hr");
    }
    private void drawGraphAxes(DrawContext c, int plotX, int plotY, int plotW, int plotH, int axisLeft, double axisMax, SessionData s)
    {
        drawRateTick(c, plotX, plotY, plotH, axisLeft, 0, axisMax, LABEL);
        drawRateTick(c, plotX, plotY, plotH, axisLeft, 2, axisMax / 2.0D, MUTED);
        drawRateTick(c, plotX, plotY, plotH, axisLeft, 4, 0D, MUTED);

        int labelY = plotY + plotH + 3;
        String startLabel = "0m";
        String middleLabel = formatGraphTimeLabel(s.getDurationMs() / 2L);
        String endLabel = formatGraphTimeLabel(Math.max(1L, s.getDurationMs()));
        int startWidth = this.textRenderer.getWidth(startLabel);
        int middleWidth = this.textRenderer.getWidth(middleLabel);
        int endWidth = this.textRenderer.getWidth(endLabel);
        c.drawText(this.textRenderer, Text.literal(startLabel), plotX, labelY, MUTED, false);
        c.drawText(this.textRenderer, Text.literal(endLabel), plotX + plotW - endWidth, labelY, MUTED, false);

        int middleX = plotX + (plotW - middleWidth) / 2;
        int startRight = plotX + startWidth + 6;
        int endLeft = plotX + plotW - endWidth - 6;
        if (middleX >= startRight && middleX + middleWidth <= endLeft)
        {
            c.drawText(this.textRenderer, Text.literal(middleLabel), middleX, labelY, MUTED, false);
        }
    }
    private void drawRateTick(DrawContext c, int plotX, int plotY, int plotH, int axisLeft, int step, double value, int color)
    {
        String label = formatPaceTick(value);
        int labelY = Math.max(plotY, Math.min(plotY + plotH - 8, plotY + (plotH * step) / 4 - 4));
        MmmUi.drawTextRightWithin(c, this.textRenderer, label, plotX - 4, labelY, axisLeft - 7, color, false);
    }
    private int getPaceAxisLeft(double axisMax)
    {
        int labelWidth = Math.max(this.textRenderer.getWidth(formatPaceTick(axisMax)), this.textRenderer.getWidth("0"));
        return Math.max(PACE_AXIS_LEFT_MIN, Math.min(PACE_AXIS_LEFT_MAX, labelWidth + 8));
    }
    private String formatPaceTick(double value)
    {
        long rounded = Math.max(0L, Math.round(value));
        if (rounded >= 10_000L)
        {
            return Math.round(rounded / 1_000.0D) + "k";
        }
        if (rounded >= 1_000L)
        {
            long tenths = Math.round(rounded / 100.0D);
            long whole = tenths / 10L;
            long fraction = tenths % 10L;
            return fraction == 0L ? whole + "k" : whole + "." + fraction + "k";
        }
        return Long.toString(rounded);
    }
    private double roundPaceCeiling(double value)
    {
        double target = Math.max(1D, value / 4.0D);
        double magnitude = Math.pow(10D, Math.floor(Math.log10(target)));
        double normalized = target / magnitude;
        double step;
        if (normalized <= 1D)
        {
            step = 1D;
        }
        else if (normalized <= 2D)
        {
            step = 2D;
        }
        else if (normalized <= 5D)
        {
            step = 5D;
        }
        else
        {
            step = 10D;
        }

        return step * magnitude * 4.0D;
    }
    private long timeForColumn(int column, int columns, long durationMs)
    {
        if (columns <= 1)
        {
            return 0L;
        }

        return Math.round((column / (double) (columns - 1)) * Math.max(0L, durationMs));
    }
    private void row(DrawContext c,int lx,int rx,int y,String label,String value){ int width=rx-lx; int valueWidth=Math.min(this.textRenderer.getWidth(value), Math.max(32, width/2)); MmmUi.drawTextWithin(c,this.textRenderer,label,lx,y,Math.max(0,width-valueWidth-8),LABEL,false); MmmUi.drawTextRightWithin(c,this.textRenderer,value,rx,y,valueWidth,TEXT,false); }
    private void stat(DrawContext c,int x,int y,int w,int h,String l,String v,String s){ int tw=w-C*2; card(c,x,y,w,h,SOFT,BORDER_SOFT); MmmUi.drawTextWithin(c,this.textRenderer,l,x+C,y+9,tw,LABEL,false); int valueColor=inactiveValueColor(v)==INACTIVE?INACTIVE:Configs.getHudNumberColor(); MmmUi.drawTextWithin(c,this.textRenderer,v,x+C,y+24,tw,valueColor,false); MmmUi.drawTextWithin(c,this.textRenderer,s,x+C,y+38,tw,MUTED,false); }
    private void pill(DrawContext c,int x,int y,int w,int h,String t){ String clipped=MmmUi.truncate(this.textRenderer,t,w-8); card(c,x,y,w,h,CARD,ACCENT); c.drawText(this.textRenderer, Text.literal(clipped), x+Math.max(4,(w-this.textRenderer.getWidth(clipped))/2), y+4, ACCENT, false); }
    private void card(DrawContext c,int x,int y,int w,int h,int fill,int border){ MmmUi.card(c,x,y,w,h,fill,border); }
    private void drawListBar(DrawContext c,int x,int y,int h,int mx,int my,int vis){ int max=Math.max(0,this.sessions.size()-vis); if(max<=0) return; int th=getListThumb(h,vis), ty=y+getListOffset(h,th,max); c.fill(x,y,x+SBW,y+h,MmmUi.SCROLLBAR_TRACK); c.drawBorder(x,y,SBW,h,BORDER_SOFT); int col=this.draggingList?MmmUi.SCROLLBAR_THUMB_ACTIVE:isOverListBar(mx,my)?MmmUi.SCROLLBAR_THUMB_HOVER:MmmUi.SCROLLBAR_THUMB; c.fill(x+1,ty,x+SBW-1,ty+th,col); }
    private void drawDetailBar(DrawContext c,Layout l,int mx,int my){ int max=Math.max(0,getDetailContentHeight()-(l.contentHeight-40)); if(max<=0) return; int x=l.detailX+l.detailWidth-C-SBW,y=l.contentY+28,h=l.contentHeight-40,th=Math.max(SBM,Math.min(h,(int)Math.round((h/(double)getDetailContentHeight())*h))), off=(int)Math.round((this.detailScroll/(double)max)*(h-th)); simpleBar(c,x,y,h,th,off,this.draggingDetail||isOverDetailBar(mx,my)); }
    private void simpleBar(DrawContext c,int x,int y,int h,int th,int off,boolean active){ c.fill(x,y,x+SBW,y+h,MmmUi.SCROLLBAR_TRACK); c.drawBorder(x,y,SBW,h,BORDER_SOFT); c.fill(x+1,y+off,x+SBW-1,y+off+th,active?MmmUi.SCROLLBAR_THUMB_ACTIVE:MmmUi.SCROLLBAR_THUMB); }
    private int getVisibleRows(Layout l){ return Math.max(1, (l.contentHeight - 68) / RH); }
    private boolean isInList(double mx,double my){ Layout l=layout(); return mx>=l.contentX+C&&mx<=l.contentX+l.leftWidth-C&&my>=l.contentY+44&&my<=l.contentY+l.contentHeight-12; }
    private boolean isInDetail(double mx,double my){ Layout l=layout(); return mx>=l.detailX+C&&mx<=l.detailX+l.detailWidth-C&&my>=l.contentY+28&&my<=l.contentY+l.contentHeight-12; }
    private boolean isOverListBar(double mx,double my){ Layout l=layout(); int x=l.contentX+l.leftWidth-C-SBW,y=l.contentY+44,h=l.contentHeight-56; return mx>=x&&mx<=x+SBW&&my>=y&&my<=y+h; }
    private boolean isOverDetailBar(double mx,double my){ Layout l=layout(); int x=l.detailX+l.detailWidth-C-SBW,y=l.contentY+28,h=l.contentHeight-40; return mx>=x&&mx<=x+SBW&&my>=y&&my<=y+h; }
    private BreakdownMetrics metrics(){ return new BreakdownMetrics(this.breakdownListX,this.breakdownListY,this.breakdownListHeight,this.breakdownViewportWidth,this.breakdownScrollbarX); }
    private boolean isInBreakdown(double mx,double my){ BreakdownMetrics m=metrics(); return m.listHeight > 0 && mx>=m.listX&&mx<=m.listX+m.viewportWidth&&my>=m.listY&&my<=m.listY+m.listHeight; }
    private boolean isOverBreakdownBar(double mx,double my){ BreakdownMetrics m=metrics(); return m.listHeight > 0 && mx>=m.scrollbarX&&mx<=m.scrollbarX+SBW&&my>=m.listY&&my<=m.listY+m.listHeight; }
    private void dragList(double mouseY){ Layout l=layout(); int vis=getVisibleRows(l), max=Math.max(0,this.sessions.size()-vis); if(max<=0){this.listScroll=0; return;} int y=l.contentY+44,h=l.contentHeight-56,th=getListThumb(h,vis), travel=Math.max(1,h-th); double n=Math.max(0.0D, Math.min(1.0D, ((mouseY-th/2.0D)-y)/travel)); setListScroll((int)Math.round(n*max)); }
    private void dragDetail(double mouseY){ Layout l=layout(); int max=Math.max(0,getDetailContentHeight()-(l.contentHeight-40)); if(max<=0){this.detailScroll=0; return;} int y=l.contentY+28,h=l.contentHeight-40,th=Math.max(SBM,Math.min(h,(int)Math.round((h/(double)getDetailContentHeight())*h))), travel=Math.max(1,h-th); double n=Math.max(0.0D, Math.min(1.0D, ((mouseY-th/2.0D)-y)/travel)); setDetailScroll((int)Math.round(n*max)); }
    private void dragBreakdown(double mouseY){ BreakdownMetrics m=metrics(); int count=this.breakdownEntryCount, max=getBreakdownMax(count,m.listHeight); if(max<=0){this.breakdownScroll=0; return;} int th=getBreakdownThumb(m.listHeight,count), travel=Math.max(1,m.listHeight-th); double n=Math.max(0.0D, Math.min(1.0D, ((mouseY-th/2.0D)-m.listY)/travel)); setBreakdownScroll((int)Math.round(n*max)); }
    private int getListThumb(int h,int vis){ if(this.sessions.isEmpty()) return h; int th=(int)Math.round((vis/(double)this.sessions.size())*h); return Math.max(SBM, Math.min(h, th)); }
    private int getListOffset(int h,int th,int max){ return max<=0?0:(int)Math.round((this.listScroll/(double)max)*(h-th)); }
    private int getDetailContentHeight(){ return 578; }
    private int getBreakdownMax(int count,int h){ return Math.max(0, count*14-h); }
    private int getBreakdownThumb(int h,int count){ int ch=Math.max(1, count*14); return Math.max(SBM, Math.min(h, (int)Math.round((h/(double)ch)*h))); }
    private int getBreakdownOffset(int h,int count){ int max=getBreakdownMax(count,h), th=getBreakdownThumb(h,count); return max<=0?0:(int)Math.round((this.breakdownScroll/(double)max)*(h-th)); }
    private void moveSelection(int delta){ if(this.sessions.isEmpty()) return; int prev=this.selectedIndex; this.selectedIndex=Math.max(0, Math.min(this.sessions.size()-1, this.selectedIndex+delta)); Layout l=layout(); int vis=getVisibleRows(l); if(this.selectedIndex<this.listScroll) this.listScroll=this.selectedIndex; else if(this.selectedIndex>=this.listScroll+vis) this.listScroll=this.selectedIndex-vis+1; if(this.selectedIndex!=prev){ this.detailScroll=0; this.breakdownScroll=0; playClick(); } }
    private void setListScroll(int offset){ Layout l=layout(); int max=Math.max(0,this.sessions.size()-getVisibleRows(l)); this.listScroll=Math.max(0,Math.min(max,offset)); }
    private void setDetailScroll(int offset){ Layout l=layout(); int max=Math.max(0,getDetailContentHeight()-(l.contentHeight-40)); this.detailScroll=Math.max(0,Math.min(max,offset)); }
    private void setBreakdownScroll(int offset){ int count=this.breakdownEntryCount; this.breakdownScroll=Math.max(0,Math.min(getBreakdownMax(count,Math.max(0,this.breakdownListHeight)),offset)); }
    private float openAnim(){ if(this.openedAtMs<=0L) return 1.0F; long elapsed=System.currentTimeMillis()-this.openedAtMs; float n=MathHelper.clamp(elapsed/280.0F,0.0F,1.0F); return n*n*(3.0F-2.0F*n); }
    private void select(int index){ if(index<0||index>=this.sessions.size()) return; if(this.selectedIndex!=index){ this.selectedIndex=index; this.detailScroll=0; this.breakdownScroll=0; playClick(); } }
    private void playClick(){ MinecraftClient client=MinecraftClient.getInstance(); if(client!=null&&client.getSoundManager()!=null) client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK,1.0F)); }
    private void curve(DrawContext c,float sx,float sy,float ex,float ey,float py,float ny){ int steps=Math.max(6,Math.round(Math.abs(ex-sx)*1.5F)); for(int s=0;s<=steps;s++){ float t=s/(float)steps, px=MathHelper.lerp(t,sx,ex), ts=(ey-py)*0.5F, te=(ny-sy)*0.5F, t2=t*t, t3=t2*t; float yy=(2.0F*t3-3.0F*t2+1.0F)*sy+(t3-2.0F*t2+t)*ts+(-2.0F*t3+3.0F*t2)*ey+(t3-t2)*te; int ix=Math.round(px), iy=Math.round(yy); c.fill(ix,iy,ix+2,iy+2,ACCENT); c.fill(ix,iy+2,ix+2,iy+3,GRAPH_FILL);} }
    private String formatGraphTimeLabel(long durationMs){ long totalSeconds=Math.max(0L,durationMs/1000L), minutes=totalSeconds/60L; if(totalSeconds<60L) return totalSeconds+"s"; if(minutes<60L) return minutes+"m"; long hours=minutes/60L, remainingMinutes=minutes%60L; return remainingMinutes==0L?hours+"h":hours+"h "+remainingMinutes+"m"; }
    private String formatClock(long ms){ long s=Math.max(0L,ms/1000L), h=s/3600L, m=(s%3600L)/60L, sec=s%60L; return String.format("%02d:%02d:%02d",h,m,sec); }
    private static int inactiveValueColor(String value){ String text=value==null?"":value.trim(); return "--".equals(text)||"Paused".equals(text)||"00:00:00".equals(text)?INACTIVE:TEXT; }
    private SessionData getSelected(){ return this.selectedIndex>=0&&this.selectedIndex<this.sessions.size()?this.sessions.get(this.selectedIndex):null; }
    private String getTopBlock(SessionData session){ String id=null; long count=0L; for(Map.Entry<String,Long> e:session.blockBreakdown.entrySet()) if(e.getValue()>count){ id=e.getKey(); count=e.getValue(); } return id==null?"No breakdown":resolveName(id)+" ("+UiFormat.formatCompact(count)+")"; }
    private String resolveName(String id){ try{ Identifier i=Identifier.tryParse(id); if(i!=null){ var b=net.minecraft.registry.Registries.BLOCK.get(i); if(b!=null) return b.getName().getString(); } }catch(Exception ignored){} return id; }
    private String truncate(String value,int maxWidth){ return MmmUi.truncate(this.textRenderer, value, maxWidth); }
    private Layout layout(){ int availableWidth=Math.max(340,MmmUi.contentWidth(this.width)-M), topY=MmmUi.TOP_BAR_HEIGHT+10, availableHeight=Math.max(260,this.height-topY-12); int panelWidth=Math.min(840,Math.max(540,availableWidth)), panelHeight=Math.min(Math.min(560,Math.max(380,availableHeight)),availableHeight), panelX=MmmUi.centerContentX(this.width,panelWidth), panelY=topY+Math.max(0,(availableHeight-panelHeight)/2), contentX=panelX+P, contentWidth=panelWidth-P*2, headerY=panelY+P, contentY=headerY+88, overviewY=headerY+58, leftWidth=Math.min(Math.max(280,(int)(contentWidth*0.54F))-G/2,Math.max(260,contentWidth-G-300)), detailX=contentX+leftWidth+G, detailWidth=contentWidth-leftWidth-G, contentHeight=panelY+panelHeight-P-contentY; return new Layout(panelX,panelY,panelWidth,panelHeight,panelX+panelWidth,contentX,contentWidth,headerY,overviewY,contentY,leftWidth,detailX,detailWidth,contentHeight); }
    private record Layout(int panelX,int panelY,int panelWidth,int panelHeight,int panelRight,int contentX,int contentWidth,int headerY,int overviewY,int contentY,int leftWidth,int detailX,int detailWidth,int contentHeight){ private Layout move(int delta){ return new Layout(panelX,panelY+delta,panelWidth,panelHeight,panelRight,contentX,contentWidth,headerY+delta,overviewY+delta,contentY+delta,leftWidth,detailX,detailWidth,contentHeight); } }
    private record BreakdownMetrics(int listX,int listY,int listHeight,int viewportWidth,int scrollbarX){}
}
