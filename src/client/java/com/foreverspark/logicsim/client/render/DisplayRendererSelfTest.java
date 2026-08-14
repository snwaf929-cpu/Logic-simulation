package com.foreverspark.logicsim.client.render;

import net.minecraft.client.gui.Font;
import net.minecraft.core.Direction;

/** Regression checks for display face orientation and world-depth behavior. */
public final class DisplayRendererSelfTest {
    private DisplayRendererSelfTest() {}

    public static void main(String[] args) {
        // PoseStack is a mathematical Y rotation. These signs deliberately do NOT copy the blockstate JSON
        // numbers: the purpose of this test is to verify that local NORTH ends up pointing at world FACING.
        checkFaces(Direction.NORTH, 0.0f);
        checkFaces(Direction.EAST, -90.0f);
        checkFaces(Direction.SOUTH, 180.0f);
        checkFaces(Direction.WEST, 90.0f);

        check(DisplayBlockEntityRenderer.pixelDisplayMode() == Font.DisplayMode.POLYGON_OFFSET,
                "pixels must use depth-tested polygon-offset surface rendering, never SEE_THROUGH");
        double z = DisplayBlockEntityRenderer.screenFaceZ();
        check(z > 0.0 && z < 0.75 / 16.0, "pixel plane sits just outside the local NORTH model screen face");
        System.out.println("Display renderer face/depth self-test: PASS");
    }

    private static void checkFaces(Direction expectedFacing, float expectedDegrees) {
        float degrees = DisplayBlockEntityRenderer.rotationDegrees(expectedFacing);
        check(degrees == expectedDegrees, expectedFacing + " PoseStack angle");

        // Rotate local NORTH vector (0,0,-1) using the same right-handed Y convention as PoseStack/JOML.
        double radians = Math.toRadians(degrees);
        double worldX = -Math.sin(radians);
        double worldZ = -Math.cos(radians);
        int stepX = (int) Math.round(worldX);
        int stepZ = (int) Math.round(worldZ);
        check(stepX == expectedFacing.getStepX() && stepZ == expectedFacing.getStepZ(),
                expectedFacing + " rotation must point local NORTH at the real screen-facing direction");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
