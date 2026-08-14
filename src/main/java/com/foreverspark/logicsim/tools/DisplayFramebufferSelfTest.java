package com.foreverspark.logicsim.tools;

import com.foreverspark.logicsim.display.DisplayFramebuffer;

public final class DisplayFramebufferSelfTest {
    private DisplayFramebufferSelfTest() {}

    public static void main(String[] args) {
        DisplayFramebuffer fb = new DisplayFramebuffer(32, 18);
        check(fb.pixelRgb565(0, 0) == 0, "new framebuffer starts black");
        check(fb.isBlack(), "new framebuffer blank fast path");
        check(fb.nonZeroPixelCount() == 0, "new framebuffer has zero lit backing pixels");

        check(fb.writePixel(7, 5, 0xF800), "pixel write accepted");
        check(!fb.isBlack(), "non-black pixel marks framebuffer lit");
        check(fb.nonZeroPixelCount() == 1, "lit counter increments once");
        check(fb.pixelRgb565(7, 5) == 0xF800, "red RGB565 stored");
        check(fb.pixelArgb(7, 5) == 0xFFFF0000, "RGB565 red converts to ARGB red");

        check(fb.writePixel(31, 17, 0x07E0), "last pixel write accepted");
        check(fb.nonZeroPixelCount() == 2, "second lit pixel increments counter");
        check((fb.pixelArgb(31, 17) & 0x00FFFFFF) == 0x0000FF00, "green conversion");
        check(!fb.writePixel(32, 18, 0xFFFF), "out of range is clipped");

        check(fb.fillRect(0, 0, 1, 1, 0xFFFF), "rectangle fill accepted");
        check(fb.nonZeroPixelCount() == 6, "rectangle fill updates lit counter");
        check(fb.fillRect(0, 0, 1, 1, 0), "black rectangle fill accepted");
        check(fb.nonZeroPixelCount() == 2, "black rectangle fill decrements lit counter");

        // Compact snapshot packs two RGB565 pixels into one int and must restore state exactly.
        int[] packed = fb.packedRgb565();
        check(packed.length == (32 * 18 + 1) / 2, "packed snapshot is two pixels per int");
        DisplayFramebuffer restored = new DisplayFramebuffer(32, 18);
        restored.loadPackedRgb565(packed);
        check(restored.pixelRgb565(7, 5) == 0xF800, "packed snapshot restores red pixel");
        check(restored.pixelRgb565(31, 17) == 0x07E0, "packed snapshot restores green pixel");
        check(restored.nonZeroPixelCount() == 2, "packed snapshot restores lit counter");

        fb.clear(0);
        check(fb.isBlack(), "black clear restores blank fast path");
        check(fb.nonZeroPixelCount() == 0, "black clear resets lit counter");

        fb.clear(0x001F);
        check(!fb.isBlack(), "non-black clear marks entire framebuffer lit");
        check(fb.nonZeroPixelCount() == 32 * 18, "non-black clear counts every backing pixel");
        check((fb.pixelArgb(7, 5) & 0x00FFFFFF) == 0x000000FF, "clear fills blue");
        check((fb.pixelArgb(31, 17) & 0x00FFFFFF) == 0x000000FF, "clear reaches whole framebuffer");
        System.out.println("Display framebuffer self-test: PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
