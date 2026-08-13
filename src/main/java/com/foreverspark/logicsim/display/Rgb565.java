package com.foreverspark.logicsim.display;

/** Utility methods for the 16-bit RGB565 pixel format used by Logic Simulation displays. */
public final class Rgb565 {
    private Rgb565() {}

    public static int normalize(int value) {
        return value & 0xFFFF;
    }

    public static int pack(int red8, int green8, int blue8) {
        int r = clamp8(red8) >> 3;
        int g = clamp8(green8) >> 2;
        int b = clamp8(blue8) >> 3;
        return (r << 11) | (g << 5) | b;
    }

    public static int red8(int rgb565) {
        int r = (normalize(rgb565) >>> 11) & 0x1F;
        return (r << 3) | (r >>> 2);
    }

    public static int green8(int rgb565) {
        int g = (normalize(rgb565) >>> 5) & 0x3F;
        return (g << 2) | (g >>> 4);
    }

    public static int blue8(int rgb565) {
        int b = normalize(rgb565) & 0x1F;
        return (b << 3) | (b >>> 2);
    }

    public static int toArgb(int rgb565) {
        return 0xFF000000 | (red8(rgb565) << 16) | (green8(rgb565) << 8) | blue8(rgb565);
    }

    private static int clamp8(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
