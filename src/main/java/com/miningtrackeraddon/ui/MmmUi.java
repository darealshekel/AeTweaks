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
    public static final int OVERLAY = 0xF2030305;
    public static final int PANEL = 0xF507080B;
    public static final int CARD = 0xE80A0B10;
    public static final int CARD_SOFT = 0xD806070A;
    public static final int INSET = 0xD0020305;
    public static final int BORDER = 0xB01E232B;
    public static final int BORDER_SOFT = 0x66303945;
    public static final int ACCENT = 0xFFFF1E2D;
    public static final int ACCENT_BRIGHT = 0xFFFF4A55;
    public static final int ACCENT_SOFT = 0x4A320007;
    public static final int ACCENT_ROW = 0x4A210006;
    public static final int TEXT = 0xFFF6F3EF;
    public static final int LABEL = 0xD8C9CDD5;
    public static final int MUTED = 0x9A828893;
    public static final int SUCCESS = 0xFF43D483;
    public static final int WARNING = 0xFFFFC857;
    public static final int ERROR = 0xFFFF5965;
    public static final int BLUE = 0xFF5FB8FF;
    public static final int ROW_SELECTED = 0x5C260007;
    public static final int ROW_HOVER = 0x381E080D;
    public static final int ROW_ALT = 0x1E0A0B10;
    public static final int GRAPH_FILL = 0xBFFF1E2D;
    public static final int GRAPH_GRID = 0x28FF1E2D;
    public static final int SCROLLBAR_TRACK = 0x44101014;
    public static final int SCROLLBAR_THUMB = 0xFF9D2430;
    public static final int SCROLLBAR_THUMB_HOVER = 0xFFFF3A47;
    public static final int SCROLLBAR_THUMB_ACTIVE = 0xFFFF6770;

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

        int gridColor = 0x16FF1E2D;
        for (int x = 0; x < width; x += 24)
        {
            context.fill(x, 0, x + 1, height, gridColor);
        }
        for (int y = 0; y < height; y += 24)
        {
            context.fill(0, y, width, y + 1, gridColor);
        }
    }

    public static void card(DrawContext context, int x, int y, int width, int height, int fillColor, int borderColor)
    {
        context.fill(x, y, x + width, y + height, fillColor);
        context.fill(x + 1, y + 1, x + width - 1, y + 2, ACCENT_SOFT);
        context.drawBorder(x, y, width, height, borderColor);
    }

    public static void fieldShell(DrawContext context, int x, int y, int width, int height, boolean focused)
    {
        card(context, x, y, width, height, INSET, focused ? ACCENT : BORDER_SOFT);
    }

    public static void pill(DrawContext context, TextRenderer renderer, int x, int y, int width, int height, String text)
    {
        card(context, x, y, width, height, CARD, ACCENT);
        int textX = x + Math.max(4, (width - renderer.getWidth(text)) / 2);
        context.drawText(renderer, Text.literal(text), textX, y + 4, ACCENT_BRIGHT, false);
    }

    public static void statusChip(DrawContext context, TextRenderer renderer, int x, int y, String text, int borderColor)
    {
        int width = renderer.getWidth(text) + 14;
        card(context, x, y, width, 16, INSET, borderColor);
        context.drawText(renderer, Text.literal(text), x + 7, y + 4, TEXT, false);
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

    public static String truncate(TextRenderer renderer, String value, int maxWidth)
    {
        if (value == null)
        {
            return "";
        }
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
}
