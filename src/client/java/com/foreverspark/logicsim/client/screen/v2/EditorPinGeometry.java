package com.foreverspark.logicsim.client.screen.v2;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Shared visible/hit-test geometry for Editor V2 signal and bus terminals. */
public final class EditorPinGeometry {
    private EditorPinGeometry() {}

    public static int halfSize(int width) {
        if (width <= 1) return 3;
        if (width <= 4) return 4;
        if (width <= 16) return 5;
        return 6;
    }

    public static int chamfer(int width) {
        return width >= 32 ? 2 : 1;
    }

    public static boolean contains(double dx, double dy, int width) {
        int half = halfSize(width);
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        if (ax > half || ay > half) return false;
        if (width <= 1) return true;
        return ax + ay <= half * 2.0 - chamfer(width);
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
