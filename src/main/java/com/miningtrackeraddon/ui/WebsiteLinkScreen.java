package com.miningtrackeraddon.ui;

import java.util.List;

import com.miningtrackeraddon.config.Configs;
import com.miningtrackeraddon.sync.WebsiteLinkManager;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

public class WebsiteLinkScreen extends Screen
{
    private static final String WEBSITE_LOGIN_URL = "https://aewt-sync-pro.vercel.app/login";
    private static final int PANEL_MARGIN = 20;
    private static final int PANEL_PADDING = 20;
    private static final int CARD_PADDING = 14;
    private static final int CARD_GAP = 12;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_ROW_GAP = 10;
    private static final int HEADER_TEXT_WIDTH = 460;
    private static final int INPUT_HEIGHT = 24;
    private static final int COLOR_OVERLAY = 0xC20B0D12;
    private static final int COLOR_PANEL = 0xD1171A20;
    private static final int COLOR_CARD = 0xB11B1F27;
    private static final int COLOR_CARD_SOFT = 0x8F14181F;
    private static final int COLOR_INSET = 0xA111141A;
    private static final int COLOR_BORDER = 0xAA4A3538;
    private static final int COLOR_BORDER_SOFT = 0x66443638;
    private static final int COLOR_ACCENT = 0xFFE3BD78;
    private static final int COLOR_ACCENT_SOFT = 0x553A2411;
    private static final int COLOR_VALUE = 0xFFF5EEE7;
    private static final int COLOR_LABEL = 0xD2D5C9BF;
    private static final int COLOR_MUTED = 0x958B8178;
    private static final int COLOR_SUCCESS = 0xFF7EEDAA;
    private static final int COLOR_WARNING = 0xFFF4D56A;
    private static final int COLOR_ERROR = 0xFFFF8D8D;

    private final Screen parent;
    private TextFieldWidget codeField;
    private ButtonWidget openButton;
    private ButtonWidget submitButton;
    private ButtonWidget clearButton;
    private ButtonWidget doneButton;

    public WebsiteLinkScreen(Screen parent)
    {
        super(Text.literal("Website Link"));
        this.parent = parent;
    }

