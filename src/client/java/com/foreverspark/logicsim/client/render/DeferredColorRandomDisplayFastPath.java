package com.foreverspark.logicsim.client.render;

import com.foreverspark.logicsim.core.CircuitSimulator;
import com.foreverspark.logicsim.core.TimingSignalDriver;
import com.foreverspark.logicsim.display.DisplayCommandCodec;
import com.foreverspark.logicsim.editor.runtime.CircuitTimingController;
import com.foreverspark.logicsim.interconnect.CircuitProgramRuntime;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * v5 specialization for physical DISPLAY workloads where arbitrary RANDOM probabilities are confined to RGB565.
 *
 * <p>The v3 compiler proves the important topology constraints first: one zero-gate CLOCK, one physical DISPLAY,
 * no root output observers, no NAND consumers of RANDOM outputs, and no RANDOM->RANDOM trigger dependency. This
 * specialization then separates CLOCK-driven coordinate/control RANDOM lanes from CLOCK-driven COLOR lanes.</p>
 *
 * <p>Arbitrary probabilities such as 10% force v3's generic sampler to generate eight 64-bit random planes for the
 * complete CLOCK group on every virtual cycle. That is unnecessary for DISPLAY-only COLOR. Coordinates are sampled
 * first with the cheap common-probability sampler. If the 2048x2048 coordinate prefilter rejects the write, the
 * intermediate COLOR value is unobservable and its arbitrary sampler is skipped. Accepted pixels use an exact packed
 * unsigned-byte threshold sampler: up to eight arbitrary COLOR lanes per RNG word instead of eight RNG words for the
 * whole 38-lane group. The final COLOR state is sampled once when the last virtual cycle was rejected so fallback and
 * editor-visible state still receive a statistically correct final value.</p>
 */
public final class DeferredColorRandomDisplayFastPath {
    private static final long PIXEL_OPCODE = (long) DisplayCommandCodec.OP_PIXEL << 48;
    private static final long DISPLAY_DATA_MASK = (1L << 48) - 1L;
    private static final long FIELD_MASK = 0xFFFFL;
    private static final long BYTE_HIGH_BITS = 0x8080808080808080L;
    private static final long BYTE_LOW_BITS = 0x7F7F7F7F7F7F7F7FL;
    private static final long RNG_NONZERO_FALLBACK = 0x9E3779B97F4A7C15L;
    private static final long RNG_SEED_GAMMA = 0x9E3779B97F4A7C15L;

    private static final Field BASE_SIMULATOR = field(IndependentRandomDisplayFastPath.Plan.class, "simulator");
    private static final Field BASE_CLOCK = field(IndependentRandomDisplayFastPath.Plan.class, "clock");
    private static final Field BASE_OUTPUT_IDS = field(IndependentRandomDisplayFastPath.Plan.class, "outputSignalIds");
    private static final Field BASE_CLOCK_GROUP = field(IndependentRandomDisplayFastPath.Plan.class, "clockGroup");
    private static final Field BASE_BOUNDARY_SCATTER = field(IndependentRandomDisplayFastPath.Plan.class, "boundaryScatter");
    private static final Field BASE_BOUNDARY_IDENTITY = field(IndependentRandomDisplayFastPath.Plan.class, "boundaryIdentity");
    private static final Field BASE_BOUNDARY_FIELD_SHIFT = field(IndependentRandomDisplayFastPath.Plan.class, "boundaryFieldShift");
    private static final Field BASE_COLOR_SHIFT = field(IndependentRandomDisplayFastPath.Plan.class, "colorSourceShift");
    private static final Field BASE_X_SHIFT = field(IndependentRandomDisplayFastPath.Plan.class, "xSourceShift");
    private static final Field BASE_Y_SHIFT = field(IndependentRandomDisplayFastPath.Plan.class, "ySourceShift");
    private static final Field BASE_EXACT_PREFILTER = field(IndependentRandomDisplayFastPath.Plan.class, "exactCoordinatePrefilter");
    private static final Field BASE_REJECT_MASK = field(IndependentRandomDisplayFastPath.Plan.class, "coordinateRejectLaneMask");
    private static final Field BASE_STATE_MASK = field(IndependentRandomDisplayFastPath.Plan.class, "stateMask");

