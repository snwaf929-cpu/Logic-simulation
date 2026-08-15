package com.foreverspark.logicsim.client.render;

import com.foreverspark.logicsim.core.CircuitSimulator;
import com.foreverspark.logicsim.core.TimingSignalDriver;
import com.foreverspark.logicsim.display.DisplayCommandCodec;
import com.foreverspark.logicsim.editor.runtime.CircuitTimingController;
import com.foreverspark.logicsim.interconnect.CircuitProgramRuntime;

import java.lang.reflect.Field;

/**
 * v4 specialization layered on top of {@link IndependentRandomDisplayFastPath}.
 *
 * <p>The v3 compiler already proves the important semantics: one zero-gate CLOCK-triggered RANDOM group, every other
 * RANDOM trigger independent of the MHz clock, one physical DISPLAY, no root outputs, dynamic RESET handled outside
 * the hot loop, and no NAND consumers. v4 reuses that proof and replaces only the per-clock RANDOM sampler.</p>
 *
 * <p>For the common 25/50/75% case, each 50% lane needs one random bit while 25/75% lanes need two. The user's real
 * 38-lane clock group therefore needs only 63 random bits/cycle. v3 generated two 64-bit xoroshiro words (128 bits)
 * every cycle. v4 generates one 64-bit xorshift64* word and uses Long.expand to place those independent bits directly
 * into the packed 48-lane state. On modern x86 JVMs Long.expand can use BMI2/PDEP, removing roughly half of the RNG
 * work from the hottest loop while preserving exact 25/50/75 probabilities.</p>
 */
public final class DenseIndependentRandomDisplayFastPath {
    private static final long PIXEL_OPCODE = (long) DisplayCommandCodec.OP_PIXEL << 48;
    private static final long DISPLAY_DATA_MASK = (1L << 48) - 1L;
    private static final long FIELD_MASK = 0xFFFFL;
    private static final long XORSHIFT64_STAR = 0x2545F4914F6CDD1DL;
    private static final long NONZERO_SEED = 0x9E3779B97F4A7C15L;

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

    private static final Class<?> GROUP_CLASS = nested("com.foreverspark.logicsim.client.render.IndependentRandomDisplayFastPath$GroupPlan");
    private static final Field GROUP_OUTPUT_MASK = field(GROUP_CLASS, "outputMask");
    private static final Field GROUP_PRESERVE_MASK = field(GROUP_CLASS, "preserveMask");
    private static final Field GROUP_CHANCE25 = field(GROUP_CLASS, "chance25Mask");
    private static final Field GROUP_CHANCE50 = field(GROUP_CLASS, "chance50Mask");
    private static final Field GROUP_CHANCE75 = field(GROUP_CLASS, "chance75Mask");
    private static final Field GROUP_CHANCE100 = field(GROUP_CLASS, "chance100Mask");
    private static final Field GROUP_COMMON = field(GROUP_CLASS, "commonChanceFastPath");

    private DenseIndependentRandomDisplayFastPath() {}

    public record CompileResult(Plan plan, String reason) {
        public boolean active() { return plan != null; }
    }

    public static CompileResult compile(CircuitProgramRuntime runtime, int deviceIndex, int displayWidth, int displayHeight) {
        IndependentRandomDisplayFastPath.CompileResult baseResult = IndependentRandomDisplayFastPath.compile(
                runtime, deviceIndex, displayWidth, displayHeight
        );
        if (!baseResult.active()) return fail("v3:" + baseResult.reason());

        IndependentRandomDisplayFastPath.Plan base = baseResult.plan();
        try {
            Object group = BASE_CLOCK_GROUP.get(base);
            if (group == null || !GROUP_CLASS.isInstance(group)) return fail("clock-group-unresolved");
            if (!GROUP_COMMON.getBoolean(group)) return fail("clock-group-arbitrary-probability");

            long outputMask = GROUP_OUTPUT_MASK.getLong(group);
            long preserveMask = GROUP_PRESERVE_MASK.getLong(group);
            long chance25 = GROUP_CHANCE25.getLong(group);
            long chance50 = GROUP_CHANCE50.getLong(group);
            long chance75 = GROUP_CHANCE75.getLong(group);
            long chance100 = GROUP_CHANCE100.getLong(group);
            long firstDecisionMask = chance25 | chance50 | chance75;
            long secondDecisionMask = chance25 | chance75;
            int firstBits = Long.bitCount(firstDecisionMask);
            int secondBits = Long.bitCount(secondDecisionMask);
            int randomBits = firstBits + secondBits;
            if (randomBits <= 0 || randomBits > 64) {
                return fail("clock-random-bit-budget-" + randomBits);
            }

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
                    preserveMask,
                    chance25,
                    chance50,
                    chance75,
                    chance100,
                    firstDecisionMask,
                    secondDecisionMask,
                    firstBits,
                    randomBits,
                    (long[][]) BASE_BOUNDARY_SCATTER.get(base),
                    BASE_BOUNDARY_IDENTITY.getBoolean(base),
                    BASE_BOUNDARY_FIELD_SHIFT.getBoolean(base),
                    BASE_COLOR_SHIFT.getInt(base),
                    BASE_X_SHIFT.getInt(base),
                    BASE_Y_SHIFT.getInt(base),
                    BASE_EXACT_PREFILTER.getBoolean(base),
                    BASE_REJECT_MASK.getLong(base),
                    seed()
            ), "active-dense-one-word:randomBits=" + randomBits + ":" + baseResult.reason());
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
        private final long outputMask;
        private final long preserveMask;
        private final long chance25Mask;
        private final long chance50Mask;
        private final long chance75Mask;
        private final long chance100Mask;
        private final long firstDecisionMask;
        private final long secondDecisionMask;
        private final int firstDecisionBits;
        private final int randomBitsPerCycle;
        private final long[][] boundaryScatter;
        private final boolean boundaryIdentity;
        private final boolean boundaryFieldShift;
        private final int colorSourceShift;
        private final int xSourceShift;
        private final int ySourceShift;
        private final boolean exactCoordinatePrefilter;
        private final long coordinateRejectLaneMask;
        private final long clockCoordinateRejectMask;
        private long[] scratch = new long[131_072];
        private long rngState;

