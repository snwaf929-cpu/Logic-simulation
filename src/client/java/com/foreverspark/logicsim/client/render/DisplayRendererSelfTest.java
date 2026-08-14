package com.foreverspark.logicsim.client.render;

import net.minecraft.core.Direction;

/** Regression checks for display face orientation, full-face geometry, emissive pixels, and compressed runs. */
public final class DisplayRendererSelfTest {
    private DisplayRendererSelfTest() {}

    public static void main(String[] args) {
        checkFaces(Direction.NORTH, 0.0f, 0.0f);
        checkFaces(Direction.EAST, 90.0f, -90.0f);
        checkFaces(Direction.SOUTH, 180.0f, 180.0f);
        checkFaces(Direction.WEST, 270.0f, 90.0f);

        check(DisplayBlockEntityRenderer.pixelLight() == 0x00F000F0,
                "screen pixels must use vanilla packed full-bright light");
        check(DisplayBlockEntityRenderer.screenMin() == 0.0f,
                "pixel geometry must start at the exact block-face edge");
        check(DisplayBlockEntityRenderer.screenMax() == 1.0f,
                "pixel geometry must end at the exact block-face edge");
        check(DisplayBlockEntityRenderer.screenMax() - DisplayBlockEntityRenderer.screenMin() == 1.0f,
                "1x1 mode must cover the complete block face with zero renderer inset");

        long run = DisplayBlockEntityRenderer.packRun(63, 0, 64, 0xFF12AB34);
        check((int) run == 0xFF12AB34, "run encoding preserves ARGB");
        check(DisplayBlockEntityRenderer.unpackY(run) == 63, "run encoding preserves max row");
        check(DisplayBlockEntityRenderer.unpackStartX(run) == 0, "run encoding preserves start X");
        check(DisplayBlockEntityRenderer.unpackEndX(run) == 64, "run encoding preserves exclusive max X");

        double z = DisplayBlockEntityRenderer.screenFaceZ();
        check(z > 0.0 && z < 0.75 / 16.0, "pixel plane sits just outside the local NORTH model screen face");
        System.out.println("Display renderer face/full-surface/run-compression self-test: PASS");
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