    private static final Class<?> GROUP_CLASS = nested(
            "com.foreverspark.logicsim.client.render.IndependentRandomDisplayFastPath$GroupPlan"
    );
    private static final Field GROUP_OUTPUT_MASK = field(GROUP_CLASS, "outputMask");
    private static final Field GROUP_CHANCE25 = field(GROUP_CLASS, "chance25Mask");
    private static final Field GROUP_CHANCE50 = field(GROUP_CLASS, "chance50Mask");
    private static final Field GROUP_CHANCE75 = field(GROUP_CLASS, "chance75Mask");
    private static final Field GROUP_CHANCE100 = field(GROUP_CLASS, "chance100Mask");
    private static final Field GROUP_PROBABILISTIC = field(GROUP_CLASS, "probabilisticMask");
    private static final Field GROUP_THRESHOLD_BITS = field(GROUP_CLASS, "thresholdBitMasks");

    private DeferredColorRandomDisplayFastPath() {}

    public record CompileResult(Plan plan, String reason) {
        public boolean active() { return plan != null; }
    }

    public static CompileResult compile(
            CircuitProgramRuntime runtime,
            int deviceIndex,
            int displayWidth,
            int displayHeight
    ) {
        IndependentRandomDisplayFastPath.CompileResult baseResult = IndependentRandomDisplayFastPath.compile(
                runtime, deviceIndex, displayWidth, displayHeight
        );
        if (!baseResult.active()) return fail("v3:" + baseResult.reason());

        IndependentRandomDisplayFastPath.Plan base = baseResult.plan();
        try {
            Object group = BASE_CLOCK_GROUP.get(base);
            if (group == null || !GROUP_CLASS.isInstance(group)) return fail("clock-group-unresolved");

            long outputMask = GROUP_OUTPUT_MASK.getLong(group);
            long chance25 = GROUP_CHANCE25.getLong(group);
            long chance50 = GROUP_CHANCE50.getLong(group);
            long chance75 = GROUP_CHANCE75.getLong(group);
            long chance100 = GROUP_CHANCE100.getLong(group);
            long probabilistic = GROUP_PROBABILISTIC.getLong(group);
            long[] thresholdBitMasks = ((long[]) GROUP_THRESHOLD_BITS.get(group)).clone();

            boolean boundaryIdentity = BASE_BOUNDARY_IDENTITY.getBoolean(base);
            boolean boundaryFieldShift = BASE_BOUNDARY_FIELD_SHIFT.getBoolean(base);
            int colorSourceShift = BASE_COLOR_SHIFT.getInt(base);
            long[][] boundaryScatter = (long[][]) BASE_BOUNDARY_SCATTER.get(base);
            long colorLaneMask = colorLaneMask(
                    boundaryIdentity,
                    boundaryFieldShift,
                    colorSourceShift,
                    boundaryScatter
            );
            if (Long.bitCount(colorLaneMask) != 16) {
                return fail("color-lane-map-" + Long.bitCount(colorLaneMask));
            }

            long clockColorMask = outputMask & colorLaneMask;
            long clockNonColorMask = outputMask & ~colorLaneMask;
            if (clockColorMask == 0L) return fail("no-clock-color-lanes");
            if (clockNonColorMask == 0L) return fail("no-clock-coordinate-lanes");

            long commonProbabilistic = chance25 | chance50 | chance75;
            long arbitraryMask = probabilistic & ~commonProbabilistic;
            long arbitraryClockMask = arbitraryMask & outputMask;
            long arbitraryColorMask = arbitraryClockMask & colorLaneMask;
            long arbitraryNonColorMask = arbitraryClockMask & ~colorLaneMask;
            if (arbitraryColorMask == 0L) return fail("no-arbitrary-clock-color");
            if (arbitraryNonColorMask != 0L) {
                return fail("arbitrary-non-color-clock-lanes-" + Long.bitCount(arbitraryNonColorMask));
            }

            boolean exactCoordinatePrefilter = BASE_EXACT_PREFILTER.getBoolean(base);
            long coordinateRejectLaneMask = BASE_REJECT_MASK.getLong(base);
            if (!exactCoordinatePrefilter || coordinateRejectLaneMask == 0L) {
                return fail("coordinate-prefilter-required");
            }
            if ((coordinateRejectLaneMask & colorLaneMask) != 0L) {
                return fail("color-overlaps-coordinate-prefilter");
            }

            long nonColorSeed = seed(0x243F6A8885A308D3L);
            long colorSeed = seed(0x13198A2E03707344L);
            CommonSampler nonColorSampler = new CommonSampler(
                    clockNonColorMask,
                    chance25 & clockNonColorMask,
                    chance50 & clockNonColorMask,
                    chance75 & clockNonColorMask,
                    chance100 & clockNonColorMask,
                    nonColorSeed
            );
            ColorSampler colorSampler = new ColorSampler(
                    clockColorMask,
                    chance25 & clockColorMask,
                    chance50 & clockColorMask,
                    chance75 & clockColorMask,
                    chance100 & clockColorMask,
                    arbitraryColorMask,
                    thresholdBitMasks,
                    colorSeed
            );

            return new CompileResult(new Plan(
                    base,
                    runtime,
                    deviceIndex,
                    displayWidth,
                    displayHeight,
                    (CircuitSimulator) BASE_SIMULATOR.get(base),
                    (TimingSignalDriver) BASE_CLOCK.get(base),
                    ((int[]) BASE_OUTPUT_IDS.get(base)).clone(),
                    outputMask,
                    clockNonColorMask,
                    clockColorMask,
                    arbitraryColorMask,
                    nonColorSampler,
                    colorSampler,
                    boundaryScatter,
                    boundaryIdentity,
                    boundaryFieldShift,
                    colorSourceShift,
                    BASE_X_SHIFT.getInt(base),
                    BASE_Y_SHIFT.getInt(base),
                    coordinateRejectLaneMask
            ), "active-deferred-rgb:arbitraryColorLanes=" + Long.bitCount(arbitraryColorMask)
                    + ":colorChunks=" + colorSampler.arbitraryChunkCount()
                    + ":" + baseResult.reason());
        } catch (IllegalAccessException exception) {
            return fail("reflection-access:" + exception.getClass().getSimpleName());
        }
    }

