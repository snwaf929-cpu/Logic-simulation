package com.foreverspark.logicsim.client.render;

/** Tiny C2-inlineable primitive used by the fused native-64 RGB writer. */
final class FusedNative64PixelMath {
    private FusedNative64PixelMath() {}

    static int write(char[] framebuffer, int pixelIndex, int rgb565, int nonZeroPixels) {
        int previous = framebuffer[pixelIndex];
        framebuffer[pixelIndex] = (char) rgb565;
        return nonZeroPixels + nonZeroFlag(rgb565) - nonZeroFlag(previous);
    }

    static int nonZeroFlag(int value) {
        return (value | -value) >>> 31;
    }
}
