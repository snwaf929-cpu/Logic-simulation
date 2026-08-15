package com.foreverspark.logicsim.client.render;

import com.foreverspark.logicsim.display.DisplayCommandCodec;

import java.util.Arrays;

/**
 * Allocation-free native 64px/tile DISPLAY batch writer.
 *
 * <p>At full density, logical and backing coordinates are identical. Large high-rate batches already spread writes
 * across essentially every tile, so calculating and OR-ing one dirty-tile bit for every simulated pixel becomes pure
 * bookkeeping. This path keeps framebuffer semantics exact, accumulates non-zero accounting locally, and may safely
 * over-invalidate tile metadata once per dense batch. Over-invalidation affects only renderer refresh work, never the
 * framebuffer contents or simulated clock state.</p>
 */
public final class RealtimeDisplayNative64FastPath {
    private static final int TILE_SHIFT = 6;
    private static final int DENSE_WRITES_PER_TILE = 8;

    private RealtimeDisplayNative64FastPath() {}

    /** Mutable per-surface state so the hot path allocates nothing per worker slice. */
    public static final class State {
        private int nonZeroPixels;
        private long revision;
        private boolean changed;
        private boolean wholeWallInvalidated;

        public void reset(int nonZeroPixels, long revision) {
            this.nonZeroPixels = nonZeroPixels;
            this.revision = revision;
            this.changed = false;
            this.wholeWallInvalidated = false;
        }

        public int nonZeroPixels() { return nonZeroPixels; }
        public long revision() { return revision; }
        public boolean changed() { return changed; }
        public boolean wholeWallInvalidated() { return wholeWallInvalidated; }
    }

    public static void apply(
            long[] raws,
            int count,
            int logicalWidth,
            int logicalHeight,
            int backingWidth,
            int columns,
            char[] pixels,
            long[] tileRevisions,
            long[] dirtyTileWords,
            State state
    ) {
        if (raws == null || count <= 0 || state == null) return;
        int limit = Math.min(count, raws.length);
        if (limit <= 0) return;

        int denseThreshold = Math.max(64, tileRevisions.length * DENSE_WRITES_PER_TILE);
        boolean allTilesDirty = limit >= denseThreshold;
        if (!allTilesDirty) Arrays.fill(dirtyTileWords, 0L);

        int nonZero = state.nonZeroPixels;
        boolean changed = false;
        char[] framebuffer = pixels;
        int stride = backingWidth;
        int tileColumns = columns;

        for (int commandIndex = 0; commandIndex < limit; commandIndex++) {
            long raw = raws[commandIndex];
            int opcode = (int) ((raw >>> 48) & 0xFFL);

            if (opcode == DisplayCommandCodec.OP_CLEAR) {
                if (nonZero == 0) continue;
                Arrays.fill(framebuffer, (char) 0);
                nonZero = 0;
                changed = true;
                allTilesDirty = true;
                continue;
            }
            if (opcode != DisplayCommandCodec.OP_PIXEL) continue;

            int globalX = (int) ((raw >>> 16) & 0xFFFFL);
            int globalY = (int) ((raw >>> 32) & 0xFFFFL);
            if (globalX >= logicalWidth || globalY >= logicalHeight) continue;

            int rgb565 = (int) raw & 0xFFFF;
            int pixelIndex = globalY * stride + globalX;
            int previous = framebuffer[pixelIndex];
            if (previous == rgb565) continue;

            framebuffer[pixelIndex] = (char) rgb565;
            if (previous == 0 && rgb565 != 0) nonZero++;
            else if (previous != 0 && rgb565 == 0) nonZero--;
            changed = true;

            if (!allTilesDirty) {
                int tileIndex = (globalY >>> TILE_SHIFT) * tileColumns + (globalX >>> TILE_SHIFT);
                dirtyTileWords[tileIndex >>> 6] |= 1L << (tileIndex & 63);
            }
        }

        state.nonZeroPixels = nonZero;
        state.changed = changed;
        state.wholeWallInvalidated = allTilesDirty && changed;
        if (!changed) return;

        long next = ++state.revision;
        if (allTilesDirty) {
            Arrays.fill(tileRevisions, next);
            return;
        }

        for (int wordIndex = 0; wordIndex < dirtyTileWords.length; wordIndex++) {
            long word = dirtyTileWords[wordIndex];
            while (word != 0L) {
                int bit = Long.numberOfTrailingZeros(word);
                int tileIndex = (wordIndex << 6) + bit;
                if (tileIndex < tileRevisions.length) tileRevisions[tileIndex] = next;
                word &= word - 1L;
            }
        }
    }
}
