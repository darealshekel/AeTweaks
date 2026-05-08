package com.miningtrackeraddon.ui;

import java.util.List;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

public final class MmmUi
{
    public static final int BLACK = 0xFF1F1F1F;
    public static final int RED = 0xFFE00000;
    public static final int OVERLAY = 0xCC050505;
    public static final int PANEL = BLACK;
    public static final int CARD = 0xE6090909;
    public static final int CARD_SOFT = CARD;
    public static final int INSET = 0xB8050505;
    public static final int BORDER = RED;
    public static final int BORDER_SOFT = 0x99E00000;
    public static final int ACCENT = RED;
    public static final int ACCENT_BRIGHT = RED;
    public static final int ACCENT_SOFT = 0x33E00000;
    public static final int ACCENT_ROW = 0x3DE00000;
    public static final int TEXT = 0xFFF6F3EF;
    public static final int LABEL = 0xD8C9CDD5;
    public static final int MUTED = 0x9A828893;
    public static final int SUCCESS = 0xFF43D483;
    public static final int WARNING = 0xFFFFC857;
    public static final int ERROR = 0xFFFF5965;
    public static final int BLUE = RED;
    public static final int ROW_SELECTED = 0x52E00000;
    public static final int ROW_HOVER = 0x26E00000;
    public static final int ROW_ALT = 0x24101010;
    public static final int GRAPH_FILL = 0xBFE00000;
    public static final int GRAPH_GRID = 0x28E00000;
    public static final int SCROLLBAR_TRACK = 0x44090909;
    public static final int SCROLLBAR_THUMB = RED;
    public static final int SCROLLBAR_THUMB_HOVER = RED;
    public static final int SCROLLBAR_THUMB_ACTIVE = RED;

    private MmmUi()
    {
    }

    public static void ensureCursorVisible()
    {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.mouse != null)
        {
            client.mouse.unlockCursor();
        }
    }

    public static void backdrop(DrawContext context, int width, int height)
    {
        context.fill(0, 0, width, height, OVERLAY);
    }

    public static void card(DrawContext context, int x, int y, int width, int height, int fillColor, int borderColor)
    {
        context.fill(x, y, x + width, y + height, fillColor);
        context.drawBorder(x, y, width, height, borderColor);
    }

    public static void fieldShell(DrawContext context, int x, int y, int width, int height, boolean focused)
    {
        card(context, x, y, width, height, INSET, focused ? ACCENT : BORDER_SOFT);
    }

    public static void pill(DrawContext context, TextRenderer renderer, int x, int y, int width, int height, String text)
    {
        card(context, x, y, width, height, CARD, ACCENT);
        String clipped = truncate(renderer, text, width - 8);
        int textX = x + Math.max(4, (width - renderer.getWidth(clipped)) / 2);
        context.drawText(renderer, Text.literal(clipped), textX, y + 4, ACCENT_BRIGHT, false);
    }

    public static void statusChip(DrawContext context, TextRenderer renderer, int x, int y, String text, int borderColor)
    {
        int width = renderer.getWidth(text) + 14;
        card(context, x, y, width, 16, INSET, borderColor);
        drawTextWithin(context, renderer, text, x + 7, y + 4, width - 14, TEXT, false);
    }

    public static void wrappedText(DrawContext context, TextRenderer renderer, String text, int x, int y, int maxWidth, int color)
    {
        List<OrderedText> lines = renderer.wrapLines(Text.literal(text).setStyle(Style.EMPTY), maxWidth);
        int lineY = y;
        for (OrderedText line : lines)
        {
            context.drawText(renderer, line, x, lineY, color, false);
            lineY += 10;
        }
    }

    public static void drawTextWithin(DrawContext context, TextRenderer renderer, String value, int x, int y, int maxWidth, int color, boolean shadow)
    {
        if (maxWidth <= 0)
        {
            return;
        }

        context.drawText(renderer, Text.literal(truncate(renderer, value, maxWidth)), x, y, color, shadow);
    }

    public static void drawTextRightWithin(DrawContext context, TextRenderer renderer, String value, int rightX, int y, int maxWidth, int color, boolean shadow)
    {
        if (maxWidth <= 0)
        {
            return;
        }

        String clipped = truncate(renderer, value, maxWidth);
        context.drawText(renderer, Text.literal(clipped), rightX - renderer.getWidth(clipped), y, color, shadow);
    }

    public static String truncate(TextRenderer renderer, String value, int maxWidth)
    {
        if (value == null)
        {
            return "";
        }
        if (maxWidth <= 0)
        {
            return "";
        }
        if (renderer.getWidth(value) <= maxWidth)
        {
            return value;
        }

        String ellipsis = "...";
        if (renderer.getWidth(ellipsis) > maxWidth)
        {
            return "";
        }

        String trimmed = value;
        while (trimmed.length() > 1 && renderer.getWidth(trimmed + ellipsis) > maxWidth)
        {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (renderer.getWidth(trimmed + ellipsis) > maxWidth)
        {
            return ellipsis;
        }
        return trimmed + ellipsis;
    }
}
