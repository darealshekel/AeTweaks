package com.miningtrackeraddon.hud;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import com.miningtrackeraddon.storage.SessionData;
import com.miningtrackeraddon.storage.SessionHistory;
import com.miningtrackeraddon.util.UiFormat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public class SessionHistoryScreen extends Screen
{
    private static final int ROW_H = 28;
    private static final int PANEL_W = 420;
    private static final int DETAIL_H = 110;
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd  HH:mm");

    private final Screen parent;
    private final List<SessionData> sessions;
    private int scrollOffset;
    private int selectedIndex = -1;

    public SessionHistoryScreen(Screen parent)
    {
        super(Text.literal("Session History"));
        this.parent = parent;
        this.sessions = SessionHistory.getHistory();
        this.scrollOffset = Math.max(0, this.sessions.size() - visibleRows());
        if (this.sessions.isEmpty() == false)
        {
            this.selectedIndex = this.sessions.size() - 1;
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta)
    {
        context.fill(0, 0, this.width, this.height, 0xCC000000);
        super.render(context, mouseX, mouseY, delta);

        MinecraftClient client = MinecraftClient.getInstance();
        int centerX = this.width / 2;
        int panelX = centerX - PANEL_W / 2;

        String title = "Session History (" + this.sessions.size() + " sessions)";
        int titleWidth = client.textRenderer.getWidth(Text.literal(title));
        context.drawText(client.textRenderer, Text.literal(title), centerX - titleWidth / 2, 12, UiFormat.YELLOW, true);

        if (this.sessions.isEmpty())
        {
            String none = "No sessions recorded yet. Mine some blocks first.";
            int noneWidth = client.textRenderer.getWidth(Text.literal(none));
            context.drawText(client.textRenderer, Text.literal(none), centerX - noneWidth / 2, this.height / 2, UiFormat.TEXT_MUTED, false);
            renderFooter(context, client, centerX);
            return;
        }

        int listTop = 30;
        int listHeight = this.height - listTop - DETAIL_H - 30;
        int visible = listHeight / ROW_H;
        this.scrollOffset = Math.max(0, Math.min(this.scrollOffset, Math.max(0, this.sessions.size() - visible)));
        context.fill(panelX, listTop, panelX + PANEL_W, listTop + visible * ROW_H, 0xBB111111);

        for (int index = 0; index < visible; index++)
        {
            int sessionIndex = this.scrollOffset + index;
            if (sessionIndex >= this.sessions.size())
            {
                break;
            }

            SessionData session = this.sessions.get(sessionIndex);
            int rowY = listTop + index * ROW_H;
            int bg = sessionIndex == this.selectedIndex ? 0xBB334400 : (index % 2 == 0 ? 0x22FFFFFF : 0x11FFFFFF);
            context.fill(panelX, rowY, panelX + PANEL_W, rowY + ROW_H, bg);
            context.drawText(client.textRenderer, Text.literal("#" + (sessionIndex + 1) + "  " + DATE_FMT.format(new Date(session.startTimeMs))), panelX + 6, rowY + 4, UiFormat.TEXT_PRIMARY, false);
            String stats = UiFormat.formatBlocksPerHour(session.getAverageBlocksPerHour()) + "  |  " + UiFormat.formatBlocks(session.totalBlocks) + "  |  " + session.getDurationString();
            context.drawText(client.textRenderer, Text.literal(stats), panelX + 6, rowY + 14, UiFormat.TEXT_MUTED, false);
            context.fill(panelX, rowY + ROW_H - 1, panelX + PANEL_W, rowY + ROW_H, 0x44FFFFFF);
        }

        int detailY = this.height - DETAIL_H - 24;
        if (this.selectedIndex >= 0 && this.selectedIndex < this.sessions.size())
        {
            renderDetail(context, client, panelX, detailY, this.sessions.get(this.selectedIndex));
        }
        renderFooter(context, client, centerX);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers)
    {
        if (keyCode == 256)
        {
            MinecraftClient.getInstance().setScreen(this.parent);
            return true;
        }
        if (keyCode == 264 || keyCode == 341)
        {
            this.scrollOffset = Math.min(this.scrollOffset + 1, Math.max(0, this.sessions.size() - visibleRows()));
            if (this.selectedIndex < this.sessions.size() - 1) this.selectedIndex++;
            return true;
        }
        if (keyCode == 265 || keyCode == 329)
        {
            this.scrollOffset = Math.max(0, this.scrollOffset - 1);
            if (this.selectedIndex > 0) this.selectedIndex--;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount)
    {
        int delta = (int) -Math.signum(verticalAmount);
        this.scrollOffset = Math.max(0, Math.min(this.scrollOffset + delta, Math.max(0, this.sessions.size() - visibleRows())));
        this.selectedIndex = Math.max(0, Math.min(this.selectedIndex + delta, this.sessions.size() - 1));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button)
    {
        int centerX = this.width / 2;
        int panelX = centerX - PANEL_W / 2;
        int listTop = 30;
        int visible = visibleRows();
        for (int index = 0; index < visible; index++)
        {
            int sessionIndex = this.scrollOffset + index;
            if (sessionIndex >= this.sessions.size())
            {
                break;
            }
            int rowY = listTop + index * ROW_H;
            if (mouseX >= panelX && mouseX <= panelX + PANEL_W && mouseY >= rowY && mouseY <= rowY + ROW_H)
            {
                this.selectedIndex = sessionIndex;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean shouldPause()
    {
        return false;
    }

    private void renderDetail(DrawContext context, MinecraftClient client, int x, int y, SessionData session)
    {
        context.fill(x, y, x + PANEL_W, y + DETAIL_H, 0xBB111111);
        context.drawBorder(x, y, PANEL_W, DETAIL_H, 0xFFFFAA00);

        int lineHeight = client.textRenderer.fontHeight + 3;
        int textX = x + 8;
        int textY = y + 6;
        context.drawText(client.textRenderer, Text.literal("Session Detail"), textX, textY, UiFormat.YELLOW, true);
        textY += lineHeight + 2;
        context.drawText(client.textRenderer, Text.literal("Date: " + DATE_FMT.format(new Date(session.startTimeMs))), textX, textY, UiFormat.TEXT_PRIMARY, false);
        textY += lineHeight;
        context.drawText(client.textRenderer, Text.literal("Duration: " + session.getDurationString()), textX, textY, UiFormat.TEXT_PRIMARY, false);
        textY += lineHeight;
        context.drawText(client.textRenderer, Text.literal("Average Rate: " + UiFormat.formatBlocksPerHour(session.getAverageBlocksPerHour())), textX, textY, UiFormat.TEXT_PRIMARY, false);
        textY += lineHeight;
        context.drawText(client.textRenderer, Text.literal("Total Mined: " + UiFormat.formatBlocks(session.totalBlocks)), textX, textY, UiFormat.TEXT_PRIMARY, false);
        textY += lineHeight;
        context.drawText(client.textRenderer, Text.literal("Best Streak: " + session.bestStreakSeconds + "s"), textX, textY, UiFormat.TEXT_PRIMARY, false);
    }

    private void renderFooter(DrawContext context, MinecraftClient client, int centerX)
    {
        String hint = "[Up/Down] Scroll   [ESC] Close";
        int width = client.textRenderer.getWidth(Text.literal(hint));
        context.drawText(client.textRenderer, Text.literal(hint), centerX - width / 2, this.height - 14, UiFormat.TEXT_MUTED, false);
    }

    private int visibleRows()
    {
        int listHeight = this.height - 30 - DETAIL_H - 30;
        return Math.max(1, listHeight / ROW_H);
    }
}
