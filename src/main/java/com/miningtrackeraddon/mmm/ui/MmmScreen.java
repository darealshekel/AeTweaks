package com.miningtrackeraddon.mmm.ui;

import com.miningtrackeraddon.mmm.MmmManager;
import com.miningtrackeraddon.mmm.data.MmmDigBreakdown;
import com.miningtrackeraddon.mmm.data.MmmLeaderboardEntry;
import com.miningtrackeraddon.mmm.data.MmmStatus;
import com.miningtrackeraddon.mmm.data.MmmViewState;
import com.miningtrackeraddon.mmm.render.MmmIconRenderer;
import com.miningtrackeraddon.util.UiFormat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class MmmScreen extends Screen
{
    private static final int PANEL_WIDTH = 420;
    private static final int PANEL_HEIGHT = 332;
    private static final int BREAKDOWN_ROW_HEIGHT = 18;

    private final Screen parent;
    private int breakdownScrollOffset;

    public MmmScreen(Screen parent)
    {
        super(Text.literal("MMM"));
        this.parent = parent;
    }

    @Override
    protected void init()
    {
        int panelX = (this.width - PANEL_WIDTH) / 2;
        int panelY = (this.height - PANEL_HEIGHT) / 2;

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"), button -> MmmManager.refreshForClient(MinecraftClient.getInstance(), true))
                .dimensions(panelX + PANEL_WIDTH - 88, panelY + 14, 70, 20)
                .build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> close())
                .dimensions(panelX + PANEL_WIDTH / 2 - 50, panelY + PANEL_HEIGHT - 30, 100, 20)
                .build());

        MmmManager.refreshForClient(MinecraftClient.getInstance(), false);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta)
    {
        context.fill(0, 0, this.width, this.height, 0xE0101010);

        int panelX = (this.width - PANEL_WIDTH) / 2;
        int panelY = (this.height - PANEL_HEIGHT) / 2;
        int contentX = panelX + 16;
        int contentWidth = PANEL_WIDTH - 32;

        context.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, 0xFF161616);
        context.drawBorder(panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, 0xFF6C5A1A);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, panelY + 18, UiFormat.YELLOW);

        MmmViewState state = MmmManager.getViewState();
        context.drawText(this.textRenderer, Text.literal(state.statusText()), contentX, panelY + 44, UiFormat.TEXT_MUTED, false);

        drawContextRow(context, state, contentX, panelY + 62, contentWidth);
        drawMatchedEntry(context, state, contentX, panelY + 92, contentWidth);

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

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount)
    {
        if (isInsideBreakdown(mouseX, mouseY))
        {
            MmmViewState state = MmmManager.getViewState();
            int maxScroll = 0;
            if (state.matchedEntry() != null)
            {
                maxScroll = Math.max(0, state.matchedEntry().getBreakdown().size() * BREAKDOWN_ROW_HEIGHT - 50);
            }
            this.breakdownScrollOffset = Math.max(0, Math.min(maxScroll, this.breakdownScrollOffset + (verticalAmount < 0 ? BREAKDOWN_ROW_HEIGHT : -BREAKDOWN_ROW_HEIGHT)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void drawContextRow(DrawContext context, MmmViewState state, int x, int y, int width)
    {
        context.fill(x, y, x + width, y + 20, 0xFF202020);
        context.drawBorder(x, y, width, 20, 0x553C3C3C);
        context.drawText(this.textRenderer, Text.literal("Current Player"), x + 8, y + 6, UiFormat.YELLOW, false);

        String username = state.currentUsername().isBlank() ? "Unavailable" : state.currentUsername();
        int iconX = x + width - 84;
        MmmIconRenderer.drawEnvironmentIcon(context, MinecraftClient.getInstance(), state.multiplayerContext() ? com.miningtrackeraddon.mmm.data.MmmEnvironment.SERVER : com.miningtrackeraddon.mmm.data.MmmEnvironment.SINGLEPLAYER, iconX, y + 5);
        context.drawText(this.textRenderer, Text.literal(username), x + 110, y + 6, UiFormat.TEXT_PRIMARY, false);
        context.drawText(this.textRenderer, Text.literal(state.multiplayerContext() ? "Multiplayer" : "Singleplayer"), iconX + 14, y + 6, UiFormat.TEXT_PRIMARY, false);
    }

    private void drawMatchedEntry(DrawContext context, MmmViewState state, int x, int y, int width)
    {
        context.fill(x, y, x + width, y + 172, 0xFF202020);
        context.drawBorder(x, y, width, 172, 0x553C3C3C);
        context.drawText(this.textRenderer, Text.literal("Manual Mining Leaderboards"), x + 10, y + 8, UiFormat.YELLOW, false);

        if (state.status() != MmmStatus.READY || state.matchedEntry() == null)
        {
            String statusLine = switch (state.status())
            {
                case LOADING -> "Loading MMM data...";
                case NOT_FOUND -> "No leaderboard row matched this player.";
                case UNAVAILABLE -> "MMM needs an in-game player to resolve the username.";
                case ERROR -> "The MMM source could not be loaded.";
                case READY -> "";
            };
            context.drawText(this.textRenderer, Text.literal(statusLine), x + 10, y + 34, UiFormat.TEXT_PRIMARY, false);
            return;
        }

        MmmLeaderboardEntry entry = state.matchedEntry();
        if (!entry.flagIconUrl().isBlank())
        {
            MmmIconRenderer.drawFlag(context, entry.flagIconUrl(), x + 12, y + 28);
        }
        int skinX = x + 36;
        MmmIconRenderer.drawSkinFace(context, MinecraftClient.getInstance(), skinX, y + 22, 24);
        context.drawText(this.textRenderer, Text.literal("#" + entry.rank()), x + 64, y + 28, 0xFF7FB5FF, false);
        context.drawText(this.textRenderer, Text.literal(entry.username()), x + 64, y + 42, UiFormat.TEXT_PRIMARY, false);
        context.drawText(this.textRenderer, Text.literal("Total Digs: " + UiFormat.formatBlocks(entry.totalDigs())), x + 64, y + 56, UiFormat.TEXT_PRIMARY, false);

        context.drawText(this.textRenderer, Text.literal("Breakdown"), x + 10, y + 84, UiFormat.YELLOW, false);

        int breakdownY = y + 102;
        int breakdownHeight = 50;
        context.enableScissor(x + 8, breakdownY - 2, x + width - 8, breakdownY + breakdownHeight);
        int rowIndex = 0;
        for (MmmDigBreakdown breakdown : entry.getBreakdown())
        {
            int rowY = breakdownY + rowIndex * BREAKDOWN_ROW_HEIGHT - this.breakdownScrollOffset;
            if (rowY + BREAKDOWN_ROW_HEIGHT < breakdownY)
            {
                rowIndex++;
                continue;
            }
            if (rowY > breakdownY + breakdownHeight)
            {
                break;
            }

            MmmIconRenderer.drawBreakdownType(context, MinecraftClient.getInstance(), breakdown.singleplayer(), x + 12, rowY + 2);
            context.drawText(this.textRenderer, Text.literal(breakdown.iconUrl()), x + 28, rowY + 2, UiFormat.YELLOW, false);
            String digsText = UiFormat.formatBlocks(breakdown.digs());
            int valueX = x + width - 12 - this.textRenderer.getWidth(digsText);
            context.drawText(this.textRenderer, Text.literal(digsText), valueX, rowY + 2, UiFormat.TEXT_PRIMARY, false);
            rowIndex++;
        }
        context.disableScissor();
    }

    private boolean isInsideBreakdown(double mouseX, double mouseY)
    {
        int panelX = (this.width - PANEL_WIDTH) / 2;
        int panelY = (this.height - PANEL_HEIGHT) / 2;
        int contentX = panelX + 16;
        int breakdownX = contentX + 8;
        int breakdownY = panelY + 92 + 102 - 2;
        return mouseX >= breakdownX && mouseX <= breakdownX + (PANEL_WIDTH - 32) - 16 &&
                mouseY >= breakdownY && mouseY <= breakdownY + 52;
    }
}
