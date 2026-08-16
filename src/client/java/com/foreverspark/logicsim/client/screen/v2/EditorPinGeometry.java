package com.foreverspark.logicsim.client.screen.v2;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Shared visible/hit-test geometry for Editor V2 signal and bus terminals. */
public final class EditorPinGeometry {
    /** Render/input both run on the client UI thread. Thread-local state prevents other threads from inheriting canvas LOD. */
    private static final ThreadLocal<Double> CANVAS_ZOOM = ThreadLocal.withInitial(() -> 1.0);

    private EditorPinGeometry() {}

    /** Called by the canvas before rendering so every legacy/specialized pin renderer shares one zoom LOD policy. */
    public static void setCanvasZoom(double zoom) {
        CANVAS_ZOOM.set(Double.isFinite(zoom) ? Math.max(0.20, Math.min(4.0, zoom)) : 1.0);
    }

    public static double canvasZoom() {
        return CANVAS_ZOOM.get();
    }

    public static int halfSize(int width) {
        int base;
        if (width <= 1) base = 3;
        else if (width <= 4) base = 4;
        else if (width <= 16) base = 5;
        else base = 6;

        double zoom = canvasZoom();
        if (zoom < 0.45) return Math.max(2, base - 2);
        if (zoom < 0.72) return Math.max(2, base - 1);
        if (zoom > 1.55) return Math.min(7, base + 1);
        return base;
    }

    public static int chamfer(int width) {
        int base = width >= 32 ? 2 : 1;
        return halfSize(width) <= 2 ? 1 : base;
    }

    public static boolean contains(double dx, double dy, int width) {
        int visualHalf = halfSize(width);
        // Low-zoom pins draw smaller to reduce clutter but retain a minimum comfortable pointer target.
        int half = Math.max(4, visualHalf + 1);
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        if (ax > half || ay > half) return false;
        if (width <= 1) return true;
        return ax + ay <= half * 2.0 - Math.min(chamfer(width), half - 1);
    }

    public static void draw(GuiGraphicsExtractor graphics, int x, int y, int width, int color) {
        int half = halfSize(width);
        if (width <= 1) {
            graphics.fill(x - half - 1, y - half - 1, x + half + 2, y + half + 2, 0xFF090B0D);
            graphics.fill(x - half, y - half, x + half + 1, y + half + 1, color);
            return;
        }
        fillChamfered(graphics, x, y, half + 1, chamfer(width) + 1, 0xFF090B0D);
        fillChamfered(graphics, x, y, half, chamfer(width), color);
    }

    private static void fillChamfered(GuiGraphicsExtractor graphics, int cx, int cy, int half, int chamfer, int color) {
        for (int dy = -half; dy <= half; dy++) {
            int inset = Math.max(0, Math.abs(dy) - (half - chamfer));
            graphics.fill(cx - half + inset, cy + dy, cx + half - inset + 1, cy + dy + 1, color);
        }
    }
}
