package com.foreverspark.logicsim.display;

import java.util.Arrays;

public final class DisplayFramebuffer {
    private final int width;
    private final int height;
    private final int[] pixels;
    private long revision;
    private int dirtyMinX;
    private int dirtyMinY;
    private int dirtyMaxX;
    private int dirtyMaxY;

    public DisplayFramebuffer(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Display size must be positive");
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
        clearDirty();
    }

    public int width() { return width; }
    public int height() { return height; }
    public long revision() { return revision; }

    public boolean writePixel(int x, int y, int rgb565) {
        if (x < 0 || y < 0 || x >= width || y >= height) return false;
        int index = y * width + x;
        int normalized = rgb565 & 0xFFFF;
        if (pixels[index] == normalized) return true;
        pixels[index] = normalized;
        revision++;
        markDirty(x, y, x, y);
        return true;
    }

    public void clear(int rgb565) {
        int normalized = rgb565 & 0xFFFF;
        boolean changed = false;
        for (int pixel : pixels) {
            if (pixel != normalized) {
                changed = true;
                break;
            }
        }
        if (!changed) return;
        Arrays.fill(pixels, normalized);
        revision++;
        markDirty(0, 0, width - 1, height - 1);
    }

    public int pixelRgb565(int x, int y) {
        return (x < 0 || y < 0 || x >= width || y >= height) ? 0 : pixels[y * width + x];
    }

    public int pixelArgb(int x, int y) { return rgb565ToArgb(pixelRgb565(x, y)); }

    public DirtyRegion consumeDirtyRegion() {
        if (dirtyMaxX < dirtyMinX || dirtyMaxY < dirtyMinY) return null;
        DirtyRegion region = new DirtyRegion(dirtyMinX, dirtyMinY, dirtyMaxX, dirtyMaxY, revision);
        clearDirty();
        return region;
    }

    public void markAllDirty() {
        markDirty(0, 0, width - 1, height - 1);
    }

    private void markDirty(int minX, int minY, int maxX, int maxY) {
        dirtyMinX = Math.min(dirtyMinX, minX);
        dirtyMinY = Math.min(dirtyMinY, minY);
        dirtyMaxX = Math.max(dirtyMaxX, maxX);
        dirtyMaxY = Math.max(dirtyMaxY, maxY);
    }

    private void clearDirty() {
        dirtyMinX = width;
        dirtyMinY = height;
        dirtyMaxX = -1;
        dirtyMaxY = -1;
    }

    public static int rgb565ToArgb(int value) {
        value &= 0xFFFF;
        int r5 = (value >>> 11) & 31, g6 = (value >>> 5) & 63, b5 = value & 31;
        int r = (r5 << 3) | (r5 >>> 2);
        int g = (g6 << 2) | (g6 >>> 4);
        int b = (b5 << 3) | (b5 >>> 2);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    public record DirtyRegion(int minX, int minY, int maxX, int maxY, long revision) {
        public int width() { return maxX - minX + 1; }
        public int height() { return maxY - minY + 1; }
    }
}