    private static CompileResult fail(String reason) {
        return new CompileResult(null, reason);
    }

    public static final class Plan {
        private final IndependentRandomDisplayFastPath.Plan base;
        private final CircuitProgramRuntime runtime;
        private final int deviceIndex;
        private final int displayWidth;
        private final int displayHeight;
        private final CircuitSimulator simulator;
        private final TimingSignalDriver clock;
        private final int[] outputSignalIds;
        private final long clockOutputMask;
        private final long clockNonColorMask;
        private final long clockColorMask;
        private final long arbitraryColorMask;
        private final CommonSampler nonColorSampler;
        private final ColorSampler colorSampler;
        private final long[][] boundaryScatter;
        private final boolean boundaryIdentity;
        private final boolean boundaryFieldShift;
        private final int colorSourceShift;
        private final int xSourceShift;
        private final int ySourceShift;
        private final long coordinateRejectLaneMask;
        private final long clockCoordinateRejectMask;
        private long[] scratch = new long[65_536];
        private long lastClockCycles;
        private long lastColorSamples;
        private long lastDisplayWrites;

        private Plan(
                IndependentRandomDisplayFastPath.Plan base,
                CircuitProgramRuntime runtime,
                int deviceIndex,
                int displayWidth,
                int displayHeight,
                CircuitSimulator simulator,
                TimingSignalDriver clock,
                int[] outputSignalIds,
                long clockOutputMask,
                long clockNonColorMask,
                long clockColorMask,
                long arbitraryColorMask,
                CommonSampler nonColorSampler,
                ColorSampler colorSampler,
                long[][] boundaryScatter,
                boolean boundaryIdentity,
                boolean boundaryFieldShift,
                int colorSourceShift,
                int xSourceShift,
                int ySourceShift,
                long coordinateRejectLaneMask
        ) {
            this.base = base;
            this.runtime = runtime;
            this.deviceIndex = deviceIndex;
            this.displayWidth = displayWidth;
            this.displayHeight = displayHeight;
            this.simulator = simulator;
            this.clock = clock;
            this.outputSignalIds = outputSignalIds;
            this.clockOutputMask = clockOutputMask;
            this.clockNonColorMask = clockNonColorMask;
            this.clockColorMask = clockColorMask;
            this.arbitraryColorMask = arbitraryColorMask;
            this.nonColorSampler = nonColorSampler;
            this.colorSampler = colorSampler;
            this.boundaryScatter = boundaryScatter;
            this.boundaryIdentity = boundaryIdentity;
            this.boundaryFieldShift = boundaryFieldShift;
            this.colorSourceShift = colorSourceShift;
            this.xSourceShift = xSourceShift;
            this.ySourceShift = ySourceShift;
            this.coordinateRejectLaneMask = coordinateRejectLaneMask;
            this.clockCoordinateRejectMask = coordinateRejectLaneMask & clockNonColorMask;
        }

