package com.foreverspark.logicsim.display;

/** Pixel-oriented controller shared by editor circuits and future world display hardware. */
public final class DisplayController {
    private final DisplayFramebuffer framebuffer;
    private boolean previousWrite;
    private boolean previousClear;

    public DisplayController(DisplayFramebuffer framebuffer) {
        if (framebuffer == null) throw new IllegalArgumentException("Framebuffer is required");
        this.framebuffer = framebuffer;
    }

    public DisplayFramebuffer framebuffer() {
        return framebuffer;
    }

    public boolean setPixel(int x, int y, int rgb565) {
        return framebuffer.trySetPixel(x, y, rgb565);
    }

    public void clear(int rgb565) {
        framebuffer.clear(rgb565);
    }

    public void fillRect(int x, int y, int width, int height, int rgb565) {
        framebuffer.fillRect(x, y, width, height, rgb565);
    }

    /** Circuit-facing pins: X[16], Y[16], COLOR[16], WRITE[1], CLEAR[1]. */
    public void drivePins(int x16, int y16, int color16, boolean write, boolean clear) {
        if (clear && !previousClear) framebuffer.clear(Rgb565.normalize(color16));
        if (write && !previousWrite) framebuffer.trySetPixel(x16 & 0xFFFF, y16 & 0xFFFF, color16);
        previousWrite = write;
        previousClear = clear;
    }

    public void resetEdges() {
        previousWrite = false;
        previousClear = false;
    }
}
