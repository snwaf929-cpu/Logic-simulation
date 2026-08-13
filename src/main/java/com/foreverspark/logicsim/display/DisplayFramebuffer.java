package com.foreverspark.logicsim.display;

import java.util.Arrays;

public final class DisplayFramebuffer {
    private final int width;
    private final int height;
    private final int[] pixels;

    public DisplayFramebuffer(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Display size must be positive");
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
    }

    public int width() { return width; }
    public int height() { return height; }

    public boolean writePixel(int x, int y, int rgb565) {
        if (x < 0 || y < 0 || x >= width || y >= height) return false;
        pixels[y * width + x] = rgb565 & 0xFFFF;
        return true;
    }

    public void clear(int rgb565) { Arrays.fill(pixels, rgb565 & 0xFFFF); }
    public int pixelRgb565(int x, int y) { return (x < 0 || y < 0 || x >= width || y >= height) ? 0 : pixels[y * width + x]; }
    public int pixelArgb(int x, int y) { return rgb565ToArgb(pixelRgb565(x, y)); }

    public static int rgb565ToArgb(int value) {
        value &= 0xFFFF;
        int r5 = (value >>> 11) & 31, g6 = (value >>> 5) & 63, b5 = value & 31;
        int r = (r5 << 3) | (r5 >>> 2);
        int g = (g6 << 2) | (g6 >>> 4);
        int b = (b5 << 3) | (b5 >>> 2);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