        public boolean matches(CircuitProgramRuntime candidate, int candidateDevice, int width, int height) {
            return candidate == runtime
                    && candidateDevice == deviceIndex
                    && width == displayWidth
                    && height == displayHeight;
        }

        public int randomLaneCount() { return outputSignalIds.length; }
        public int clockLaneCount() { return Long.bitCount(clockOutputMask); }
        public int hotNonColorLaneCount() { return Long.bitCount(clockNonColorMask); }
        public int deferredColorLaneCount() { return Long.bitCount(clockColorMask); }
        public int arbitraryColorLaneCount() { return Long.bitCount(arbitraryColorMask); }
        public int arbitraryColorChunkCount() { return colorSampler.arbitraryChunkCount(); }
        public int externalTriggerGroupCount() { return base.externalTriggerGroupCount(); }
        public int coordinatePrefilterLaneCount() { return Long.bitCount(coordinateRejectLaneMask); }
        public String boundaryPackMode() { return base.boundaryPackMode(); }
        public long lastClockCycles() { return lastClockCycles; }
        public long lastColorSamples() { return lastColorSamples; }
        public long lastDisplayWrites() { return lastDisplayWrites; }

        public long advance(long elapsedNanos, long edgeBudget, CircuitTimingController.LongBatchConsumer sink) {
            if (elapsedNanos < 0L || edgeBudget < 0L) {
                throw new IllegalArgumentException("clock arguments must be >= 0");
            }

            // Preserve v3's independent-trigger semantics. elapsed=0/budget=0 guarantees no MHz edge is consumed.
            base.advance(0L, 0L, null);
            long state = readBaseState();

            if (!clock.timing().running()) {
                lastClockCycles = 0L;
                lastColorSamples = 0L;
                lastDisplayWrites = 0L;
                return 0L;
            }

            long emitted = clock.advanceNanosPulseBatch(elapsedNanos, edgeBudget);
            long risingEdges = clock.lastPulseRisingEdges();
            if (risingEdges <= 0L) {
                lastClockCycles = 0L;
                lastColorSamples = 0L;
                lastDisplayWrites = 0L;
                return emitted;
            }
            if (risingEdges > Integer.MAX_VALUE) {
                throw new IllegalStateException("Deferred RGB display batch is too large: " + risingEdges);
            }

            int cycles = (int) risingEdges;
            ensureScratch(cycles);
            long preserved = state & ~clockOutputMask;
            long finalNonColor = state & clockNonColorMask;
            long finalColor = state & clockColorMask;
            boolean fixedCoordinateReject = (preserved & coordinateRejectLaneMask) != 0L;
            boolean colorSampledOnLastCycle = false;
            int outputCount = 0;
            long colorSamples = 0L;

            for (int cycle = 0; cycle < cycles; cycle++) {
                finalNonColor = nonColorSampler.sample();
                boolean rejected = fixedCoordinateReject
                        || (finalNonColor & clockCoordinateRejectMask) != 0L;
                if (rejected) {
                    colorSampledOnLastCycle = false;
                    continue;
                }

                finalColor = colorSampler.sample();
                colorSamples++;
                colorSampledOnLastCycle = true;
                long finalState = preserved | finalNonColor | finalColor;
                scratch[outputCount++] = PIXEL_OPCODE | packBoundary(finalState);
            }

            // Intermediate COLOR values on rejected DISPLAY writes are unobservable, but keep the final RANDOM state
            // statistically correct if the final virtual cycle itself was rejected.
            if (!colorSampledOnLastCycle) {
                finalColor = colorSampler.sample();
                colorSamples++;
            }

            long finalState = preserved | finalNonColor | finalColor;
            commitState(finalState);
            if (sink != null && outputCount > 0) sink.accept(scratch, outputCount);

            lastClockCycles = cycles;
            lastColorSamples = colorSamples;
            lastDisplayWrites = outputCount;
            return emitted;
        }

        public void synchronizeFallback() {
            base.synchronizeFallback();
        }

        private long packBoundary(long state) {
            if (boundaryIdentity) return state & DISPLAY_DATA_MASK;
            if (boundaryFieldShift) {
                long color = (state >>> colorSourceShift) & FIELD_MASK;
                long x = (state >>> xSourceShift) & FIELD_MASK;
                long y = (state >>> ySourceShift) & FIELD_MASK;
                return color | (x << 16) | (y << 32);
            }
            return scatter(boundaryScatter, state);
        }