        private Plan(
                IndependentRandomDisplayFastPath.Plan base,
                CircuitProgramRuntime runtime,
                int deviceIndex,
                int displayWidth,
                int displayHeight,
                CircuitSimulator simulator,
                TimingSignalDriver clock,
                int[] outputSignalIds,
                long outputMask,
                long preserveMask,
                long chance25Mask,
                long chance50Mask,
                long chance75Mask,
                long chance100Mask,
                long firstDecisionMask,
                long secondDecisionMask,
                int firstDecisionBits,
                int randomBitsPerCycle,
                long[][] boundaryScatter,
                boolean boundaryIdentity,
                boolean boundaryFieldShift,
                int colorSourceShift,
                int xSourceShift,
                int ySourceShift,
                boolean exactCoordinatePrefilter,
                long coordinateRejectLaneMask,
                long seed
        ) {
            this.base = base;
            this.runtime = runtime;
            this.deviceIndex = deviceIndex;
            this.displayWidth = displayWidth;
            this.displayHeight = displayHeight;
            this.simulator = simulator;
            this.clock = clock;
            this.outputSignalIds = outputSignalIds;
            this.outputMask = outputMask;
            this.preserveMask = preserveMask;
            this.chance25Mask = chance25Mask;
            this.chance50Mask = chance50Mask;
            this.chance75Mask = chance75Mask;
            this.chance100Mask = chance100Mask;
            this.firstDecisionMask = firstDecisionMask;
            this.secondDecisionMask = secondDecisionMask;
            this.firstDecisionBits = firstDecisionBits;
            this.randomBitsPerCycle = randomBitsPerCycle;
            this.boundaryScatter = boundaryScatter;
            this.boundaryIdentity = boundaryIdentity;
            this.boundaryFieldShift = boundaryFieldShift;
            this.colorSourceShift = colorSourceShift;
            this.xSourceShift = xSourceShift;
            this.ySourceShift = ySourceShift;
            this.exactCoordinatePrefilter = exactCoordinatePrefilter;
            this.coordinateRejectLaneMask = coordinateRejectLaneMask;
            this.clockCoordinateRejectMask = coordinateRejectLaneMask & outputMask;
            this.rngState = seed == 0L ? NONZERO_SEED : seed;
        }

        public boolean matches(CircuitProgramRuntime candidate, int candidateDevice, int width, int height) {
            return candidate == runtime
                    && candidateDevice == deviceIndex
                    && width == displayWidth
                    && height == displayHeight;
        }

        public int randomLaneCount() { return outputSignalIds.length; }
        public int clockLaneCount() { return Long.bitCount(outputMask); }
        public int externalTriggerGroupCount() { return base.externalTriggerGroupCount(); }
        public int randomBitsPerCycle() { return randomBitsPerCycle; }
        public int rngWordsPerCycle() { return 1; }
        public boolean coordinatePrefilterEnabled() { return exactCoordinatePrefilter && coordinateRejectLaneMask != 0L; }
        public int coordinatePrefilterLaneCount() { return Long.bitCount(coordinateRejectLaneMask); }
        public String boundaryPackMode() { return base.boundaryPackMode(); }

