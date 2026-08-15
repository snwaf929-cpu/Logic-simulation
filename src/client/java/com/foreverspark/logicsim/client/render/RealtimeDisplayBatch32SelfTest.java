package com.foreverspark.logicsim.client.render;

import com.foreverspark.logicsim.display.DisplayCommandCodec;

/** Dependency-light regression for the 32px/tile realtime DISPLAY batch specialization. */
public final class RealtimeDisplayBatch32SelfTest {
    private RealtimeDisplayBatch32SelfTest() {}

    public static void main(String[] args) {
        int logicalWidth = 64;
        int logicalHeight = 64;
        int backingWidth = 128;
        int backingHeight = 128;
        int columns = 2;
        char[] pixels = new char[backingWidth * backingHeight];
        long[] tileRevisions = new long[4];
        long[] dirtyWords = new long[1];
        RealtimeDisplayBatch32FastPath.State state = new RealtimeDisplayBatch32FastPath.State();

        long[] first = {
                DisplayCommandCodec.pixel(1, 2, 0x1234),
                DisplayCommandCodec.pixel(33, 34, 0xABCD)
        };
        state.reset(0, 0L);
        RealtimeDisplayBatch32FastPath.apply(
                first, first.length, logicalWidth, logicalHeight, backingWidth, columns,
                pixels, tileRevisions, dirtyWords, state
        );

        check(state.changed(), "first pixel batch must change the framebuffer");
        check(state.revision() == 1L, "complete batch must publish one revision");
        check(state.nonZeroPixels() == 8, "two logical pixels at scale 2 must occupy eight backing pixels");
        check2x2(pixels, backingWidth, 2, 4, 0x1234);
        check2x2(pixels, backingWidth, 66, 68, 0xABCD);
        check(tileRevisions[0] == 1L && tileRevisions[3] == 1L,
                "only touched tiles must receive the first batch revision");
        check(tileRevisions[1] == 0L && tileRevisions[2] == 0L,
                "untouched tiles must stay clean");

        state.reset(state.nonZeroPixels(), state.revision());
        RealtimeDisplayBatch32FastPath.apply(
                first, first.length, logicalWidth, logicalHeight, backingWidth, columns,
                pixels, tileRevisions, dirtyWords, state
        );
        check(!state.changed(), "identical repeated writes must not publish a revision");
        check(state.revision() == 1L, "unchanged batch must preserve revision");

        long[] clearThenPixel = {
                DisplayCommandCodec.clear(),
                DisplayCommandCodec.pixel(5, 5, 0x00F0)
        };
        state.reset(state.nonZeroPixels(), state.revision());
        RealtimeDisplayBatch32FastPath.apply(
                clearThenPixel, clearThenPixel.length, logicalWidth, logicalHeight, backingWidth, columns,
                pixels, tileRevisions, dirtyWords, state
        );
        check(state.changed(), "clear+pixel batch must change the framebuffer");
        check(state.revision() == 2L, "clear+pixel batch must still publish only one revision");
        check(state.nonZeroPixels() == 4, "one scale-2 logical pixel after clear must occupy four backing pixels");
        check2x2(pixels, backingWidth, 10, 10, 0x00F0);
        for (long tileRevision : tileRevisions) {
            check(tileRevision == 2L, "CLEAR must mark every tile with the batch revision");
        }

        long[] outOfRange = {DisplayCommandCodec.pixel(64, 63, 0xFFFF)};
        state.reset(state.nonZeroPixels(), state.revision());
        RealtimeDisplayBatch32FastPath.apply(
                outOfRange, outOfRange.length, logicalWidth, logicalHeight, backingWidth, columns,
                pixels, tileRevisions, dirtyWords, state
        );
        check(!state.changed(), "out-of-range command must be ignored");
        check(state.revision() == 2L, "ignored command must not publish metadata");

        System.out.println("Realtime DISPLAY 32px/tile batch self-test: PASS"
                + " | logical=" + logicalWidth + "x" + logicalHeight
                + " backing=" + backingWidth + "x" + backingHeight
                + " scale=2 metadataCommits=1-per-batch");
    }

    private static void check2x2(char[] pixels, int stride, int x, int y, int expected) {
        check(pixels[y * stride + x] == (char) expected, "top-left backing pixel mismatch");
        check(pixels[y * stride + x + 1] == (char) expected, "top-right backing pixel mismatch");
        check(pixels[(y + 1) * stride + x] == (char) expected, "bottom-left backing pixel mismatch");
        check(pixels[(y + 1) * stride + x + 1] == (char) expected, "bottom-right backing pixel mismatch");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
