package com.miningtrackeraddon.hud;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.miningtrackeraddon.storage.SessionData;
import com.miningtrackeraddon.storage.SessionHistory;
import com.miningtrackeraddon.storage.WorldSessionContext;
import com.miningtrackeraddon.util.UiFormat;

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
    private static final int SBW = 8;
    private static final int SBM = 18;
    private static final int BREAKDOWN_HEIGHT = 108;
    private static final int OVERLAY = 0xB8121620;
    private static final int PANEL = 0xB4171F2D;
    private static final int CARD = 0x99202A3B;
    private static final int SOFT = 0x7F182130;
    private static final int BORDER = 0xAA42657D;
    private static final int ACCENT = 0xFF67E7FF;
    private static final int ACCENT_SOFT = 0x5540D7FF;
    private static final int TEXT = 0xFFF5FBFF;
    private static final int LABEL = 0xB6C7D6E7;
    private static final int MUTED = 0x8EA5B9CC;
    private static final int ROW_SEL = 0x5A28516B;
    private static final int ROW_HOVER = 0x2A203748;
    private static final int ROW_ALT = 0x140E1824;
    private static final int GRAPH_FILL = 0xAA5CE1FF;
    private static final int GRAPH_GRID = 0x223C5264;
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd  HH:mm");

    private final Screen parent;
    private final List<SessionData> sessions;
    private final String worldName;
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
        this.sessions = SessionHistory.getHistory();
        this.worldName = WorldSessionContext.getCurrentWorldName();
        if (!this.sessions.isEmpty())
        {
            this.selectedIndex = this.sessions.size() - 1;
            this.listScroll = Math.max(0, this.sessions.size() - 1);
        }
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
        context.fill(0, 0, this.width, this.height, OVERLAY);
        card(context, l.panelX, l.panelY, l.panelWidth, l.panelHeight, PANEL, BORDER);
        context.drawText(this.textRenderer, Text.literal("Session History"), l.contentX, l.headerY, TEXT, true);
        pill(context, l.contentX, l.headerY + 18, Math.min(220, l.contentWidth / 2), 16, this.worldName);
        context.drawText(this.textRenderer, Text.literal("Browse past sessions with the same clean summary style."), l.contentX + 2, l.headerY + 40, LABEL, false);
        drawList(context, l, mouseX, mouseY);
        drawDetail(context, l, mouseX, mouseY);
        super.render(context, mouseX, mouseY, delta);
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

    private void drawList(DrawContext context, Layout l, int mouseX, int mouseY)
    {
        card(context, l.contentX, l.contentY, l.leftWidth, l.contentHeight, SOFT, BORDER);
        context.drawText(this.textRenderer, Text.literal("Runs"), l.contentX + C, l.contentY + 10, TEXT, false);
        int x = l.contentX + C;
        int y = l.contentY + 44;
        int width = l.leftWidth - C * 2;
        int height = l.contentHeight - 56;
        int viewportWidth = width - SBW - 6;
        context.fill(x, y, x + width, y + height, 0x22131B27);
        context.drawBorder(x, y, width, height, 0x663A5368);
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
            int rowColor = index == this.selectedIndex ? ROW_SEL : hovered ? ROW_HOVER : ((row & 1) == 0 ? ROW_ALT : 0x0F111823);
            context.fill(x + 4, rowY, x + viewportWidth - 4, rowY + RH - 4, rowColor);
            context.drawText(this.textRenderer, Text.literal("#" + (index + 1) + "  " + DATE_FMT.format(new Date(session.startTimeMs))), x + 12, rowY + 6, TEXT, false);
            context.drawText(this.textRenderer, Text.literal(UiFormat.formatCompact(session.totalBlocks) + " blocks  |  " + session.getDurationString() + "  |  " + UiFormat.formatCompact(session.getAverageBlocksPerHour()) + "/hr"), x + 12, rowY + 19, MUTED, false);
        }
        context.disableScissor();
        drawListBar(context, x + width - SBW, y, height, mouseX, mouseY, visibleRows);
    }

    private void drawDetail(DrawContext context, Layout l, int mouseX, int mouseY)
    {
        card(context, l.detailX, l.contentY, l.detailWidth, l.contentHeight, CARD, BORDER);
        context.drawText(this.textRenderer, Text.literal("Session Detail"), l.detailX + C, l.contentY + 10, TEXT, false);
        SessionData session = getSelected();
        if (session == null) { context.drawText(this.textRenderer, Text.literal("Select a run to inspect it."), l.detailX + C, l.contentY + 34, MUTED, false); return; }
        int vx = l.detailX + C;
        int vy = l.contentY + 28;
        int vw = l.detailWidth - C * 2 - SBW - 6;
        int vh = l.contentHeight - 40;
        int drawY = vy - this.detailScroll;
        context.enableScissor(vx, vy, vx + vw, vy + vh);
        context.drawText(this.textRenderer, Text.literal(DATE_FMT.format(new Date(session.startTimeMs))), vx, drawY, MUTED, false);
        int cardWidth = (vw - G) / 2;
        int statY = drawY + 16;
        stat(context, vx, statY, cardWidth, 50, "Total Mined", UiFormat.formatCompact(session.totalBlocks), "blocks");
        stat(context, vx + cardWidth + G, statY, cardWidth, 50, "Active Time", formatClock(session.getDurationMs()), "session");
        stat(context, vx, statY + 56, cardWidth, 50, "Avg Rate", UiFormat.formatCompact(session.getAverageBlocksPerHour()), "blocks/hr");
        stat(context, vx + cardWidth + G, statY + 56, cardWidth, 50, "Peak Rate", UiFormat.formatCompact(session.peakBlocksPerHour), "blocks/hr");
        int graphY = statY + 118;
        card(context, vx, graphY, vw, 92, SOFT, 0x663C556C);
        context.drawText(this.textRenderer, Text.literal("Session Pace"), vx + 10, graphY + 8, TEXT, false);
        context.drawText(this.textRenderer, Text.literal("Blocks per hour across the session"), vx + 10, graphY + 20, MUTED, false);
        drawGraph(context, vx + 10, graphY + 34, vw - 20, 50, session);
        int infoY = graphY + 102;
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
        card(context, x, cardY, width, BREAKDOWN_HEIGHT, SOFT, 0x663C556C);
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
        if (entries.isEmpty()) { context.drawText(this.textRenderer, Text.literal("No block breakdown recorded."), listX, listY + 2, MUTED, false); return; }
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
                context.drawText(this.textRenderer, Text.literal(count), listX + viewportWidth - countWidth - 2, rowY, ACCENT, false);
            }
            rowY += 14;
        }
        context.disableScissor();
        if (getBreakdownMax(entries.size(), listHeight) > 0)
        {
            simpleBar(context, this.breakdownScrollbarX, listY, listHeight, getBreakdownThumb(listHeight, entries.size()), getBreakdownOffset(listHeight, entries.size()), this.draggingBreakdown || isOverBreakdownBar(mouseX, mouseY));
        }
    }

    private void drawGraph(DrawContext c, int x, int y, int w, int h, SessionData s){ c.fill(x,y,x+w,y+h,0x1C0B121C); for(int i=0;i<4;i++){ int ly=y+(h*i)/4; c.fill(x,ly,x+w,ly+1,GRAPH_GRID);} if(s.miningRateBuckets.isEmpty()){ c.drawCenteredTextWithShadow(this.textRenderer, Text.literal("No graph data saved for this run"), x+w/2, y+h/2-4, MUTED); return; } int cols=Math.max(1,Math.min(w/4,s.miningRateBuckets.size())); double[] rates=new double[cols]; double max=60d; for(int col=0;col<cols;col++){ int start=(int)Math.floor((col*s.miningRateBuckets.size())/(double)cols), end=(int)Math.floor(((col+1)*s.miningRateBuckets.size())/(double)cols); if(end<=start) end=Math.min(s.miningRateBuckets.size(),start+1); double total=0d; int count=0; for(int i=start;i<end;i++){ total+=s.miningRateBuckets.get(i)*60d; count++; } rates[col]=count<=0?0d:total/count; max=Math.max(max,rates[col]); } int bottom=y+h-8, usable=Math.max(12,h-14); float[] py=new float[cols]; int[] px=new int[cols]; for(int col=0;col<cols;col++){ float n=(float)(rates[col]/max); py[col]=bottom-n*usable; px[col]=x+Math.round((col/(float)Math.max(1,cols-1))*(w-1)); } for(int col=0;col<cols-1;col++){ float prev=col>0?py[col-1]:py[col], next=col+2<cols?py[col+2]:py[col+1]; curve(c,px[col],py[col],px[col+1],py[col+1],prev,next);} c.drawText(this.textRenderer, Text.literal(UiFormat.formatCompact(Math.round(max))+"/hr"), x+4, y+4, LABEL, false); }
    private void row(DrawContext c,int lx,int rx,int y,String label,String value){ c.drawText(this.textRenderer, Text.literal(label), lx, y, LABEL, false); int w=this.textRenderer.getWidth(value); c.drawText(this.textRenderer, Text.literal(value), rx-w, y, TEXT, false); }
    private void stat(DrawContext c,int x,int y,int w,int h,String l,String v,String s){ card(c,x,y,w,h,SOFT,0x663C556C); c.drawText(this.textRenderer, Text.literal(l), x+C, y+9, LABEL, false); c.drawText(this.textRenderer, Text.literal(v), x+C, y+24, TEXT, false); c.drawText(this.textRenderer, Text.literal(s), x+C, y+38, MUTED, false); }
    private void pill(DrawContext c,int x,int y,int w,int h,String t){ card(c,x,y,w,h,CARD,ACCENT); c.drawText(this.textRenderer, Text.literal(t), x+(w-this.textRenderer.getWidth(t))/2, y+4, TEXT, false); }
    private void card(DrawContext c,int x,int y,int w,int h,int fill,int border){ c.fill(x,y,x+w,y+h,fill); c.fill(x+1,y+1,x+w-1,y+2,ACCENT_SOFT); c.drawBorder(x,y,w,h,border); }
    private void drawListBar(DrawContext c,int x,int y,int h,int mx,int my,int vis){ int max=Math.max(0,this.sessions.size()-vis); if(max<=0) return; int th=getListThumb(h,vis), ty=y+getListOffset(h,th,max); c.fill(x,y,x+SBW,y+h,0x33203042); c.drawBorder(x,y,SBW,h,0x66506A7F); int col=this.draggingList?0xFFD8FCFF:isOverListBar(mx,my)?0xFFACF3FF:0xFF7BDCEA; c.fill(x+1,ty,x+SBW-1,ty+th,col); }
    private void drawDetailBar(DrawContext c,Layout l,int mx,int my){ int max=Math.max(0,getDetailContentHeight()-(l.contentHeight-40)); if(max<=0) return; int x=l.detailX+l.detailWidth-C-SBW,y=l.contentY+28,h=l.contentHeight-40,th=Math.max(SBM,Math.min(h,(int)Math.round((h/(double)getDetailContentHeight())*h))), off=(int)Math.round((this.detailScroll/(double)max)*(h-th)); simpleBar(c,x,y,h,th,off,this.draggingDetail||isOverDetailBar(mx,my)); }
    private void simpleBar(DrawContext c,int x,int y,int h,int th,int off,boolean active){ c.fill(x,y,x+SBW,y+h,0x33203042); c.drawBorder(x,y,SBW,h,0x66506A7F); c.fill(x+1,y+off,x+SBW-1,y+off+th,active?0xFFD8FCFF:0xFF7BDCEA); }
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
    private int getDetailContentHeight(){ return 538; }
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
    private String formatClock(long ms){ long s=Math.max(0L,ms/1000L), h=s/3600L, m=(s%3600L)/60L, sec=s%60L; return String.format("%02d:%02d:%02d",h,m,sec); }
    private SessionData getSelected(){ return this.selectedIndex>=0&&this.selectedIndex<this.sessions.size()?this.sessions.get(this.selectedIndex):null; }
    private String getTopBlock(SessionData session){ String id=null; long count=0L; for(Map.Entry<String,Long> e:session.blockBreakdown.entrySet()) if(e.getValue()>count){ id=e.getKey(); count=e.getValue(); } return id==null?"No breakdown":resolveName(id)+" ("+UiFormat.formatCompact(count)+")"; }
    private String resolveName(String id){ try{ Identifier i=Identifier.tryParse(id); if(i!=null){ var b=net.minecraft.registry.Registries.BLOCK.get(i); if(b!=null) return b.getName().getString(); } }catch(Exception ignored){} return id; }
    private String truncate(String value,int maxWidth){ if(this.textRenderer.getWidth(value)<=maxWidth) return value; String e="...", t=value; while(t.length()>1&&this.textRenderer.getWidth(t+e)>maxWidth) t=t.substring(0,t.length()-1); return t+e; }
    private Layout layout(){ int panelWidth=Math.min(760,Math.max(580,this.width-M*2)), panelHeight=Math.min(520,Math.max(420,this.height-28)), panelX=(this.width-panelWidth)/2, panelY=(this.height-panelHeight)/2, contentX=panelX+P, contentWidth=panelWidth-P*2, headerY=panelY+P, contentY=headerY+58, overviewY=headerY+58, leftWidth=Math.max(300,(int)(contentWidth*0.50F))-G/2, detailX=contentX+leftWidth+G, detailWidth=contentWidth-leftWidth-G, contentHeight=panelY+panelHeight-P-contentY; return new Layout(panelX,panelY,panelWidth,panelHeight,panelX+panelWidth,contentX,contentWidth,headerY,overviewY,contentY,leftWidth,detailX,detailWidth,contentHeight); }
    private record Layout(int panelX,int panelY,int panelWidth,int panelHeight,int panelRight,int contentX,int contentWidth,int headerY,int overviewY,int contentY,int leftWidth,int detailX,int detailWidth,int contentHeight){ private Layout move(int delta){ return new Layout(panelX,panelY+delta,panelWidth,panelHeight,panelRight,contentX,contentWidth,headerY+delta,overviewY+delta,contentY+delta,leftWidth,detailX,detailWidth,contentHeight); } }
    private record BreakdownMetrics(int listX,int listY,int listHeight,int viewportWidth,int scrollbarX){}
}