        public long advance(long elapsedNanos, long edgeBudget, CircuitTimingController.LongBatchConsumer sink) {
            if (elapsedNanos < 0L || edgeBudget < 0L) throw new IllegalArgumentException("clock arguments must be >= 0");

            // Let v3 poll only the independent trigger groups. elapsed=0/budget=0 guarantees no MHz clock edge is
            // consumed here. The resulting packed state is then imported once for the dense clock batch.
            base.advance(0L, 0L, null);
            long state = readBaseState();

            if (!clock.timing().running()) return 0L;

            long emitted = clock.advanceNanosPulseBatch(elapsedNanos, edgeBudget);
            long risingEdges = clock.lastPulseRisingEdges();
            if (risingEdges <= 0L) return emitted;
            if (risingEdges > Integer.MAX_VALUE) {
                throw new IllegalStateException("Dense RANDOM display batch is too large: " + risingEdges);
            }

            int cycles = (int) risingEdges;
            ensureScratch(cycles);
            int outputCount = 0;

            // Independent RANDOM groups do not change during this MHz batch. Keep their lanes once instead of masking
            // the entire 48-bit state on every virtual cycle.
            final long preserved = state & preserveMask;
            final boolean fixedCoordinateReject = exactCoordinatePrefilter
                    && (preserved & coordinateRejectLaneMask) != 0L;
            long rng = rngState;
            long finalState = state;

            if (fixedCoordinateReject) {
                // Still advance every RANDOM sample exactly; only DISPLAY packing/publication is impossible this batch.
                for (int cycle = 0; cycle < cycles; cycle++) {
                    rng = nextState(rng);
                    long bits = rng * XORSHIFT64_STAR;
                    finalState = preserved | sampleClock(bits);
                }
            } else if (exactCoordinatePrefilter && clockCoordinateRejectMask != 0L) {
                for (int cycle = 0; cycle < cycles; cycle++) {
                    rng = nextState(rng);
                    long bits = rng * XORSHIFT64_STAR;
                    long sampled = sampleClock(bits);
                    finalState = preserved | sampled;
                    if ((sampled & clockCoordinateRejectMask) != 0L) continue;
                    scratch[outputCount++] = PIXEL_OPCODE | packBoundary(finalState);
                }
            } else if (exactCoordinatePrefilter) {
                for (int cycle = 0; cycle < cycles; cycle++) {
                    rng = nextState(rng);
                    long bits = rng * XORSHIFT64_STAR;
                    finalState = preserved | sampleClock(bits);
                    scratch[outputCount++] = PIXEL_OPCODE | packBoundary(finalState);
                }
            } else {
                for (int cycle = 0; cycle < cycles; cycle++) {
                    rng = nextState(rng);
                    long bits = rng * XORSHIFT64_STAR;
                    finalState = preserved | sampleClock(bits);
                    long raw = PIXEL_OPCODE | packBoundary(finalState);
                    int x = (int) ((raw >>> 16) & FIELD_MASK);
                    int y = (int) ((raw >>> 32) & FIELD_MASK);
                    if (x < displayWidth && y < displayHeight) scratch[outputCount++] = raw;
                }
            }

            rngState = rng == 0L ? NONZERO_SEED : rng;
            commitState(finalState);
            if (sink != null && outputCount > 0) sink.accept(scratch, outputCount);
            return emitted;
        }

        public void synchronizeFallback() {
            base.synchronizeFallback();
        }

        private long sampleClock(long randomBits) {
            long first = Long.expand(randomBits, firstDecisionMask);
            long secondSource = firstDecisionBits >= 64 ? 0L : randomBits >>> firstDecisionBits;
            long second = Long.expand(secondSource, secondDecisionMask);
            long result = chance100Mask;
            result |= first & chance50Mask;
            result |= (first & second) & chance25Mask;
            result |= (first | second) & chance75Mask;
            return result & outputMask;
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

    /** xorshift64* transition: only three xor/shift pairs per generated 64-bit decision word. */
    private static long nextState(long state) {
        long x = state == 0L ? NONZERO_SEED : state;
        x ^= x >>> 12;
        x ^= x << 25;
        x ^= x >>> 27;
        return x;
    }

    private static long scatter(long[][] tables, long laneMask) {
        long result = 0L;
        for (int chunk = 0; chunk < tables.length; chunk++) {
            result |= tables[chunk][(int) ((laneMask >>> (chunk * 8)) & 0xFFL)];
        }
        return result;
    }

    private static long seed() {
        long value = mix64(System.nanoTime() ^ Thread.currentThread().threadId());
        return value == 0L ? NONZERO_SEED : value;
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
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
}
