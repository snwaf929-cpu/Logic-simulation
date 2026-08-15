package com.foreverspark.logicsim.client.render;

/** Deterministic structural regression for the v6 single-word arbitrary-RGB sampler. */
public final class SingleWordRgbMaskSelfTest {
    private SingleWordRgbMaskSelfTest() {}

    public static void main(String[] args) {
        long mask = 0xFFFFL << 32;
        int threshold = 26; // Current compiler quantization for an approximately 10% RANDOM lane.
        long[] thresholdBitMasks = new long[8];
        for (int bit = 0; bit < 8; bit++) {
            if ((threshold & (1 << bit)) != 0) thresholdBitMasks[bit] = mask;
        }

        SingleWordRgbMaskSampler sampler = new SingleWordRgbMaskSampler(
                mask,
                thresholdBitMasks,
                0x0123_4567_89AB_CDEFL
        );

        check(sampler.laneCount() == 16, "all 16 RGB565 lanes must be represented");
        check(sampler.compact16(), "contiguous RGB565 lanes must use the 32 KiB compact table");
        check(sampler.compactShift() == 32, "RGB source shift mismatch");
        check(sampler.tableEntries() == 16_384, "v6 table must remain cache-sized");
        check(sampler.rngWordsPerSample() == 1, "all arbitrary RGB lanes must consume one 32-bit PRNG word");

        int expectedHits = threshold * SingleWordRgbMaskSampler.ENTRIES_PER_THRESHOLD_QUANTUM;
        for (int lane = 32; lane < 48; lane++) {
            long laneBit = 1L << lane;
            check(sampler.tableHitCount(laneBit) == expectedHits,
                    "lane " + lane + " changed the existing 8-bit probability quantum");
        }

        long observedMask = 0L;
        long nonZero = 0L;
        long nonBinary = 0L;
        long samples = 1_000_000L;
        long[] laneOnes = new long[16];
        for (long sample = 0; sample < samples; sample++) {
            long value = sampler.sampleMask();
            check((value & ~mask) == 0L, "sampler emitted bits outside COLOR mask");
            observedMask |= value;
            if (value != 0L) nonZero++;
            if (value != 0L && value != mask) nonBinary++;
            for (int bit = 0; bit < 16; bit++) {
                if ((value & (1L << (32 + bit))) != 0L) laneOnes[bit]++;
            }
        }
        check(observedMask == mask, "every RGB565 lane must be able to become high");
        check(nonZero > samples / 2, "10% per RGB bit should produce many non-black colors");
        check(nonBinary > samples / 2, "stress sampler must produce actual colors, not black/white only");

        double expected = threshold / 256.0;
        for (int bit = 0; bit < laneOnes.length; bit++) {
            double actual = laneOnes[bit] / (double) samples;
            check(Math.abs(actual - expected) < 0.004,
                    "lane " + bit + " probability drifted: expected=" + expected + " actual=" + actual);
        }

        System.out.println("Single-word 32-bit arbitrary-RGB mask v6 self-test: PASS"
                + " | lanes=" + sampler.laneCount()
                + " tableEntries=" + sampler.tableEntries()
                + " tableBytes=" + (sampler.tableEntries() * Character.BYTES)
                + " rngWordsPerColor=" + sampler.rngWordsPerSample()
                + " probability=" + String.format("%.6f", expected)
                + " samples=" + samples);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
