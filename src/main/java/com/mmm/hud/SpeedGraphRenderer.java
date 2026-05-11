package com.mmm.hud;

import com.mmm.config.Configs;
import com.mmm.tracker.MiningSpeedTracker;
import com.mmm.ui.MmmUi;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

public final class SpeedGraphRenderer
{
    private static final int GRAPH_HEIGHT = 80;
    private static final int GAP = 2;

    private static final int FADE_DELAY_TICKS = 100; // 5 seconds before starting fade
    private static final int FADE_DURATION_TICKS = 40; // 2 seconds to fade to minimum
    private static final float MIN_OPACITY = 0.4f;

    private SpeedGraphRenderer()
    {
    }

    public static void render(DrawContext context, MinecraftClient client)
    {
        int count = MiningSpeedTracker.getHistoryCount();
        if (count == 0) return;

        float scaleStep = Configs.getGraphScaleStep();

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

        float ceiling = (float) Math.ceil(dataMax / scaleStep) * scaleStep;
        float floor = Math.max(0f, (float) Math.floor(dataMin / scaleStep) * scaleStep);
        float minRange = ceiling * 0.05f;
        if (ceiling - floor < minRange)
        {
            floor = Math.max(0f, ceiling - minRange);
        }
        float range = ceiling - floor;

        float opacity = computeOpacity();

        int bgColor = applyOpacity(MmmUi.INSET, opacity);
        context.fill(x, y, x + width, y + GRAPH_HEIGHT, bgColor);

        int baseFillAlpha = Math.round(Configs.getGraphFillOpacity() * 255);
        int fillColor = applyOpacity((baseFillAlpha << 24) | (Configs.getGraphFillColor() & 0x00FFFFFF), opacity);
        int lineColor = applyOpacity(Configs.getGraphLineColor() | 0xFF000000, opacity);

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

            context.fill(colX, colTop, colX + 1, colBottom, fillColor);
            context.fill(colX, colTop, colX + 1, colTop + 1, lineColor);
        }

        renderGridLines(context, client.textRenderer, x, y, width, floor, ceiling, opacity, scaleStep);
    }

    private static float computeOpacity()
    {
        if (MiningSpeedTracker.isActivelyMining())
        {
            return 1.0f;
        }

        int idleTicks = MiningSpeedTracker.getIdleTicks();
        if (idleTicks <= FADE_DELAY_TICKS)
        {
            return 1.0f;
        }

        int fadeProgress = idleTicks - FADE_DELAY_TICKS;
        if (fadeProgress >= FADE_DURATION_TICKS)
        {
            return MIN_OPACITY;
        }

        float t = (float) fadeProgress / FADE_DURATION_TICKS;
        return 1.0f - t * (1.0f - MIN_OPACITY);
    }

    private static int applyOpacity(int argbColor, float opacity)
    {
        int alpha = (argbColor >>> 24) & 0xFF;
        int newAlpha = Math.round(alpha * opacity);
        return (newAlpha << 24) | (argbColor & 0x00FFFFFF);
    }

    private static void renderGridLines(DrawContext context, TextRenderer font,
            int startX, int startY, int width, float floor, float ceiling, float opacity, float scaleStep)
    {
        if (ceiling <= floor) return;

        float scaleRange = ceiling - floor;
        float step = scaleStep;
        while (scaleRange / step > 5)
        {
            step += scaleStep;
        }

        int baseGridAlpha = Math.round(Configs.getGraphGridOpacity() * 255);
        int gridColor = applyOpacity((baseGridAlpha << 24) | (Configs.getGraphGridColor() & 0x00FFFFFF), opacity);
        int labelColor = applyOpacity(MmmUi.MUTED, opacity);

        int steps = Math.round((ceiling - floor) / step);
        for (int i = 0; i <= steps; i++)
        {
            float val = floor + i * step;
            float fraction = (val - floor) / scaleRange;
            int gridY = startY + GRAPH_HEIGHT - (int) (fraction * GRAPH_HEIGHT);
            gridY = Math.max(startY, Math.min(startY + GRAPH_HEIGHT, gridY));

            context.fill(startX, gridY, startX + width, gridY + 1, gridColor);

            String label = Math.round(val) + "";
            int labelX = startX + width + 2;
            context.drawText(font, label, labelX, gridY - 3, labelColor, true);
        }
    }
}
