package com.mmm.hud;

import com.mmm.tracker.MiningSpeedTracker;
import com.mmm.ui.MmmUi;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

public final class SpeedGraphRenderer
{
    private static final int GRAPH_HEIGHT = 30;
    private static final int GAP = 2;
    private static final float SCALE_STEP = 300f;
    private static final int LINE_COLOR = 0xFFE00000;
    private static final int FILL_COLOR = MmmUi.GRAPH_FILL;
    private static final int GRID_COLOR = MmmUi.GRAPH_GRID;

    private SpeedGraphRenderer()
    {
    }

    public static void render(DrawContext context, MinecraftClient client)
    {
        int count = MiningSpeedTracker.getHistoryCount();
        if (count == 0) return;

        int[] hudBounds = MiningHudRenderer.getBounds(client);
        int x = hudBounds[0];
        int y = hudBounds[3] + GAP;
        int width = hudBounds[2] - hudBounds[0];

        float[] history = MiningSpeedTracker.getSpeedHistory();
        int writeIdx = MiningSpeedTracker.getHistoryIndex();
        int bufferSize = history.length;
        int readStart = (writeIdx - count + bufferSize) % bufferSize;

        float dataMax = 0f;
        float dataMin = Float.MAX_VALUE;
        for (int i = 0; i < count; i++)
        {
            float v = history[(readStart + i) % bufferSize];
            if (v > dataMax) dataMax = v;
            if (v < dataMin) dataMin = v;
        }
        if (dataMin == Float.MAX_VALUE) dataMin = 0f;
        if (dataMax <= 0f) return;

        float ceiling = (float) Math.ceil(dataMax / SCALE_STEP) * SCALE_STEP;
        float floor = Math.max(0f, (float) Math.floor(dataMin / SCALE_STEP) * SCALE_STEP);
        float minRange = ceiling * 0.05f;
        if (ceiling - floor < minRange)
        {
            floor = Math.max(0f, ceiling - minRange);
        }
        float range = ceiling - floor;

        context.fill(x, y, x + width, y + GRAPH_HEIGHT, MmmUi.INSET);

        for (int col = 0; col < width; col++)
        {
            int entryIdx = (int) ((long) col * count / width);
            int bufIdx = (readStart + entryIdx) % bufferSize;
            float value = history[bufIdx];
            int colHeight = (range > 0f)
                ? (int) ((value - floor) / range * GRAPH_HEIGHT)
                : 0;
            colHeight = Math.max(0, Math.min(GRAPH_HEIGHT, colHeight));
            if (colHeight <= 0) continue;

            int colX = x + col;
            int colBottom = y + GRAPH_HEIGHT;
            int colTop = colBottom - colHeight;

            context.fill(colX, colTop, colX + 1, colBottom, FILL_COLOR);
            context.fill(colX, colTop, colX + 1, colTop + 1, LINE_COLOR);
        }

        renderGridLines(context, client.textRenderer, x, y, width, floor, ceiling);
    }

    private static void renderGridLines(DrawContext context, TextRenderer font,
            int startX, int startY, int width, float floor, float ceiling)
    {
        if (ceiling <= floor) return;

        float scaleRange = ceiling - floor;
        float step = SCALE_STEP;
        while (scaleRange / step > 4)
        {
            step += SCALE_STEP;
        }

        int steps = Math.round((ceiling - floor) / step);
        for (int i = 0; i <= steps; i++)
        {
            float val = floor + i * step;
            float fraction = (val - floor) / scaleRange;
            int gridY = startY + GRAPH_HEIGHT - (int) (fraction * GRAPH_HEIGHT);
            gridY = Math.max(startY, Math.min(startY + GRAPH_HEIGHT, gridY));

            context.fill(startX, gridY, startX + width, gridY + 1, GRID_COLOR);

            String label = Math.round(val) + "";
            int labelX = startX + width + 2;
            context.drawText(font, label, labelX, gridY - 3, MmmUi.MUTED, true);
        }
    }
}