        private void commitState(long state) {
            long previous = readBaseState();
            if (state != previous) {
                simulator.driveBitVectorFast(outputSignalIds, 0, outputSignalIds.length, state);
            }
            writeBaseState(state);
        }

        private long readBaseState() {
            try {
                return BASE_STATE_MASK.getLong(base);
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("Could not read v3 packed RANDOM state", exception);
            }
        }

        private void writeBaseState(long state) {
            try {
                BASE_STATE_MASK.setLong(base, state);
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("Could not synchronize v3 packed RANDOM state", exception);
            }
        }

        private void ensureScratch(int count) {
            if (count <= scratch.length) return;
            int next = scratch.length;
            while (next < count) next = Math.max(next + 1, next << 1);
            scratch = new long[next];
        }
    }

    /** Cheap sampler used only for CLOCK lanes proven to use 0/25/50/75/100% probabilities. */
    private static final class CommonSampler {
        private final long outputMask;
        private final long chance25Mask;
        private final long chance50Mask;
        private final long chance75Mask;
        private final long chance100Mask;
        private final long activeMask;
        private final boolean needsSecondWord;
        private long rng0;
        private long rng1;

        private CommonSampler(
                long outputMask,
                long chance25Mask,
                long chance50Mask,
                long chance75Mask,
                long chance100Mask,
                long seed
        ) {
            this.outputMask = outputMask;
            this.chance25Mask = chance25Mask;
            this.chance50Mask = chance50Mask;
            this.chance75Mask = chance75Mask;
            this.chance100Mask = chance100Mask;
            this.activeMask = chance25Mask | chance50Mask | chance75Mask;
            this.needsSecondWord = (chance25Mask | chance75Mask) != 0L;
            this.rng0 = mix64(seed);
            this.rng1 = mix64(seed + RNG_SEED_GAMMA);
            if ((rng0 | rng1) == 0L) rng1 = RNG_NONZERO_FALLBACK;
        }

        private long sample() {
            long result = chance100Mask;
            if (activeMask != 0L) {
                long r0 = nextLong();
                result |= r0 & chance50Mask;
                if (needsSecondWord) {
                    long r1 = nextLong();
                    result |= (r0 & r1) & chance25Mask;
                    result |= (r0 | r1) & chance75Mask;
                }
            }
            return result & outputMask;
        }

        private long nextLong() {
            long s0 = rng0;
            long s1 = rng1;
            long result = Long.rotateLeft(s0 + s1, 17) + s0;
            s1 ^= s0;
            rng0 = Long.rotateLeft(s0, 49) ^ s1 ^ (s1 << 21);
            rng1 = Long.rotateLeft(s1, 28);
            return result;
        }
    }

    /** COLOR sampler: common probabilities stay bitwise; arbitrary lanes use packed unsigned-byte thresholds. */
    private static final class ColorSampler {
        private final long outputMask;
        private final long chance25Mask;
        private final long chance50Mask;
        private final long chance75Mask;
        private final long chance100Mask;
        private final long activeCommonMask;
        private final boolean needsSecondCommonWord;
        private final ArbitraryChunk[] arbitraryChunks;
        private long rng0;
        private long rng1;

        private ColorSampler(
                long outputMask,
                long chance25Mask,
                long chance50Mask,
                long chance75Mask,
                long chance100Mask,
                long arbitraryMask,
                long[] thresholdBitMasks,
                long seed
        ) {
            this.outputMask = outputMask;
            this.chance25Mask = chance25Mask;
            this.chance50Mask = chance50Mask;
            this.chance75Mask = chance75Mask;
            this.chance100Mask = chance100Mask;
            this.activeCommonMask = chance25Mask | chance50Mask | chance75Mask;
            this.needsSecondCommonWord = (chance25Mask | chance75Mask) != 0L;
            this.arbitraryChunks = arbitraryChunks(arbitraryMask, thresholdBitMasks);
            this.rng0 = mix64(seed);
            this.rng1 = mix64(seed + RNG_SEED_GAMMA);
            if ((rng0 | rng1) == 0L) rng1 = RNG_NONZERO_FALLBACK;
        }

        private int arbitraryChunkCount() { return arbitraryChunks.length; }

