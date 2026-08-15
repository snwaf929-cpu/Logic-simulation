package com.foreverspark.logicsim.client.render;

/**
 * Compiles arbitrary RANDOM lane thresholds into a cache-sized lookup table so the RGB hot loop can sample an
 * entire 16-bit color mask from one 32-bit PRNG state advance.
 *
 * <p>The existing RANDOM compiler represents arbitrary probability as an unsigned 8-bit threshold. The table size
 * is 2^14 = 16,384, exactly 64 entries for every one of those 256 threshold quanta. Therefore every lane has exactly
 * the same marginal probability as the old unsigned-byte sampler; this is not a 10% -> 12.5% approximation.</p>
 */
public final class SingleWordRgbMaskSampler {
    static final int TABLE_BITS = 14;
    static final int TABLE_SIZE = 1 << TABLE_BITS;
    static final int TABLE_MASK = TABLE_SIZE - 1;
    static final int ENTRIES_PER_THRESHOLD_QUANTUM = TABLE_SIZE >>> 8;
    private static final int NONZERO_STATE = 0x6D2B79F5;

    private final long laneMask;
    private final int compactShift;
    private final char[] compactTable;
    private final long[] wideTable;
    private int rngState;

    public SingleWordRgbMaskSampler(long laneMask, long[] thresholdBitMasks, long seed) {
        if (thresholdBitMasks == null || thresholdBitMasks.length < 8) {
            throw new IllegalArgumentException("thresholdBitMasks must contain 8 bit planes");
        }
        this.laneMask = laneMask;
        this.compactShift = compactShift(laneMask);
        if (compactShift >= 0) {
            this.compactTable = new char[TABLE_SIZE];
            this.wideTable = null;
            compileCompact(laneMask, thresholdBitMasks, seed, compactShift, compactTable);
        } else {
            this.compactTable = null;
            this.wideTable = new long[TABLE_SIZE];
            compileWide(laneMask, thresholdBitMasks, seed, wideTable);
        }
        int mixed = mix32((int) seed ^ (int) (seed >>> 32) ^ 0xA511E9B3);
        this.rngState = mixed == 0 ? NONZERO_STATE : mixed;
    }

    /** One 32-bit PRNG state transition and one table load for all arbitrary lanes. */
    public long sampleMask() {
        int x = rngState;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        if (x == 0) x = NONZERO_STATE;
        rngState = x;
        int index = x >>> (32 - TABLE_BITS);
        if (compactTable != null) {
            return ((long) compactTable[index] & 0xFFFFL) << compactShift;
        }
        return wideTable[index];
    }

    public long laneMask() { return laneMask; }
    public int laneCount() { return Long.bitCount(laneMask); }
    public int tableEntries() { return TABLE_SIZE; }
    public int rngWordsPerSample() { return laneMask == 0L ? 0 : 1; }
    public boolean compact16() { return compactTable != null; }
    public int compactShift() { return compactShift; }

    /** Test/diagnostic helper: exact number of table entries that assert this source lane. */
    int tableHitCount(long laneBit) {
        if ((laneBit & laneMask) == 0L || Long.bitCount(laneBit) != 1) return 0;
        int count = 0;
        if (compactTable != null) {
            int bit = Long.numberOfTrailingZeros(laneBit) - compactShift;
            int mask = 1 << bit;
            for (char value : compactTable) if ((value & mask) != 0) count++;
        } else {
            for (long value : wideTable) if ((value & laneBit) != 0L) count++;
        }
        return count;
    }

    private static void compileCompact(
            long laneMask,
            long[] thresholdBitMasks,
            long seed,
            int shift,
            char[] table
    ) {
        long remaining = laneMask;
        while (remaining != 0L) {
            int lane = Long.numberOfTrailingZeros(remaining);
            long laneBit = 1L << lane;
            remaining &= ~laneBit;
            int threshold = thresholdForLane(thresholdBitMasks, laneBit);
            if (threshold <= 0) continue;
            int hits = threshold * ENTRIES_PER_THRESHOLD_QUANTUM;
            int compactBit = 1 << (lane - shift);
            int key = laneKey(seed, lane, threshold);
            int multiplier = (mix32(key ^ 0x9E3779B9) | 1) & TABLE_MASK;
            if (multiplier == 0) multiplier = 1;
            int offset = mix32(key ^ 0x7F4A7C15) & TABLE_MASK;
            for (int index = 0; index < TABLE_SIZE; index++) {
                int permuted = (index * multiplier + offset) & TABLE_MASK;
                if (permuted < hits) table[index] |= (char) compactBit;
            }
        }
    }

    private static void compileWide(
            long laneMask,
            long[] thresholdBitMasks,
            long seed,
            long[] table
    ) {
        long remaining = laneMask;
        while (remaining != 0L) {
            int lane = Long.numberOfTrailingZeros(remaining);
            long laneBit = 1L << lane;
            remaining &= ~laneBit;
            int threshold = thresholdForLane(thresholdBitMasks, laneBit);
            if (threshold <= 0) continue;
            int hits = threshold * ENTRIES_PER_THRESHOLD_QUANTUM;
            int key = laneKey(seed, lane, threshold);
            int multiplier = (mix32(key ^ 0x9E3779B9) | 1) & TABLE_MASK;
            if (multiplier == 0) multiplier = 1;
            int offset = mix32(key ^ 0x7F4A7C15) & TABLE_MASK;
            for (int index = 0; index < TABLE_SIZE; index++) {
                int permuted = (index * multiplier + offset) & TABLE_MASK;
                if (permuted < hits) table[index] |= laneBit;
            }
        }
    }

    private static int compactShift(long mask) {
        int lanes = Long.bitCount(mask);
        if (lanes == 0 || lanes > 16) return -1;
        int shift = Long.numberOfTrailingZeros(mask);
        long normalized = mask >>> shift;
        long expected = lanes == 64 ? -1L : (1L << lanes) - 1L;
        return normalized == expected ? shift : -1;
    }

    private static int thresholdForLane(long[] thresholdBitMasks, long laneBit) {
        int threshold = 0;
        for (int bit = 0; bit < 8; bit++) {
            if ((thresholdBitMasks[bit] & laneBit) != 0L) threshold |= 1 << bit;
        }
        return threshold;
    }

    private static int laneKey(long seed, int lane, int threshold) {
        return (int) seed
                ^ (int) (seed >>> 32)
                ^ lane * 0x45D9F3B
                ^ threshold * 0x27D4EB2D;
    }

    private static int mix32(int x) {
        x ^= x >>> 16;
        x *= 0x7FEB352D;
        x ^= x >>> 15;
        x *= 0x846CA68B;
        x ^= x >>> 16;
        return x;
    }
}
