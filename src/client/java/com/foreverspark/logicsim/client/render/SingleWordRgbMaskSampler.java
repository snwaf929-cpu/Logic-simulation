package com.foreverspark.logicsim.client.render;

/**
 * Compiles arbitrary RANDOM lane thresholds into tiny lookup tables so the RGB hot loop can sample an entire
 * 16-bit color mask from one 32-bit PRNG state advance.
 *
 * <p>The RANDOM compiler represents arbitrary probability as an unsigned 8-bit threshold. For the common contiguous
 * RGB565 case, v9 uses two independent 256-entry byte tables: one table for COLOR bits 0..7 and one for bits 8..15.
 * A 256-entry domain is exactly the native probability quantum, so a threshold T occupies exactly T entries. The two
 * tables total only 512 bytes instead of the previous 32 KiB table and stay hot alongside the framebuffer pipeline.</p>
 */
public final class SingleWordRgbMaskSampler {
    static final int COMPACT_TABLE_SIZE = 256;
    static final int WIDE_TABLE_BITS = 14;
    static final int WIDE_TABLE_SIZE = 1 << WIDE_TABLE_BITS;
    static final int WIDE_TABLE_MASK = WIDE_TABLE_SIZE - 1;
    static final int ENTRIES_PER_THRESHOLD_QUANTUM = 1;
    private static final int WIDE_ENTRIES_PER_THRESHOLD_QUANTUM = WIDE_TABLE_SIZE >>> 8;
    private static final int NONZERO_STATE = 0x6D2B79F5;

    private final long laneMask;
    private final int compactShift;
    private final byte[] compactLowTable;
    private final byte[] compactHighTable;
    private final long[] wideTable;
    private int rngState;

    public SingleWordRgbMaskSampler(long laneMask, long[] thresholdBitMasks, long seed) {
        if (thresholdBitMasks == null || thresholdBitMasks.length < 8) {
            throw new IllegalArgumentException("thresholdBitMasks must contain 8 bit planes");
        }
        this.laneMask = laneMask;
        this.compactShift = compactShift(laneMask);
        if (compactShift >= 0) {
            this.compactLowTable = new byte[COMPACT_TABLE_SIZE];
            this.compactHighTable = new byte[COMPACT_TABLE_SIZE];
            this.wideTable = null;
            compileCompact(laneMask, thresholdBitMasks, seed, compactShift, compactLowTable, compactHighTable);
        } else {
            this.compactLowTable = null;
            this.compactHighTable = null;
            this.wideTable = new long[WIDE_TABLE_SIZE];
            compileWide(laneMask, thresholdBitMasks, seed, wideTable);
        }
        int mixed = mix32((int) seed ^ (int) (seed >>> 32) ^ 0xA511E9B3);
        this.rngState = mixed == 0 ? NONZERO_STATE : mixed;
    }

    /** One 32-bit PRNG state transition and two 256-entry L1-resident loads for all 16 contiguous RGB lanes. */
    public long sampleMask() {
        int x = rngState;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        // xorshift32 is invertible on non-zero state; the constructor guard means x can never become zero here.
        rngState = x;
        return sampleMaskFromWord(x);
    }

    /**
     * Pure lookup used by v10 parallel batches. The caller supplies a statistically suitable 32-bit word, allowing
     * independent cycle ranges to be evaluated concurrently without sharing mutable PRNG state.
     */
    public long sampleMaskFromWord(int word) {
        if (compactLowTable != null) {
            int low = compactLowTable[word & 0xFF] & 0xFF;
            int high = compactHighTable[(word >>> 8) & 0xFF] & 0xFF;
            return ((long) (low | (high << 8)) & 0xFFFFL) << compactShift;
        }
        int index = word >>> (32 - WIDE_TABLE_BITS);
        return wideTable[index];
    }

    public long laneMask() { return laneMask; }
    public int laneCount() { return Long.bitCount(laneMask); }
    public int tableEntries() { return compactLowTable != null ? COMPACT_TABLE_SIZE * 2 : WIDE_TABLE_SIZE; }
    public int tableBytes() {
        return compactLowTable != null
                ? compactLowTable.length + compactHighTable.length
                : wideTable.length * Long.BYTES;
    }
    public int rngWordsPerSample() { return laneMask == 0L ? 0 : 1; }
    public boolean compact16() { return compactLowTable != null; }
    public int compactShift() { return compactShift; }

    /** Test/diagnostic helper: exact number of table entries that assert this source lane. */
    int tableHitCount(long laneBit) {
        if ((laneBit & laneMask) == 0L || Long.bitCount(laneBit) != 1) return 0;
        if (compactLowTable != null) {
            int localBit = Long.numberOfTrailingZeros(laneBit) - compactShift;
            if (localBit < 8) {
                int mask = 1 << localBit;
                int count = 0;
                for (byte value : compactLowTable) if (((value & 0xFF) & mask) != 0) count++;
                return count;
            }
            int mask = 1 << (localBit - 8);
            int count = 0;
            for (byte value : compactHighTable) if (((value & 0xFF) & mask) != 0) count++;
            return count;
        }

        int count = 0;
        for (long value : wideTable) if ((value & laneBit) != 0L) count++;
        return count;
    }

    private static void compileCompact(
            long laneMask,
            long[] thresholdBitMasks,
            long seed,
            int shift,
            byte[] lowTable,
            byte[] highTable
    ) {
        int[] threshold = new int[16];
        int[] multiplier = new int[16];
        int[] offset = new int[16];

        for (int localLane = 0; localLane < 16; localLane++) {
            long laneBit = 1L << (shift + localLane);
            if ((laneMask & laneBit) == 0L) continue;
            int laneThreshold = thresholdForLane(thresholdBitMasks, laneBit);
            threshold[localLane] = laneThreshold;
            int key = laneKey(seed, shift + localLane, laneThreshold);
            int mul = (mix32(key ^ 0x9E3779B9) | 1) & 0xFF;
            multiplier[localLane] = mul == 0 ? 1 : mul;
            offset[localLane] = mix32(key ^ 0x7F4A7C15) & 0xFF;
        }

        for (int index = 0; index < COMPACT_TABLE_SIZE; index++) {
            int low = 0;
            int high = 0;
            for (int localLane = 0; localLane < 16; localLane++) {
                int laneThreshold = threshold[localLane];
                if (laneThreshold <= 0) continue;
                int permuted = (index * multiplier[localLane] + offset[localLane]) & 0xFF;
                if (permuted >= laneThreshold) continue;
                if (localLane < 8) low |= 1 << localLane;
                else high |= 1 << (localLane - 8);
            }
            lowTable[index] = (byte) low;
            highTable[index] = (byte) high;
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
            int hits = threshold * WIDE_ENTRIES_PER_THRESHOLD_QUANTUM;
            int key = laneKey(seed, lane, threshold);
            int multiplier = (mix32(key ^ 0x9E3779B9) | 1) & WIDE_TABLE_MASK;
            if (multiplier == 0) multiplier = 1;
            int offset = mix32(key ^ 0x7F4A7C15) & WIDE_TABLE_MASK;
            for (int index = 0; index < WIDE_TABLE_SIZE; index++) {
                int permuted = (index * multiplier + offset) & WIDE_TABLE_MASK;
                if (permuted < hits) table[index] |= laneBit;
            }
        }
    }

    private static int compactShift(long mask) {
        int lanes = Long.bitCount(mask);
        if (lanes == 0 || lanes > 16) return -1;
        int shift = Long.numberOfTrailingZeros(mask);
        long normalized = mask >>> shift;
        long expected = (1L << lanes) - 1L;
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
