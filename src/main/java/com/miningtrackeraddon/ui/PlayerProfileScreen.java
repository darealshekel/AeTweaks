package com.miningtrackeraddon.ui;

import com.miningtrackeraddon.config.Configs;
import com.miningtrackeraddon.storage.WorldSessionContext;
import com.miningtrackeraddon.sync.CloudSyncManager;
import com.miningtrackeraddon.sync.WebsiteProfileTotals;
import com.miningtrackeraddon.tracker.MiningStats;
import com.miningtrackeraddon.util.UiFormat;
import java.util.Comparator;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

public class PlayerProfileScreen extends Screen
{
    private static final String WEBSITE_BASE_URL = "https://www.mmmaniacs.com/player/";
    private static final int PANEL_PADDING = 18;
    private static final int CARD_PADDING = 12;
    private static final int CARD_GAP = 10;
    private static final int BUTTON_HEIGHT = 20;

    private final Screen parent;

    public PlayerProfileScreen(Screen parent)
    {
        super(Text.literal("Player Profile"));
        this.parent = parent;
    }

    @Override
    protected void init()
    {
        MmmUi.ensureCursorVisible();
        WebsiteProfileTotals.refresh(false);
        this.clearChildren();
        Layout layout = computeLayout();
        ButtonWidget profileButton = ButtonWidget.builder(Text.literal("Open Website Profile"), button -> openWebsiteProfile())
                .dimensions(layout.panelRight - 196, layout.headerY - 2, 118, BUTTON_HEIGHT)
                .build();
        profileButton.active = hasProfileName();
        this.addDrawableChild(profileButton);
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> close())
                .dimensions(layout.panelRight - 70, layout.headerY - 2, 60, BUTTON_HEIGHT)
                .build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta)
    {
        MmmUi.ensureCursorVisible();
        Layout layout = computeLayout();
        MmmUi.backdrop(context, this.width, this.height);
        MmmUi.card(context, layout.panelX, layout.panelY, layout.panelWidth, layout.panelHeight, MmmUi.PANEL, MmmUi.BORDER);

        context.drawText(this.textRenderer, this.title, layout.contentX, layout.headerY, MmmUi.TEXT, true);
        MmmUi.statusChip(context, this.textRenderer, layout.contentX, layout.headerY + 18, syncLabel(), syncColor());
        context.drawText(this.textRenderer, Text.literal("Local mining data is stored on this client and sent on your account cadence."), layout.contentX, layout.headerY + 42, MmmUi.LABEL, false);

        drawTotalsCard(context, layout.leftX, layout.cardsY, layout.cardWidth, 102);
        drawRecordsCard(context, layout.rightX, layout.cardsY, layout.cardWidth, 102);
        drawSourceCard(context, layout.leftX, layout.cardsY + 112, layout.cardWidth, layout.lowerHeight);
        drawBreakdownCard(context, layout.rightX, layout.cardsY + 112, layout.cardWidth, layout.lowerHeight);

        super.render(context, mouseX, mouseY, delta);
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

    private void drawTotalsCard(DrawContext context, int x, int y, int width, int height)
    {
        long globalTotal = Configs.websiteGlobalTotalBlocks > 0L ? Configs.websiteGlobalTotalBlocks : Configs.totalBlocksMined;
        long worldTotal = MiningStats.getCurrentSourceTotalMined();
        MmmUi.card(context, x, y, width, height, MmmUi.CARD, MmmUi.BORDER);
        drawCardTitle(context, x, y, "Totals");
        drawMetric(context, x, y + 30, width, "Global Total", UiFormat.formatBlocks(globalTotal), MmmUi.ACCENT_BRIGHT);
        drawMetric(context, x, y + 56, width, "World Total", UiFormat.formatBlocks(worldTotal), MmmUi.TEXT);
        context.drawText(this.textRenderer, Text.literal(lastGlobalUpdateText()), x + CARD_PADDING, y + height - 14, MmmUi.MUTED, false);
    }

    private void drawRecordsCard(DrawContext context, int x, int y, int width, int height)
    {
        MmmUi.card(context, x, y, width, height, MmmUi.CARD, MmmUi.BORDER);
        drawCardTitle(context, x, y, "Records");
        drawMetric(context, x, y + 28, width, "Today / Week", UiFormat.formatCompact(MiningStats.getDailyBlocksMined()) + " / " + UiFormat.formatCompact(MiningStats.getWeeklyBlocksMined()), MmmUi.TEXT);
        drawMetric(context, x, y + 52, width, "PR Day / Week", UiFormat.formatCompact(MiningStats.getPersonalRecordDailyBlocks()) + " / " + UiFormat.formatCompact(MiningStats.getPersonalRecordWeeklyBlocks()), MmmUi.TEXT);
        drawMetric(context, x, y + 76, width, "Fastest 100K", MiningStats.getFastest100kMs() > 0L ? MiningStats.getFastest100kClock() : "-", MmmUi.TEXT);
    }

    private void drawSourceCard(DrawContext context, int x, int y, int width, int height)
    {
        WorldSessionContext.WorldInfo world = WorldSessionContext.getCurrentWorldInfo();
        MmmUi.card(context, x, y, width, height, MmmUi.CARD_SOFT, MmmUi.BORDER);
        drawCardTitle(context, x, y, "Current Source");
        drawMetric(context, x, y + 30, width, "Name", MmmUi.truncate(this.textRenderer, world.displayName(), width - 92), MmmUi.TEXT);
        drawMetric(context, x, y + 56, width, "Type", world.kind(), MmmUi.TEXT);
        drawMetric(context, x, y + 82, width, "Estimated Pace", UiFormat.formatDetailedBlocksPerHour(Math.round(MiningStats.getPredictionSnapshot().blocksPerHour())), MmmUi.TEXT);
        drawMetric(context, x, y + 108, width, "Sync Every", UiFormat.formatDuration(CloudSyncManager.getSyncIntervalMs() / 1000L), MmmUi.TEXT);
    }

    private void drawBreakdownCard(DrawContext context, int x, int y, int width, int height)
    {
        WorldSessionContext.WorldInfo world = WorldSessionContext.getCurrentWorldInfo();
        Configs.WorldStatsEntry stats = Configs.getOrCreateWorldStats(world.id(), world.displayName(), world.kind(), world.host());
        MmmUi.card(context, x, y, width, height, MmmUi.CARD_SOFT, MmmUi.BORDER);
        drawCardTitle(context, x, y, "Mined Blocks");

        if (stats.blockBreakdown == null || stats.blockBreakdown.isEmpty())
        {
            context.drawText(this.textRenderer, Text.literal("No per-block data stored for this source yet."), x + CARD_PADDING, y + 34, MmmUi.MUTED, false);
            return;
        }

        int rowY = y + 30;
        int index = 0;
        for (Map.Entry<String, Long> entry : stats.blockBreakdown.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()))
                .limit(Math.max(1, (height - 42) / 16))
                .toList())
        {
            int rowColor = index % 2 == 0 ? MmmUi.ROW_ALT : MmmUi.INSET;
            context.fill(x + CARD_PADDING, rowY - 3, x + width - CARD_PADDING, rowY + 12, rowColor);
            context.drawText(this.textRenderer, Text.literal(formatBlockId(entry.getKey())), x + CARD_PADDING + 6, rowY, MmmUi.TEXT, false);
            String count = UiFormat.formatCompact(entry.getValue());
            context.drawText(this.textRenderer, Text.literal(count), x + width - CARD_PADDING - 6 - this.textRenderer.getWidth(count), rowY, MmmUi.ACCENT_BRIGHT, false);
            rowY += 16;
            index++;
        }
    }

    private void drawCardTitle(DrawContext context, int x, int y, String title)
    {
        context.drawText(this.textRenderer, Text.literal(title), x + CARD_PADDING, y + 10, MmmUi.TEXT, false);
    }

    private void drawMetric(DrawContext context, int x, int y, int width, String label, String value, int valueColor)
    {
        context.drawText(this.textRenderer, Text.literal(label), x + CARD_PADDING, y, MmmUi.MUTED, false);
        String clipped = MmmUi.truncate(this.textRenderer, value, width - CARD_PADDING * 2 - 88);
        context.drawText(this.textRenderer, Text.literal(clipped), x + width - CARD_PADDING - this.textRenderer.getWidth(clipped), y, valueColor, false);
    }

    private String syncLabel()
    {
        String tier = CloudSyncManager.getSyncTier();
        return tier.replace('_', ' ').toUpperCase() + " sync";
    }

    private int syncColor()
    {
        return switch (CloudSyncManager.getSyncTier())
        {
            case "owner", "supporter_plus" -> MmmUi.ACCENT;
            case "supporter" -> MmmUi.BLUE;
            default -> MmmUi.BORDER;
        };
    }

    private String lastGlobalUpdateText()
    {
        long updated = Math.max(Configs.websiteGlobalTotalUpdatedAtMs, Configs.websiteLastSuccessfulSyncMs);
        if (updated <= 0L)
        {
            return "Website profile total not loaded yet";
        }
        long ageSeconds = Math.max(0L, (System.currentTimeMillis() - updated) / 1000L);
        return "Website profile update " + UiFormat.formatDuration(ageSeconds) + " ago";
    }

    private void openWebsiteProfile()
    {
        String username = resolveProfileName();
        if (username != null && username.isBlank() == false)
        {
            Util.getOperatingSystem().open(WEBSITE_BASE_URL + username.toLowerCase());
        }
    }

    private boolean hasProfileName()
    {
        String username = resolveProfileName();
        return username != null && username.isBlank() == false;
    }

    private String resolveProfileName()
    {
        String username = Configs.websiteLinkedMinecraftUsername;
        if (username == null || username.isBlank())
        {
            MinecraftClient client = MinecraftClient.getInstance();
            username = client != null && client.getSession() != null ? client.getSession().getUsername() : "";
        }
        return username;
    }

    private static String formatBlockId(String blockId)
    {
        String value = blockId == null ? "" : blockId;
        int separator = value.indexOf(':');
        if (separator >= 0 && separator + 1 < value.length())
        {
            value = value.substring(separator + 1);
        }
        return value.replace('_', ' ');
    }

    private Layout computeLayout()
    {
        int panelWidth = Math.min(this.width - 24, 620);
        int panelHeight = Math.min(this.height - 24, 336);
        int panelX = (this.width - panelWidth) / 2;
        int panelY = (this.height - panelHeight) / 2;
        int contentX = panelX + PANEL_PADDING;
        int contentWidth = panelWidth - PANEL_PADDING * 2;
        int cardWidth = (contentWidth - CARD_GAP) / 2;
        int cardsY = panelY + 74;
        int lowerHeight = Math.max(126, panelY + panelHeight - cardsY - 112 - PANEL_PADDING);
        return new Layout(panelX, panelY, panelWidth, panelHeight, panelX + panelWidth, contentX, panelY + 16, contentWidth, contentX, contentX + cardWidth + CARD_GAP, cardWidth, cardsY, lowerHeight);
    }

    private record Layout(int panelX, int panelY, int panelWidth, int panelHeight, int panelRight, int contentX, int headerY, int contentWidth, int leftX, int rightX, int cardWidth, int cardsY, int lowerHeight)
    {
    }
}
