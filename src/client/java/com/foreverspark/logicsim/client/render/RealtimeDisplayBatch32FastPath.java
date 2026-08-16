package com.foreverspark.logicsim.client.render;

import com.foreverspark.logicsim.display.DisplayCommandCodec;

import java.util.Arrays;

/**
 * Allocation-free batch writer for DISPLAY walls configured at 32 logical pixels per block.
 *
 * <p>The backing store remains 64x64 per block, so every logical pixel is a 2x2 backing rectangle. The generic
 * realtime path used to increment the global revision and write the tile revision for every virtual pixel command.
 * At tens of MHz that metadata traffic is avoidable. This helper applies a whole simulator batch, tracks dirty tiles
 * in the existing bitset, and publishes one revision for the complete batch.</p>
 */
public final class RealtimeDisplayBatch32FastPath {
    private static final int DENSITY_SHIFT = 5; // 32 logical pixels / tile.
    private static final int SCALE_SHIFT = 1;   // 64 backing / 32 logical = 2.

    private RealtimeDisplayBatch32FastPath() {}

    /** Mutable per-surface state so the hot path does not allocate a result object per worker slice. */
    public static final class State {
        private int nonZeroPixels;
        private long revision;
        private boolean changed;

        public void reset(int nonZeroPixels, long revision) {
            this.nonZeroPixels = nonZeroPixels;
            this.revision = revision;
            this.changed = false;
        }

        public int nonZeroPixels() { return nonZeroPixels; }
        public long revision() { return revision; }
        public boolean changed() { return changed; }
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

        Arrays.fill(dirtyTileWords, 0L);
        boolean changed = false;
        boolean allTilesDirty = false;
        int nonZero = state.nonZeroPixels;

        for (int commandIndex = 0; commandIndex < limit; commandIndex++) {
            long raw = raws[commandIndex];
            int opcode = (int) ((raw >>> 48) & 0xFFL);

            if (opcode == DisplayCommandCodec.OP_CLEAR) {
                if (nonZero == 0) continue;
                Arrays.fill(pixels, (char) 0);
                nonZero = 0;
                changed = true;
                allTilesDirty = true;
                Arrays.fill(dirtyTileWords, 0L);
                continue;
            }
            if (opcode != DisplayCommandCodec.OP_PIXEL) continue;

            int globalX = (int) ((raw >>> 16) & 0xFFFFL);
            int globalY = (int) ((raw >>> 32) & 0xFFFFL);
            if (globalX >= logicalWidth || globalY >= logicalHeight) continue;

            int rgb565 = (int) raw & 0xFFFF;
            int backingX = globalX << SCALE_SHIFT;
            int backingY = globalY << SCALE_SHIFT;
            int row0 = backingY * backingWidth + backingX;
            int row1 = row0 + backingWidth;
            boolean commandChanged = false;

            int previous = pixels[row0];
            if (previous != rgb565) {
                pixels[row0] = (char) rgb565;
                if (previous == 0 && rgb565 != 0) nonZero++;
                else if (previous != 0 && rgb565 == 0) nonZero--;
                commandChanged = true;
            }

            previous = pixels[row0 + 1];
            if (previous != rgb565) {
                pixels[row0 + 1] = (char) rgb565;
                if (previous == 0 && rgb565 != 0) nonZero++;
                else if (previous != 0 && rgb565 == 0) nonZero--;
                commandChanged = true;
            }

            previous = pixels[row1];
            if (previous != rgb565) {
                pixels[row1] = (char) rgb565;
                if (previous == 0 && rgb565 != 0) nonZero++;
                else if (previous != 0 && rgb565 == 0) nonZero--;
                commandChanged = true;
            }

            previous = pixels[row1 + 1];
            if (previous != rgb565) {
                pixels[row1 + 1] = (char) rgb565;
                if (previous == 0 && rgb565 != 0) nonZero++;
                else if (previous != 0 && rgb565 == 0) nonZero--;
                commandChanged = true;
            }

            if (!commandChanged) continue;
            changed = true;
            if (!allTilesDirty) {
                int tileIndex = (globalY >>> DENSITY_SHIFT) * columns + (globalX >>> DENSITY_SHIFT);
                dirtyTileWords[tileIndex >>> 6] |= 1L << (tileIndex & 63);
            }
        }

        state.nonZeroPixels = nonZero;
        state.changed = changed;
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
