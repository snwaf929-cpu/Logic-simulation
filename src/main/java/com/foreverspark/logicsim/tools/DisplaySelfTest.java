package com.foreverspark.logicsim.tools;

import com.foreverspark.logicsim.display.DisplayController;
import com.foreverspark.logicsim.display.DisplayFramebuffer;
import com.foreverspark.logicsim.display.Rgb565;

public final class DisplaySelfTest {
    private DisplaySelfTest() {}

    public static void main(String[] args) {
        testRgb565();
        testIndividualPixelWrites();
        testFillAndDirtyRegion();
        testCircuitPinProtocol();
        System.out.println("Display framebuffer/controller self-test: PASS");
    }

    private static void testRgb565() {
        check(Rgb565.pack(255, 0, 0) == 0xF800, "red RGB565");
        check(Rgb565.pack(0, 255, 0) == 0x07E0, "green RGB565");
        check(Rgb565.pack(0, 0, 255) == 0x001F, "blue RGB565");
        check(Rgb565.toArgb(0xFFFF) == 0xFFFFFFFF, "white ARGB conversion");
    }

    private static void testIndividualPixelWrites() {
        DisplayFramebuffer fb = new DisplayFramebuffer(16, 16);
        fb.setPixel(3, 4, 0xF800);
        fb.setPixel(15, 15, 0x07E0);
        check(fb.getPixel(3, 4) == 0xF800, "pixel 3,4");
        check(fb.getPixel(15, 15) == 0x07E0, "pixel 15,15");
        check(!fb.trySetPixel(16, 0, 0xFFFF), "out of bounds write rejected");
    }

    private static void testFillAndDirtyRegion() {
        DisplayFramebuffer fb = new DisplayFramebuffer(8, 8);
        fb.fillRect(2, 3, 4, 2, 0x001F);
        check(fb.getPixel(2, 3) == 0x001F, "fill start");
        check(fb.getPixel(5, 4) == 0x001F, "fill end");
        DisplayFramebuffer.DirtyRect dirty = fb.consumeDirtyRect();
        check(dirty != null, "dirty rect exists");
        check(dirty.minX() == 2 && dirty.minY() == 3, "dirty rect origin");
        check(dirty.width() == 4 && dirty.height() == 2, "dirty rect size");
        check(fb.peekDirtyRect() == null, "dirty rect consumed");
    }

    private static void testCircuitPinProtocol() {
        DisplayController controller = new DisplayController(new DisplayFramebuffer(32, 32));
        controller.drivePins(7, 9, 0xF81F, true, false);
        check(controller.framebuffer().getPixel(7, 9) == 0xF81F, "WRITE rising edge sets pixel");

        controller.drivePins(8, 9, 0x07E0, true, false);
        check(controller.framebuffer().getPixel(8, 9) == 0, "holding WRITE high does not write repeatedly");

        controller.drivePins(8, 9, 0x07E0, false, false);
        controller.drivePins(8, 9, 0x07E0, true, false);
        check(controller.framebuffer().getPixel(8, 9) == 0x07E0, "second WRITE edge sets next pixel");

        controller.drivePins(0, 0, 0x001F, false, true);
        check(controller.framebuffer().getPixel(7, 9) == 0x001F, "CLEAR edge fills framebuffer");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
