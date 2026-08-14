package com.foreverspark.logicsim.client.render;

import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.Direction;

/** Regression checks for display face orientation, depth behavior, and emissive pixels. */
public final class DisplayRendererSelfTest {
    private DisplayRendererSelfTest() {}

    public static void main(String[] args) {
        checkFaces(Direction.NORTH, 0.0f, 0.0f);
        checkFaces(Direction.EAST, 90.0f, -90.0f);
        checkFaces(Direction.SOUTH, 180.0f, 180.0f);
        checkFaces(Direction.WEST, 270.0f, 90.0f);

        check(DisplayBlockEntityRenderer.pixelDisplayMode() == Font.DisplayMode.POLYGON_OFFSET,
                "pixels must use depth-tested polygon-offset surface rendering, never SEE_THROUGH");
        check(DisplayBlockEntityRenderer.pixelLight() == LightTexture.FULL_BRIGHT,
                "screen pixels must be emissive/full-bright");
        double z = DisplayBlockEntityRenderer.screenFaceZ();
        check(z > 0.0 && z < 0.75 / 16.0, "pixel plane sits just outside the local NORTH model screen face");
        System.out.println("Display renderer face/depth self-test: PASS");
    }

    private static void checkFaces(Direction expectedFacing, float expectedYnYaw, float expectedSignedDegrees) {
        check(DisplayBlockEntityRenderer.surfaceYawDegrees(expectedFacing) == expectedYnYaw,
                expectedFacing + " Axis.YN vanilla yaw");

        float degrees = DisplayBlockEntityRenderer.rotationDegrees(expectedFacing);
        check(degrees == expectedSignedDegrees, expectedFacing + " equivalent signed PoseStack angle");

        // Rotate local NORTH vector (0,0,-1) using the right-handed Axis.YP equivalent.
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
