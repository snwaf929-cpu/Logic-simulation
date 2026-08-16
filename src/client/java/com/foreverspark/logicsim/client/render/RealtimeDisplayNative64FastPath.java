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
 *
 * <p>Dense variable-color streams use a branchless pixel loop. RANDOM RGB workloads make the old previous==new and
 * zero/non-zero transition branches highly data-dependent (especially low probabilities where black is common). The
 * adaptive path keeps constant/repeating streams on the skip-friendly loop, but removes those unpredictable branches
 * for genuinely varying RGB batches.</p>
 */
public final class RealtimeDisplayNative64FastPath {
    private static final int TILE_SHIFT = 6;
    private static final int DENSE_WRITES_PER_TILE = 8;
    private static final int COLOR_PROBE_SAMPLES = 32;
    private static final int COLOR_PROBE_MIN_CHANGES = 8;

    private RealtimeDisplayNative64FastPath() {}

    /** Mutable per-surface state so the hot path allocates nothing per worker slice. */
    public static final class State {
        private int nonZeroPixels;
        private long revision;
        private boolean changed;
        private boolean wholeWallInvalidated;
        private boolean variableColorStreaming;

        public void reset(int nonZeroPixels, long revision) {
            this.nonZeroPixels = nonZeroPixels;
            this.revision = revision;
            this.changed = false;
            this.wholeWallInvalidated = false;
            this.variableColorStreaming = false;
        }

        public int nonZeroPixels() { return nonZeroPixels; }
        public long revision() { return revision; }
        public boolean changed() { return changed; }
        public boolean wholeWallInvalidated() { return wholeWallInvalidated; }
        public boolean variableColorStreaming() { return variableColorStreaming; }
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
        if (allTilesDirty && looksLikeVariableColorStream(raws, limit)) {
            applyVariableColorDense(
                    raws,
                    limit,
                    logicalWidth,
                    logicalHeight,
                    backingWidth,
                    pixels,
                    tileRevisions,
                    state
            );
            return;
        }

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
            nonZero += nonZeroFlag(rgb565) - nonZeroFlag(previous);
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

    /**
     * Probe a tiny prefix only. Constant/repeating colors benefit from the equality-skip path; random RGB streams
     * overwhelmingly exceed this threshold, so they avoid an unpredictable equality branch for the full batch.
     */
    private static boolean looksLikeVariableColorStream(long[] raws, int limit) {
        int probe = Math.min(limit, COLOR_PROBE_SAMPLES);
        if (probe < COLOR_PROBE_MIN_CHANGES + 1) return false;

        int previousColor = -1;
        int changes = 0;
        for (int index = 0; index < probe; index++) {
            long raw = raws[index];
            if (((raw >>> 48) & 0xFFL) != DisplayCommandCodec.OP_PIXEL) return false;
            int color = (int) raw & 0xFFFF;
            if (previousColor >= 0 && color != previousColor) changes++;
            previousColor = color;
        }
        return changes >= COLOR_PROBE_MIN_CHANGES;
    }

    /**
     * Dense, highly-variable RGB writer. Pixel stores are unconditional and exact. nonZeroPixels remains exact via a
     * branchless 0/1 delta, while metadata is intentionally over-invalidated once for the whole wall.
     */
    private static void applyVariableColorDense(
            long[] raws,
            int limit,
            int logicalWidth,
            int logicalHeight,
            int backingWidth,
            char[] framebuffer,
            long[] tileRevisions,
            State state
    ) {
        int nonZero = state.nonZeroPixels;
        boolean touched = false;

        for (int commandIndex = 0; commandIndex < limit; commandIndex++) {
            long raw = raws[commandIndex];
            int opcode = (int) ((raw >>> 48) & 0xFFL);
            if (opcode == DisplayCommandCodec.OP_PIXEL) {
                int globalX = (int) ((raw >>> 16) & 0xFFFFL);
                int globalY = (int) ((raw >>> 32) & 0xFFFFL);
                if (globalX >= logicalWidth || globalY >= logicalHeight) continue;

                int rgb565 = (int) raw & 0xFFFF;
                int pixelIndex = globalY * backingWidth + globalX;
                int previous = framebuffer[pixelIndex];
                framebuffer[pixelIndex] = (char) rgb565;
                nonZero += nonZeroFlag(rgb565) - nonZeroFlag(previous);
                touched = true;
                continue;
            }

            if (opcode == DisplayCommandCodec.OP_CLEAR) {
                Arrays.fill(framebuffer, (char) 0);
                nonZero = 0;
                touched = true;
            }
        }

        state.nonZeroPixels = nonZero;
        state.changed = touched;
        state.wholeWallInvalidated = touched;
        state.variableColorStreaming = touched;
        if (!touched) return;

        long next = ++state.revision;
        Arrays.fill(tileRevisions, next);
    }

    /** Branchless 0 when value==0, otherwise 1. RGB565 values are always non-negative 16-bit ints. */
    private static int nonZeroFlag(int value) {
        return (value | -value) >>> 31;
    }
}
