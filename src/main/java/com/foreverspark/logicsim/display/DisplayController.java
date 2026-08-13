package com.foreverspark.logicsim.display;

/**
 * Hardware-facing display controller. X/Y/COLOR are sampled on a rising WRITE edge.
 * CLEAR is also edge-triggered so holding either control high does not repeat work.
 */
public final class DisplayController {
    private final DisplayFramebuffer framebuffer;
    private boolean lastWrite;
    private boolean lastClear;
    private long writeCount;

    public DisplayController(int width, int height) {
        this.framebuffer = new DisplayFramebuffer(width, height);
    }

    public DisplayFramebuffer framebuffer() { return framebuffer; }
    public long writeCount() { return writeCount; }

    public void sample(int x, int y, int rgb565, boolean write, boolean clear) {
        if (clear && !lastClear) framebuffer.clear(0);
        if (write && !lastWrite && framebuffer.writePixel(x, y, rgb565)) writeCount++;
        lastWrite = write;
        lastClear = clear;
    }

    public void resetEdges() {
        lastWrite = false;
        lastClear = false;
    }
}
