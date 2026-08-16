package com.foreverspark.logicsim.client.render;

import com.foreverspark.logicsim.display.DisplayCommandCodec;
import net.minecraft.core.Direction;

/** Regression checks for display face orientation, full-face geometry, emissive rendering, and native-64 batching. */
public final class DisplayRendererSelfTest {
    private DisplayRendererSelfTest() {}

    public static void main(String[] args) {
        checkFaces(Direction.NORTH, 0.0f, 0.0f);
        checkFaces(Direction.EAST, 90.0f, -90.0f);
        checkFaces(Direction.SOUTH, 180.0f, 180.0f);
        checkFaces(Direction.WEST, 270.0f, 90.0f);

        check(DisplayBlockEntityRenderer.pixelLight() == 0x00F000F0,
                "screen texture must use vanilla packed full-bright light");
        check(DisplayBlockEntityRenderer.screenMin() == 0.0f,
                "screen quad must start at the exact block-face edge");
        check(DisplayBlockEntityRenderer.screenMax() == 1.0f,
                "screen quad must end at the exact block-face edge");
        check(DisplayBlockEntityRenderer.screenMax() - DisplayBlockEntityRenderer.screenMin() == 1.0f,
                "screen texture must cover the complete block face with zero renderer inset");

        double z = DisplayBlockEntityRenderer.screenFaceZ();
        check(z > 0.0 && z < 0.75 / 16.0, "texture plane sits just outside the local NORTH model screen face");
        checkNative64Batch();
        checkFusedPixelMath();
        System.out.println("Display renderer face/full-surface/GPU-texture/native64-batch/fused-RGB self-test: PASS");
    }

    private static void checkNative64Batch() {
        int logicalWidth = 128;
        int logicalHeight = 128;
        int backingWidth = 128;
        int columns = 2;
        char[] pixels = new char[backingWidth * logicalHeight];
        long[] tileRevisions = new long[4];
        long[] dirtyWords = new long[1];
        RealtimeDisplayNative64FastPath.State state = new RealtimeDisplayNative64FastPath.State();

        long[] dense = new long[64];
        for (int index = 0; index < dense.length; index++) {
            int x = (index * 37) & 127;
            int y = (index * 53) & 127;
            dense[index] = DisplayCommandCodec.pixel(x, y, index + 1);
        }

        state.reset(0, 0L);
        RealtimeDisplayNative64FastPath.apply(
                dense, dense.length, logicalWidth, logicalHeight, backingWidth, columns,
                pixels, tileRevisions, dirtyWords, state
        );
        check(state.changed(), "dense native-64 batch must change framebuffer");
        check(state.revision() == 1L, "dense native-64 batch must publish one revision");
        check(state.nonZeroPixels() == 64, "dense native-64 non-zero accounting");
        check(state.wholeWallInvalidated(), "dense native-64 batch should use one whole-wall metadata invalidation");
        check(state.variableColorStreaming(), "variable dense RGB must use branchless streaming path");
        for (long tileRevision : tileRevisions) {
            check(tileRevision == 1L, "dense native-64 batch must publish every tile at the same revision");
        }

        // The variable-color streaming path intentionally over-publishes identical replays to remove the unpredictable
        // previous==new branch from the MHz loop. Framebuffer and exact non-zero accounting must still be unchanged.
        int beforeNonZero = state.nonZeroPixels();
        state.reset(state.nonZeroPixels(), state.revision());
        RealtimeDisplayNative64FastPath.apply(
                dense, dense.length, logicalWidth, logicalHeight, backingWidth, columns,
                pixels, tileRevisions, dirtyWords, state
        );
        check(state.changed(), "variable RGB replay may intentionally republish");
        check(state.revision() == 2L, "variable RGB replay should publish one new batch revision");
        check(state.nonZeroPixels() == beforeNonZero, "variable RGB replay must preserve exact non-zero accounting");
        check(state.variableColorStreaming(), "variable RGB replay remains on branchless path");

        // Constant/repeating color streams retain the equality-skip path; this protects the 100%/solid-color case.
        long[] constant = new long[64];
        for (int index = 0; index < constant.length; index++) {
            int x = (index * 29) & 127;
            int y = (index * 47) & 127;
            constant[index] = DisplayCommandCodec.pixel(x, y, 0xFFFF);
        }
        state.reset(state.nonZeroPixels(), state.revision());
        RealtimeDisplayNative64FastPath.apply(
                constant, constant.length, logicalWidth, logicalHeight, backingWidth, columns,
                pixels, tileRevisions, dirtyWords, state
        );
        check(state.changed(), "constant-color batch must update newly touched pixels");
        check(!state.variableColorStreaming(), "constant color must retain skip-friendly path");
        long constantRevision = state.revision();

        state.reset(state.nonZeroPixels(), state.revision());
        RealtimeDisplayNative64FastPath.apply(
                constant, constant.length, logicalWidth, logicalHeight, backingWidth, columns,
                pixels, tileRevisions, dirtyWords, state
        );
        check(!state.changed(), "identical constant-color replay must not republish");
        check(state.revision() == constantRevision, "constant-color replay must preserve revision");
        check(!state.variableColorStreaming(), "constant replay must stay skip-friendly");

        long[] one = {DisplayCommandCodec.pixel(1, 1, 0x07E0)};
        state.reset(state.nonZeroPixels(), state.revision());
        RealtimeDisplayNative64FastPath.apply(
                one, one.length, logicalWidth, logicalHeight, backingWidth, columns,
                pixels, tileRevisions, dirtyWords, state
        );
        check(state.changed(), "small native-64 batch must change framebuffer");
        check(!state.wholeWallInvalidated(), "small native-64 batch should keep precise dirty metadata");
        check(!state.variableColorStreaming(), "small native-64 batch must not select streaming mode");
    }

    private static void checkFusedPixelMath() {
        char[] pixel = new char[1];
        int nonZero = 0;

        nonZero = FusedNative64PixelMath.write(pixel, 0, 0xF800, nonZero);
        check(pixel[0] == (char) 0xF800, "fused writer stores exact RGB565 red");
        check(nonZero == 1, "fused writer increments zero->nonzero exactly");

        nonZero = FusedNative64PixelMath.write(pixel, 0, 0x07E0, nonZero);
        check(pixel[0] == (char) 0x07E0, "fused writer replaces nonzero RGB565 exactly");
        check(nonZero == 1, "fused writer preserves count on nonzero->nonzero");

        nonZero = FusedNative64PixelMath.write(pixel, 0, 0, nonZero);
        check(pixel[0] == 0, "fused writer stores black exactly");
        check(nonZero == 0, "fused writer decrements nonzero->zero exactly");

        nonZero = FusedNative64PixelMath.write(pixel, 0, 0, nonZero);
        check(nonZero == 0, "fused writer preserves count on zero->zero");
    }

    private static void checkFaces(Direction expectedFacing, float expectedYnYaw, float expectedSignedDegrees) {
        check(DisplayBlockEntityRenderer.surfaceYawDegrees(expectedFacing) == expectedYnYaw,
                expectedFacing + " Axis.YN vanilla yaw");

        float degrees = DisplayBlockEntityRenderer.rotationDegrees(expectedFacing);
        check(degrees == expectedSignedDegrees, expectedFacing + " equivalent signed PoseStack angle");

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