    @Override
    protected void init()
    {
        ensureCursorVisible();
        this.clearChildren();

        Layout layout = computeLayout();
        int contentInnerWidth = layout.linkWidth - CARD_PADDING * 2;
        int buttonWidth = (contentInnerWidth - CARD_GAP) / 2;

        this.codeField = new TextFieldWidget(this.textRenderer, layout.linkX + CARD_PADDING, layout.linkY + 78, contentInnerWidth, BUTTON_HEIGHT, Text.empty());
        this.codeField.setMaxLength(12);
        this.codeField.setDrawsBackground(false);
        this.codeField.setEditableColor(COLOR_VALUE);
        this.codeField.setUneditableColor(COLOR_MUTED);
        this.codeField.setChangedListener(text -> {
            String sanitized = text == null ? "" : text.toUpperCase().replaceAll("[^A-Z0-9]", "");
            if (!sanitized.equals(text))
            {
                this.codeField.setText(sanitized);
                return;
            }
            refreshState();
        });
        this.codeField.setPlaceholder(Text.literal("ENTER WEBSITE CODE"));
        this.addDrawableChild(this.codeField);

        this.openButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Open Website"), button -> Util.getOperatingSystem().open(WEBSITE_LOGIN_URL))
                .dimensions(layout.linkX + CARD_PADDING, layout.linkY + 108, buttonWidth, BUTTON_HEIGHT)
                .build());
        this.submitButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Link Account"), button -> submitCode())
                .dimensions(layout.linkX + CARD_PADDING + buttonWidth + CARD_GAP, layout.linkY + 108, buttonWidth, BUTTON_HEIGHT)
                .build());
        this.clearButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Clear Link"), button -> clearPersistedLink())
                .dimensions(layout.statusButtonX, layout.statusPrimaryButtonY, layout.statusButtonWidth, BUTTON_HEIGHT)
                .build());
        this.doneButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> close())
                .dimensions(layout.statusButtonX, layout.statusSecondaryButtonY, layout.statusButtonWidth, BUTTON_HEIGHT)
                .build());

        refreshState();
        this.setInitialFocus(this.codeField);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta)
    {
        ensureCursorVisible();
        Layout layout = computeLayout();
        updateWidgetBounds(layout);

        context.fill(0, 0, this.width, this.height, COLOR_OVERLAY);
        fillCard(context, layout.panelX, layout.panelY, layout.panelWidth, layout.panelHeight, COLOR_PANEL, COLOR_BORDER);

        drawHeader(context, layout);
        drawStepsCard(context, layout);
        drawLinkCard(context, layout);
        drawStatusCard(context, layout);
        drawCodeFieldShell(context);

        super.render(context, mouseX, mouseY, delta);

        if (this.codeField != null && this.codeField.getText().isBlank() && this.codeField.isFocused() == false)
        {
            context.drawText(this.textRenderer, Text.literal("ENTER WEBSITE CODE"), this.codeField.getX() + 10, this.codeField.getY() + 8, COLOR_MUTED, false);
        }
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

    private void drawHeader(DrawContext context, Layout layout)
    {
        context.drawText(this.textRenderer, this.title, layout.contentX, layout.headerY, COLOR_VALUE, true);
        drawPill(context, layout.contentX, layout.headerY + 18, 126, 16, "Account Sync");
        drawWrappedText(
                context,
                "Use the same dark-site flow: open the website, generate a temporary code, then confirm the link here.",
                layout.contentX + 2,
                layout.headerY + 38,
                Math.min(HEADER_TEXT_WIDTH, layout.contentWidth - 4),
                COLOR_LABEL);
    }

    private void drawStepsCard(DrawContext context, Layout layout)
    {
        fillCard(context, layout.stepsX, layout.stepsY, layout.stepsWidth, layout.stepsHeight, COLOR_CARD, COLOR_BORDER);
        context.drawText(this.textRenderer, Text.literal("How It Works"), layout.stepsX + CARD_PADDING, layout.stepsY + 10, COLOR_VALUE, false);
        context.drawText(this.textRenderer, Text.literal("Three steps, kept tight and readable."), layout.stepsX + CARD_PADDING, layout.stepsY + 23, COLOR_MUTED, false);

        int rowY = layout.stepsY + 46;
        int rowWidth = layout.stepsWidth - CARD_PADDING * 2;
        int miniGap = 12;
        int cardWidth = (rowWidth - miniGap * 2) / 3;
        drawMiniStep(context, layout.stepsX + CARD_PADDING, rowY, cardWidth, "1", "Open website", "Go to the login page in your browser.");
        drawMiniStep(context, layout.stepsX + CARD_PADDING + cardWidth + miniGap, rowY, cardWidth, "2", "Generate code", "Create a temporary mod link code there.");
        drawMiniStep(context, layout.stepsX + CARD_PADDING + (cardWidth + miniGap) * 2, rowY, cardWidth, "3", "Paste and link", "Enter the code below and confirm.");
    }

    private void drawLinkCard(DrawContext context, Layout layout)
    {
        fillCard(context, layout.linkX, layout.linkY, layout.linkWidth, layout.linkHeight, COLOR_CARD_SOFT, COLOR_BORDER);
        context.drawText(this.textRenderer, Text.literal("Link Code"), layout.linkX + CARD_PADDING, layout.linkY + 10, COLOR_VALUE, false);
        context.drawText(this.textRenderer, Text.literal("Paste the code from the site and finish the account link from here."), layout.linkX + CARD_PADDING, layout.linkY + 23, COLOR_MUTED, false);
        context.drawText(this.textRenderer, Text.literal("Website"), layout.linkX + CARD_PADDING, layout.linkY + 46, COLOR_LABEL, false);
        context.drawText(this.textRenderer, Text.literal(WEBSITE_LOGIN_URL), layout.linkX + CARD_PADDING + 52, layout.linkY + 46, COLOR_ACCENT, false);
        context.drawText(this.textRenderer, Text.literal("Website Code"), layout.linkX + CARD_PADDING, layout.linkY + 64, COLOR_LABEL, false);
    }

    private void drawStatusCard(DrawContext context, Layout layout)
    {
        WebsiteLinkManager.LinkState state = WebsiteLinkManager.getState();
        String persistedUser = WebsiteLinkManager.getPersistedUsername();
        boolean linked = WebsiteLinkManager.hasPersistedLink();
        String statusLine = linked
                ? "Linked as " + (persistedUser.isBlank() ? "this Minecraft account" : persistedUser)
                : "No website link saved yet.";
        int statusColor = linked ? COLOR_SUCCESS : COLOR_LABEL;
        String stateLabel = switch (state.status())
        {
            case SUCCESS -> "Ready";
            case ERROR -> "Error";
            case QUEUED -> "Queued";
            case SUBMITTING -> "Submitting";
            default -> linked ? "Linked" : "Idle";
        };
        int stateColor = switch (state.status())
        {
            case SUCCESS -> COLOR_SUCCESS;
            case ERROR -> COLOR_ERROR;
            case QUEUED -> COLOR_WARNING;
            case SUBMITTING -> COLOR_LABEL;
            default -> linked ? COLOR_SUCCESS : COLOR_MUTED;
        };

        fillCard(context, layout.statusX, layout.statusY, layout.statusWidth, layout.statusHeight, COLOR_CARD_SOFT, COLOR_BORDER);
        context.drawText(this.textRenderer, Text.literal("Saved Link"), layout.statusX + CARD_PADDING, layout.statusY + 10, COLOR_VALUE, false);
        drawStatusChip(context, layout.statusX + CARD_PADDING, layout.statusY + 28, stateLabel, stateColor);

        int summaryY = layout.statusY + 52;
        fillCard(context, layout.statusX + CARD_PADDING, summaryY, layout.statusWidth - CARD_PADDING * 2, 60, COLOR_INSET, COLOR_BORDER_SOFT);
        drawWrappedText(context, statusLine, layout.statusX + CARD_PADDING + 10, summaryY + 10, layout.statusWidth - CARD_PADDING * 2 - 20, statusColor);
        drawWrappedText(
                context,
                linked ? "Stored locally and ready for sync." : "Nothing is stored locally on this client yet.",
                layout.statusX + CARD_PADDING + 10,
                summaryY + 30,
                layout.statusWidth - CARD_PADDING * 2 - 20,
                COLOR_MUTED);

        if (state.detail().isBlank() == false)
        {
            int detailColor = switch (state.status())
            {
                case SUCCESS -> COLOR_SUCCESS;
                case ERROR -> COLOR_ERROR;
                case QUEUED -> COLOR_WARNING;
                case SUBMITTING -> COLOR_LABEL;
                default -> COLOR_MUTED;
            };
            fillCard(context, layout.statusX + CARD_PADDING, summaryY + 72, layout.statusWidth - CARD_PADDING * 2, 54, COLOR_INSET, COLOR_BORDER_SOFT);
            context.drawText(this.textRenderer, Text.literal("Latest Status"), layout.statusX + CARD_PADDING + 10, summaryY + 80, COLOR_LABEL, false);
            drawWrappedText(context, state.detail(), layout.statusX + CARD_PADDING + 10, summaryY + 92, layout.statusWidth - CARD_PADDING * 2 - 20, detailColor);
        }
    }

    private void drawMiniStep(DrawContext context, int x, int y, int width, String number, String title, String description)
    {
        fillCard(context, x, y, width, 64, COLOR_INSET, COLOR_BORDER_SOFT);
        context.drawText(this.textRenderer, Text.literal(number), x + 10, y + 10, COLOR_ACCENT, false);
        context.drawText(this.textRenderer, Text.literal(title), x + 24, y + 10, COLOR_VALUE, false);
        drawWrappedText(context, description, x + 10, y + 27, width - 20, COLOR_MUTED);
    }

    private void drawCodeFieldShell(DrawContext context)
    {
        if (this.codeField == null)
        {
            return;
        }

        int x = this.codeField.getX() - 1;
        int y = this.codeField.getY() - 1;
        int width = this.codeField.getWidth() + 2;
        int height = INPUT_HEIGHT;
        fillCard(context, x, y, width, height, COLOR_INSET, this.codeField.isFocused() ? COLOR_ACCENT : COLOR_BORDER_SOFT);
    }

    private void drawWrappedText(DrawContext context, String text, int x, int y, int maxWidth, int color)
    {
        List<OrderedText> lines = this.textRenderer.wrapLines(Text.literal(text).setStyle(Style.EMPTY), maxWidth);
        int lineY = y;
        for (OrderedText line : lines)
        {
            context.drawText(this.textRenderer, line, x, lineY, color, false);
            lineY += 10;
        }
    }

    private void drawPill(DrawContext context, int x, int y, int width, int height, String text)
    {
        fillCard(context, x, y, width, height, COLOR_CARD, COLOR_ACCENT);
        context.drawText(this.textRenderer, Text.literal(text), x + (width - this.textRenderer.getWidth(text)) / 2, y + 4, COLOR_ACCENT, false);
    }

    private void drawStatusChip(DrawContext context, int x, int y, String text, int borderColor)
    {
        int width = this.textRenderer.getWidth(text) + 16;
        fillCard(context, x, y, width, 16, COLOR_INSET, borderColor);
        context.drawText(this.textRenderer, Text.literal(text), x + 8, y + 4, COLOR_VALUE, false);
    }

    private void fillCard(DrawContext context, int x, int y, int width, int height, int fillColor, int borderColor)
    {
        context.fill(x, y, x + width, y + height, fillColor);
        context.fill(x + 1, y + 1, x + width - 1, y + 2, COLOR_ACCENT_SOFT);
        context.drawBorder(x, y, width, height, borderColor);
    }

    private void updateWidgetBounds(Layout layout)
    {
        int contentInnerWidth = layout.linkWidth - CARD_PADDING * 2;
        int buttonWidth = (contentInnerWidth - CARD_GAP) / 2;

        if (this.codeField != null)
        {
            this.codeField.setX(layout.linkX + CARD_PADDING);
            this.codeField.setY(layout.linkY + 78);
            this.codeField.setWidth(contentInnerWidth);
        }
        if (this.openButton != null)
        {
            this.openButton.setX(layout.linkX + CARD_PADDING);
            this.openButton.setY(layout.linkY + 108);
            this.openButton.setWidth(buttonWidth);
        }
        if (this.submitButton != null)
        {
            this.submitButton.setX(layout.linkX + CARD_PADDING + buttonWidth + CARD_GAP);
            this.submitButton.setY(layout.linkY + 108);
            this.submitButton.setWidth(buttonWidth);
        }
        if (this.clearButton != null)
        {
            this.clearButton.setX(layout.statusButtonX);
            this.clearButton.setY(layout.statusPrimaryButtonY);
            this.clearButton.setWidth(layout.statusButtonWidth);
        }
        if (this.doneButton != null)
        {
            this.doneButton.setX(layout.statusButtonX);
            this.doneButton.setY(layout.statusSecondaryButtonY);
            this.doneButton.setWidth(layout.statusButtonWidth);
        }
    }

    private void ensureCursorVisible()
    {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.mouse != null)
        {
            client.mouse.unlockCursor();
        }
    }

    private void submitCode()
    {
        WebsiteLinkManager.claimCode(this.codeField.getText());
        refreshState();
    }

    private void clearPersistedLink()
    {
        Configs.websiteLinkedMinecraftUuid = "";
        Configs.websiteLinkedMinecraftUsername = "";
        Configs.websiteLinkedAtMs = 0L;
        Configs.saveToFile();
        WebsiteLinkManager.reset();
        refreshState();
    }

    private void refreshState()
    {
        if (this.submitButton != null)
        {
            this.submitButton.active = this.codeField != null && this.codeField.getText().trim().length() >= 6;
        }
        if (this.clearButton != null)
        {
            this.clearButton.active = WebsiteLinkManager.hasPersistedLink();
        }
    }

    private Layout computeLayout()
    {
        int panelWidth = Math.min(796, Math.max(664, this.width - PANEL_MARGIN * 2));
        int panelHeight = Math.min(408, Math.max(356, this.height - 28));
        int panelX = (this.width - panelWidth) / 2;
        int panelY = (this.height - panelHeight) / 2;
        int contentX = panelX + PANEL_PADDING;
        int contentWidth = panelWidth - PANEL_PADDING * 2;
        int headerY = panelY + PANEL_PADDING;
        int stepsY = headerY + 66;
        int statusWidth = Math.max(222, Math.min(252, contentWidth / 3));
        int stepsWidth = contentWidth - statusWidth - CARD_GAP;
        int stepsHeight = 122;
        int linkY = stepsY + stepsHeight + CARD_GAP;
        int linkHeight = 140;
        int statusX = contentX + stepsWidth + CARD_GAP;
        int statusY = stepsY;
        int statusHeight = stepsHeight + CARD_GAP + linkHeight;
        int statusButtonWidth = statusWidth - CARD_PADDING * 2;
        int statusSecondaryButtonY = statusY + statusHeight - CARD_PADDING - BUTTON_HEIGHT;
        int statusPrimaryButtonY = statusSecondaryButtonY - BUTTON_HEIGHT - BUTTON_ROW_GAP;
        int statusButtonX = statusX + CARD_PADDING;

        return new Layout(
                panelX,
                panelY,
                panelWidth,
                panelHeight,
                contentX,
                contentWidth,
                headerY,
                contentX,
                stepsY,
                stepsWidth,
                stepsHeight,
                statusX,
                statusY,
                statusWidth,
                statusHeight,
                contentX,
                linkY,
                contentWidth - statusWidth - CARD_GAP,
                linkHeight,
                statusButtonX,
                statusButtonWidth,
                statusPrimaryButtonY,
                statusSecondaryButtonY);
    }

    private record Layout(
            int panelX,
            int panelY,
            int panelWidth,
            int panelHeight,
            int contentX,
            int contentWidth,
            int headerY,
            int stepsX,
            int stepsY,
            int stepsWidth,
            int stepsHeight,
            int statusX,
            int statusY,
            int statusWidth,
            int statusHeight,
            int linkX,
            int linkY,
            int linkWidth,
            int linkHeight,
            int statusButtonX,
            int statusButtonWidth,
            int statusPrimaryButtonY,
            int statusSecondaryButtonY)
    {
    }
}
