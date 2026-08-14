package com.foreverspark.logicsim.client.render;

import net.minecraft.client.gui.Font;
import net.minecraft.core.Direction;

/** Regression checks for display face orientation and world-depth behavior. */
public final class DisplayRendererSelfTest {
    private DisplayRendererSelfTest() {}

    public static void main(String[] args) {
        // These values deliberately match assets/logicsimulation/blockstates/display_block.json.
        check(DisplayBlockEntityRenderer.rotationDegrees(Direction.NORTH) == 0.0f, "north rotation matches blockstate");
        check(DisplayBlockEntityRenderer.rotationDegrees(Direction.EAST) == 90.0f, "east rotation matches blockstate");
        check(DisplayBlockEntityRenderer.rotationDegrees(Direction.SOUTH) == 180.0f, "south rotation matches blockstate");
        check(DisplayBlockEntityRenderer.rotationDegrees(Direction.WEST) == 270.0f, "west rotation matches blockstate");

        check(DisplayBlockEntityRenderer.pixelDisplayMode() == Font.DisplayMode.NORMAL,
                "pixels must use normal depth-tested rendering, never SEE_THROUGH");
        double z = DisplayBlockEntityRenderer.screenFaceZ();
        check(z > 0.0 && z < 0.75 / 16.0, "pixel plane sits just outside the local NORTH model screen face");
        System.out.println("Display renderer face/depth self-test: PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
