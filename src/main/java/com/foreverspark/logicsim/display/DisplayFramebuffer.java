package com.foreverspark.logicsim.display;

import java.util.Arrays;

/**
 * Dense RGB565 framebuffer owned by a simulated display controller.
 *
 * The framebuffer is deliberately independent from Minecraft rendering so the same state can
 * be driven by editor circuits, world devices, networking, tests, and future GPU hardware.
 */
public final class DisplayFramebuffer {
    public static final int MAX_DIMENSION = 4096;
    public static final int MAX_PIXELS = 4_194_304;

    private final int width;
    private final int height;
    private final short[] pixels;

    private long revision;
    private int dirtyMinX;
    private int dirtyMinY;
    private int dirtyMaxX;
    private int dirtyMaxY;
    private boolean dirty;

    public DisplayFramebuffer(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Display dimensions must be positive");
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("Display dimensions cannot exceed " + MAX_DIMENSION + "x" + MAX_DIMENSION);
        }
        long count = (long) width * height;
        if (count > MAX_PIXELS) {
            throw new IllegalArgumentException("Display framebuffer cannot exceed " + MAX_PIXELS + " pixels");
        }
        this.width = width;
        this.height = height;
        this.pixels = new short[(int) count];
        clearDirty();
    }

    public int width() { return width; }
    public int height() { return height; }
    public int pixelCount() { return pixels.length; }
    public long revision() { return revision; }

    public int getPixel(int x, int y) {
        checkCoordinates(x, y);
        return pixels[index(x, y)] & 0xFFFF;
    }

    /** Returns false when the pixel was outside the framebuffer and no write was performed. */
    public boolean trySetPixel(int x, int y, int rgb565) {
        if (!contains(x, y)) return false;
        int index = index(x, y);
        short next = (short) Rgb565.normalize(rgb565);
        if (pixels[index] == next) return true;
        pixels[index] = next;
        revision++;
        markDirty(x, y, x + 1, y + 1);
        return true;
    }

    public void setPixel(int x, int y, int rgb565) {
        checkCoordinates(x, y);
        trySetPixel(x, y, rgb565);
    }

    public void clear(int rgb565) {
        short next = (short) Rgb565.normalize(rgb565);
        boolean changed = false;
        for (short pixel : pixels) {
            if (pixel != next) {
                changed = true;
                break;
            }
        }
        if (!changed) return;
        Arrays.fill(pixels, next);
        revision++;
        markDirty(0, 0, width, height);
    }

    /** Clips the rectangle to the framebuffer. Empty/outside rectangles are ignored. */
    public void fillRect(int x, int y, int rectWidth, int rectHeight, int rgb565) {
        if (rectWidth <= 0 || rectHeight <= 0) return;
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(width, x + rectWidth);
        int y1 = Math.min(height, y + rectHeight);
        if (x0 >= x1 || y0 >= y1) return;

        short next = (short) Rgb565.normalize(rgb565);
        boolean changed = false;
        for (int py = y0; py < y1; py++) {
            int row = py * width;
            for (int px = x0; px < x1; px++) {
                int index = row + px;
                if (pixels[index] != next) {
                    pixels[index] = next;
                    changed = true;
                }
            }
        }
        if (changed) {
            revision++;
            markDirty(x0, y0, x1, y1);
        }
    }

    public short[] snapshotRgb565() {
        return pixels.clone();
    }

    public int[] snapshotArgb() {
        int[] result = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) result[i] = Rgb565.toArgb(pixels[i] & 0xFFFF);
        return result;
    }

    public DirtyRect peekDirtyRect() {
        return dirty ? new DirtyRect(dirtyMinX, dirtyMinY, dirtyMaxX, dirtyMaxY) : null;
    }

    public DirtyRect consumeDirtyRect() {
        DirtyRect result = peekDirtyRect();
        clearDirty();
        return result;
    }

    public boolean contains(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }

    private void checkCoordinates(int x, int y) {
        if (!contains(x, y)) throw new IndexOutOfBoundsException("Pixel outside framebuffer: " + x + "," + y);
    }

    private int index(int x, int y) {
        return y * width + x;
    }

    private void markDirty(int x0, int y0, int x1, int y1) {
        if (!dirty) {
            dirty = true;
            dirtyMinX = x0;
            dirtyMinY = y0;
            dirtyMaxX = x1;
            dirtyMaxY = y1;
            return;
        }
        dirtyMinX = Math.min(dirtyMinX, x0);
        dirtyMinY = Math.min(dirtyMinY, y0);
        dirtyMaxX = Math.max(dirtyMaxX, x1);
        dirtyMaxY = Math.max(dirtyMaxY, y1);
    }

    private void clearDirty() {
        dirty = false;
        dirtyMinX = dirtyMinY = dirtyMaxX = dirtyMaxY = 0;
    }

    public record DirtyRect(int minX, int minY, int maxXExclusive, int maxYExclusive) {
        public int width() { return maxXExclusive - minX; }
        public int height() { return maxYExclusive - minY; }
    }
}