        private long sample() {
            long result = chance100Mask;
            if (activeCommonMask != 0L) {
                long r0 = nextLong();
                result |= r0 & chance50Mask;
                if (needsSecondCommonWord) {
                    long r1 = nextLong();
                    result |= (r0 & r1) & chance25Mask;
                    result |= (r0 | r1) & chance75Mask;
                }
            }

            for (ArbitraryChunk chunk : arbitraryChunks) {
                long highBits = unsignedByteLessThan(nextLong(), chunk.thresholdBytes);
                long compact = Long.compress(highBits, BYTE_HIGH_BITS);
                result |= Long.expand(compact, chunk.laneMask);
            }
            return result & outputMask;
        }

        private long nextLong() {
            long s0 = rng0;
            long s1 = rng1;
            long result = Long.rotateLeft(s0 + s1, 17) + s0;
            s1 ^= s0;
            rng0 = Long.rotateLeft(s0, 49) ^ s1 ^ (s1 << 21);
            rng1 = Long.rotateLeft(s1, 28);
            return result;
        }
    }

    private record ArbitraryChunk(long laneMask, long thresholdBytes) {}

    private static ArbitraryChunk[] arbitraryChunks(long arbitraryMask, long[] thresholdBitMasks) {
        if (arbitraryMask == 0L) return new ArbitraryChunk[0];
        List<ArbitraryChunk> chunks = new ArrayList<>(2);
        long remaining = arbitraryMask;
        while (remaining != 0L) {
            long laneMask = 0L;
            long thresholds = 0L;
            int count = 0;
            while (remaining != 0L && count < 8) {
                int lane = Long.numberOfTrailingZeros(remaining);
                long laneBit = 1L << lane;
                remaining &= ~laneBit;
                laneMask |= laneBit;
                int threshold = thresholdForLane(thresholdBitMasks, laneBit);
                thresholds |= (long) (threshold & 0xFF) << (count * 8);
                count++;
            }
            chunks.add(new ArbitraryChunk(laneMask, thresholds));
        }
        return chunks.toArray(ArbitraryChunk[]::new);
    }

    private static int thresholdForLane(long[] thresholdBitMasks, long laneBit) {
        int threshold = 0;
        for (int bit = 0; bit < 8; bit++) {
            if ((thresholdBitMasks[bit] & laneBit) != 0L) threshold |= 1 << bit;
        }
        return threshold;
    }

    /**
     * Returns 0x80 in each byte where unsigned(randomByte) < unsigned(thresholdByte).
     * Every minuend low-7-bit byte receives a sentinel high bit, so subtraction cannot borrow across byte lanes.
     */
    static long unsignedByteLessThan(long randomBytes, long thresholdBytes) {
        long randomHigh = randomBytes & BYTE_HIGH_BITS;
        long thresholdHigh = thresholdBytes & BYTE_HIGH_BITS;
        long lowDiff = ((randomBytes & BYTE_LOW_BITS) | BYTE_HIGH_BITS) - (thresholdBytes & BYTE_LOW_BITS);
        long lowLess = ~lowDiff & BYTE_HIGH_BITS;
        long highLess = ~randomHigh & thresholdHigh;
        long sameHigh = ~(randomHigh ^ thresholdHigh) & BYTE_HIGH_BITS;
        return highLess | (sameHigh & lowLess);
    }

    private static long colorLaneMask(
            boolean boundaryIdentity,
            boolean boundaryFieldShift,
            int colorSourceShift,
            long[][] boundaryScatter
    ) {
        if (boundaryIdentity) return FIELD_MASK;
        if (boundaryFieldShift) return FIELD_MASK << colorSourceShift;
        if (boundaryScatter == null) return 0L;

        long result = 0L;
        for (int lane = 0; lane < 48; lane++) {
            long laneBit = 1L << lane;
            if ((scatter(boundaryScatter, laneBit) & FIELD_MASK) != 0L) result |= laneBit;
        }
        return result;
    }

    private static long scatter(long[][] tables, long laneMask) {
        long result = 0L;
        for (int chunk = 0; chunk < tables.length; chunk++) {
            result |= tables[chunk][(int) ((laneMask >>> (chunk * 8)) & 0xFFL)];
        }
        return result;
    }

    private static Class<?> nested(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field result = owner.getDeclaredField(name);
            result.setAccessible(true);
            return result;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static long seed(long salt) {
        return mix64(System.nanoTime() ^ Thread.currentThread().threadId() ^ salt);
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
