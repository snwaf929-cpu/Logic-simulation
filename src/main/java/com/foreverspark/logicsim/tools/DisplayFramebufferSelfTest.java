package com.foreverspark.logicsim.tools;

import com.foreverspark.logicsim.display.DisplayFramebuffer;

public final class DisplayFramebufferSelfTest {
    private DisplayFramebufferSelfTest() {}

    public static void main(String[] args) {
        DisplayFramebuffer fb = new DisplayFramebuffer(32, 18);
        check(fb.pixelRgb565(0, 0) == 0, "new framebuffer starts black");
        check(fb.writePixel(7, 5, 0xF800), "pixel write accepted");
        check(fb.pixelRgb565(7, 5) == 0xF800, "red RGB565 stored");
        check(fb.pixelArgb(7, 5) == 0xFFFF0000, "RGB565 red converts to ARGB red");
        check(fb.writePixel(31, 17, 0x07E0), "last pixel write accepted");
        check((fb.pixelArgb(31, 17) & 0x00FFFFFF) == 0x0000FF00, "green conversion");
        check(!fb.writePixel(32, 18, 0xFFFF), "out of range is clipped");
        fb.clear(0x001F);
        check((fb.pixelArgb(7, 5) & 0x00FFFFFF) == 0x000000FF, "clear fills blue");
        check((fb.pixelArgb(31, 17) & 0x00FFFFFF) == 0x000000FF, "clear reaches whole framebuffer");
        System.out.println("Display framebuffer self-test: PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
