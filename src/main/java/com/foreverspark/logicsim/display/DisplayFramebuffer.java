package com.foreverspark.logicsim.display;

import java.util.Arrays;

public final class DisplayFramebuffer {
    private final int width;
    private final int height;
    private final int[] pixels;
    private long revision;
    private int nonZeroPixels;
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

    /** O(1) fast path used by the renderer so a powered-off/black display submits no custom geometry at all. */
    public boolean isBlack() { return nonZeroPixels == 0; }
    public int nonZeroPixelCount() { return nonZeroPixels; }

    public boolean writePixel(int x, int y, int rgb565) {
        if (x < 0 || y < 0 || x >= width || y >= height) return false;
        int index = y * width + x;
        int normalized = rgb565 & 0xFFFF;
        int previous = pixels[index];
        if (previous == normalized) return true;
        pixels[index] = normalized;
        if (previous == 0 && normalized != 0) nonZeroPixels++;
        else if (previous != 0 && normalized == 0) nonZeroPixels--;
        revision++;
        markDirty(x, y, x, y);
        return true;
    }

    /** Fills an inclusive rectangle and records it as one framebuffer revision. */
    public boolean fillRect(int minX, int minY, int maxX, int maxY, int rgb565) {
        if (minX < 0 || minY < 0 || maxX < minX || maxY < minY || maxX >= width || maxY >= height) return false;
        int normalized = rgb565 & 0xFFFF;
        boolean changed = false;
        for (int y = minY; y <= maxY; y++) {
            int row = y * width;
            for (int x = minX; x <= maxX; x++) {
                int index = row + x;
                int previous = pixels[index];
                if (previous == normalized) continue;
                pixels[index] = normalized;
                if (previous == 0 && normalized != 0) nonZeroPixels++;
                else if (previous != 0 && normalized == 0) nonZeroPixels--;
                changed = true;
            }
        }
        if (changed) {
            revision++;
            markDirty(minX, minY, maxX, maxY);
        }
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
        nonZeroPixels = normalized == 0 ? 0 : pixels.length;
        revision++;
        markDirty(0, 0, width - 1, height - 1);
    }

    public int pixelRgb565(int x, int y) {
        return (x < 0 || y < 0 || x >= width || y >= height) ? 0 : pixels[y * width + x];
    }

    public int pixelArgb(int x, int y) { return rgb565ToArgb(pixelRgb565(x, y)); }

    /**
     * Compact persistence/network representation: two RGB565 pixels per int. A 64x64 tile therefore needs only
     * 2048 ints (8 KiB) instead of thousands of separately named NBT entries.
     */
    public int[] packedRgb565() {
        int[] packed = new int[(pixels.length + 1) >>> 1];
        for (int pixel = 0, out = 0; pixel < pixels.length; pixel += 2, out++) {
            int low = pixels[pixel] & 0xFFFF;
            int high = pixel + 1 < pixels.length ? (pixels[pixel + 1] & 0xFFFF) : 0;
            packed[out] = low | (high << 16);
        }
        return packed;
    }

    /**
     * Loads a compact snapshot in one pass and advances revision exactly once. This is intentionally authoritative:
     * client block-entity packets may replace the whole framebuffer and render caches must notice even when the
     * resulting pixel count happens to match the previous frame.
     */
    public void loadPackedRgb565(int[] packed) {
        Arrays.fill(pixels, 0);
        nonZeroPixels = 0;
        if (packed != null) {
            int count = Math.min(packed.length, (pixels.length + 1) >>> 1);
            for (int in = 0, pixel = 0; in < count && pixel < pixels.length; in++) {
                int pair = packed[in];
                int low = pair & 0xFFFF;
                pixels[pixel++] = low;
                if (low != 0) nonZeroPixels++;
                if (pixel < pixels.length) {
                    int high = (pair >>> 16) & 0xFFFF;
                    pixels[pixel++] = high;
                    if (high != 0) nonZeroPixels++;
                }
            }
        }
        revision++;
        markDirty(0, 0, width - 1, height - 1);
    }

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
