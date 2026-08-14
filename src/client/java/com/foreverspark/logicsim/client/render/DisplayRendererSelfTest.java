package com.foreverspark.logicsim.client.render;

import net.minecraft.client.gui.Font;
import net.minecraft.core.Direction;

/** Regression checks for display face orientation and world-depth behavior. */
public final class DisplayRendererSelfTest {
    private DisplayRendererSelfTest() {}

    public static void main(String[] args) {
        check(DisplayBlockEntityRenderer.rotationDegrees(Direction.NORTH) == 0.0f, "north rotation");
        check(DisplayBlockEntityRenderer.rotationDegrees(Direction.EAST) == -90.0f, "east rotates local NORTH to EAST");
        check(DisplayBlockEntityRenderer.rotationDegrees(Direction.SOUTH) == 180.0f, "south rotation");
        check(DisplayBlockEntityRenderer.rotationDegrees(Direction.WEST) == 90.0f, "west rotates local NORTH to WEST");

        check(DisplayBlockEntityRenderer.isCameraOnFront(Direction.NORTH, 0, 0, -2), "north-front camera accepted");
        check(!DisplayBlockEntityRenderer.isCameraOnFront(Direction.NORTH, 0, 0, 2), "north-back camera rejected");
        check(DisplayBlockEntityRenderer.isCameraOnFront(Direction.EAST, 2, 0, 0), "east-front camera accepted");
        check(!DisplayBlockEntityRenderer.isCameraOnFront(Direction.EAST, -2, 0, 0), "east-back camera rejected");
        check(DisplayBlockEntityRenderer.isCameraOnFront(Direction.SOUTH, 0, 0, 2), "south-front camera accepted");
        check(DisplayBlockEntityRenderer.isCameraOnFront(Direction.WEST, -2, 0, 0), "west-front camera accepted");

        check(DisplayBlockEntityRenderer.pixelDisplayMode() == Font.DisplayMode.NORMAL,
                "pixels must use normal depth-tested rendering, never SEE_THROUGH");
        double z = DisplayBlockEntityRenderer.screenFaceZ();
        check(z > 0.0 && z < 0.75 / 16.0, "pixel plane sits just in front of the model screen face");
        System.out.println("Display renderer face/depth self-test: PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
