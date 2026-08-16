package com.foreverspark.logicsim.client.render;

import com.foreverspark.logicsim.block.CircuitBlockEntity;
import com.foreverspark.logicsim.block.CircuitSimulationWorker;
import com.foreverspark.logicsim.core.TimingSignalDriver;
import com.foreverspark.logicsim.display.DisplayCommandCodec;
import com.foreverspark.logicsim.editor.runtime.CircuitTimingController;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Parallel companion for IndependentRandomDisplayFastPath when the CLOCK-triggered RANDOM group uses only the
 * common 0/25/50/75/100% probabilities.
 *
 * <p>The v3 compiler already proves there are no RANDOM->RANDOM trigger dependencies and no NAND consumers of RANDOM
 * outputs. That makes every CLOCK sample independent once external-trigger state is captured at the start of a worker
 * slice. This plan replaces the mutable per-cycle PRNG with a counter-addressed permutation, splits a batch into stable
 * worker ranges, then publishes range buffers in CLOCK order.</p>
 *
 * <p>This path intentionally keeps framebuffer publication serial for now. On 2048x2048 random-coordinate workloads,
 * the coordinate prefilter rejects almost every sample, so the dominant cost is RANDOM generation rather than the
 * small accepted-pixel commit. The existing v11 deferred-color path remains the denser two-stage framebuffer engine.</p>
 */
public final class ParallelIndependentRandomDisplayFastPath {
    private static final long PIXEL_OPCODE = (long) DisplayCommandCodec.OP_PIXEL << 48;
    private static final long DISPLAY_DATA_MASK = (1L << 48) - 1L;
    private static final long FIELD_MASK = 0xFFFFL;
    private static final int MIN_CYCLES_PER_WORKER = 8_192;
    private static final int MAX_WORKERS = 64;

    private static final Class<?> SOURCE_PLAN = IndependentRandomDisplayFastPath.Plan.class;
    private static final Field SOURCE_CLOCK = field(SOURCE_PLAN, "clock");
    private static final Field SOURCE_CLOCK_GROUP = field(SOURCE_PLAN, "clockGroup");
    private static final Field SOURCE_BOUNDARY_SCATTER = field(SOURCE_PLAN, "boundaryScatter");
    private static final Field SOURCE_BOUNDARY_IDENTITY = field(SOURCE_PLAN, "boundaryIdentity");
    private static final Field SOURCE_BOUNDARY_FIELD_SHIFT = field(SOURCE_PLAN, "boundaryFieldShift");
    private static final Field SOURCE_COLOR_SHIFT = field(SOURCE_PLAN, "colorSourceShift");
    private static final Field SOURCE_X_SHIFT = field(SOURCE_PLAN, "xSourceShift");
    private static final Field SOURCE_Y_SHIFT = field(SOURCE_PLAN, "ySourceShift");
    private static final Field SOURCE_EXACT_PREFILTER = field(SOURCE_PLAN, "exactCoordinatePrefilter");
    private static final Field SOURCE_COORDINATE_REJECT_MASK = field(SOURCE_PLAN, "coordinateRejectLaneMask");
    private static final Field SOURCE_DISPLAY_WIDTH = field(SOURCE_PLAN, "displayWidth");
    private static final Field SOURCE_DISPLAY_HEIGHT = field(SOURCE_PLAN, "displayHeight");
    private static final Field SOURCE_STATE_MASK = field(SOURCE_PLAN, "stateMask");
    private static final Method SOURCE_COMMIT_STATE = method(SOURCE_PLAN, "commitState", long.class);

    private static final Class<?> GROUP_CLASS = nested(
            "com.foreverspark.logicsim.client.render.IndependentRandomDisplayFastPath$GroupPlan"
    );
    private static final Field GROUP_OUTPUT_MASK = field(GROUP_CLASS, "outputMask");
    private static final Field GROUP_CHANCE25 = field(GROUP_CLASS, "chance25Mask");
    private static final Field GROUP_CHANCE50 = field(GROUP_CLASS, "chance50Mask");
    private static final Field GROUP_CHANCE75 = field(GROUP_CLASS, "chance75Mask");
    private static final Field GROUP_CHANCE100 = field(GROUP_CLASS, "chance100Mask");
    private static final Field GROUP_COMMON_FAST_PATH = field(GROUP_CLASS, "commonChanceFastPath");

    private ParallelIndependentRandomDisplayFastPath() {}

    public record CompileResult(Plan plan, String reason) {
        public boolean active() { return plan != null; }
    }

    public static CompileResult compile(IndependentRandomDisplayFastPath.Plan source) {
        if (source == null) return new CompileResult(null, "source-plan-missing");
        try {
            Object group = SOURCE_CLOCK_GROUP.get(source);
            if (group == null || !GROUP_CLASS.isInstance(group)) {
                return new CompileResult(null, "clock-group-unresolved");
            }
            if (!GROUP_COMMON_FAST_PATH.getBoolean(group)) {
                return new CompileResult(null, "clock-group-has-arbitrary-probability");
            }

            long outputMask = GROUP_OUTPUT_MASK.getLong(group);
            if (outputMask == 0L) return new CompileResult(null, "clock-group-empty");

            long chance25 = GROUP_CHANCE25.getLong(group) & outputMask;
            long chance50 = GROUP_CHANCE50.getLong(group) & outputMask;
            long chance75 = GROUP_CHANCE75.getLong(group) & outputMask;
            long chance100 = GROUP_CHANCE100.getLong(group) & outputMask;
            long coordinateRejectMask = SOURCE_COORDINATE_REJECT_MASK.getLong(source);

            Plan plan = new Plan(
                    source,
                    (TimingSignalDriver) SOURCE_CLOCK.get(source),
                    outputMask,
                    chance25,
                    chance50,
                    chance75,
                    chance100,
                    (long[][]) SOURCE_BOUNDARY_SCATTER.get(source),
                    SOURCE_BOUNDARY_IDENTITY.getBoolean(source),
                    SOURCE_BOUNDARY_FIELD_SHIFT.getBoolean(source),
                    SOURCE_COLOR_SHIFT.getInt(source),
                    SOURCE_X_SHIFT.getInt(source),
                    SOURCE_Y_SHIFT.getInt(source),
                    SOURCE_EXACT_PREFILTER.getBoolean(source),
                    coordinateRejectMask,
                    coordinateRejectMask & outputMask,
                    SOURCE_DISPLAY_WIDTH.getInt(source),
                    SOURCE_DISPLAY_HEIGHT.getInt(source),
                    seed(0x8CB92BA72F3D8DD7L)
            );
            return new CompileResult(
                    plan,
                    "active-parallel-common-v12:clockLanes=" + Long.bitCount(outputMask)
            );
        } catch (IllegalAccessException exception) {
            return new CompileResult(null, "reflection-access:" + exception.getClass().getSimpleName());
        }
    }

    public static final class Plan {
        private static final long COMMON_SALT0 = 0x9E3779B97F4A7C15L;
        private static final long COMMON_SALT1 = 0xD1B54A32D192ED03L;

        private final IndependentRandomDisplayFastPath.Plan source;
        private final TimingSignalDriver clock;
        private final long clockOutputMask;
        private final long chance25;
        private final long chance50;
        private final long chance75;
        private final long chance100;
        private final long activeCommon;
        private final boolean needsSecondWord;
        private final long[][] boundaryScatter;
        private final boolean boundaryIdentity;
        private final boolean boundaryFieldShift;
        private final int colorSourceShift;
        private final int xSourceShift;
        private final int ySourceShift;
        private final boolean exactCoordinatePrefilter;
        private final long coordinateRejectLaneMask;
        private final long clockCoordinateRejectMask;
        private final int displayWidth;
        private final int displayHeight;

        private final long[][] scratchByWorker = new long[MAX_WORKERS][];
        private final int[] outputCounts = new int[MAX_WORKERS];

        private long sequence;
        private long lastClockCycles;
        private long lastDisplayWrites;
        private int lastParallelWorkers = 1;

        private Plan(
                IndependentRandomDisplayFastPath.Plan source,
                TimingSignalDriver clock,
                long clockOutputMask,
                long chance25,
                long chance50,
                long chance75,
                long chance100,
                long[][] boundaryScatter,
                boolean boundaryIdentity,
                boolean boundaryFieldShift,
                int colorSourceShift,
                int xSourceShift,
                int ySourceShift,
                boolean exactCoordinatePrefilter,
                long coordinateRejectLaneMask,
                long clockCoordinateRejectMask,
                int displayWidth,
                int displayHeight,
                long seed
        ) {
            this.source = source;
            this.clock = clock;
            this.clockOutputMask = clockOutputMask;
            this.chance25 = chance25;
            this.chance50 = chance50;
            this.chance75 = chance75;
            this.chance100 = chance100;
            this.activeCommon = chance25 | chance50 | chance75;
            this.needsSecondWord = (chance25 | chance75) != 0L;
            this.boundaryScatter = boundaryScatter;
            this.boundaryIdentity = boundaryIdentity;
            this.boundaryFieldShift = boundaryFieldShift;
            this.colorSourceShift = colorSourceShift;
            this.xSourceShift = xSourceShift;
            this.ySourceShift = ySourceShift;
            this.exactCoordinatePrefilter = exactCoordinatePrefilter;
            this.coordinateRejectLaneMask = coordinateRejectLaneMask;
            this.clockCoordinateRejectMask = clockCoordinateRejectMask;
            this.displayWidth = displayWidth;
            this.displayHeight = displayHeight;
            this.sequence = seed;
        }

        public long advance(
                CircuitBlockEntity circuit,
                long elapsedNanos,
                long edgeBudget,
                CircuitTimingController.LongBatchConsumer sink
        ) {
            if (circuit == null) throw new IllegalArgumentException("Circuit Block is required");
            if (elapsedNanos < 0L || edgeBudget < 0L) {
                throw new IllegalArgumentException("clock arguments must be >= 0");
            }

            // Preserve v3's independent external-trigger edge detection without consuming any CLOCK work.
            source.advance(0L, 0L, null);
            long state = readSourceState(source);

            if (!clock.timing().running()) {
                lastClockCycles = 0L;
                lastDisplayWrites = 0L;
                return 0L;
            }

            long emitted = clock.advanceNanosPulseBatch(elapsedNanos, edgeBudget);
            long risingEdges = clock.lastPulseRisingEdges();
            if (risingEdges <= 0L) {
                lastClockCycles = 0L;
                lastDisplayWrites = 0L;
                return emitted;
            }
            if (risingEdges > Integer.MAX_VALUE) {
                throw new IllegalStateException("Parallel independent RANDOM batch is unexpectedly large: " + risingEdges);
            }

            int cycles = (int) risingEdges;
            long counterBase = sequence;
            long preserved = state & ~clockOutputMask;
            boolean fixedCoordinateReject = exactCoordinatePrefilter
                    && (preserved & coordinateRejectLaneMask) != 0L;

            int ranges = CircuitSimulationWorker.runParallelRanges(
                    circuit,
                    cycles,
                    MIN_CYCLES_PER_WORKER,
                    (taskIndex, start, end) -> runRange(
                            taskIndex,
                            start,
                            end,
                            counterBase,
                            preserved,
                            fixedCoordinateReject
                    )
            );

            long displayWrites = 0L;
            if (sink != null) {
                for (int taskIndex = 0; taskIndex < ranges; taskIndex++) {
                    int count = outputCounts[taskIndex];
                    displayWrites += count;
                    if (count > 0) sink.accept(scratchByWorker[taskIndex], count);
                }
            } else {
                for (int taskIndex = 0; taskIndex < ranges; taskIndex++) {
                    displayWrites += outputCounts[taskIndex];
                }
            }

            long finalClockState = sampleClock(counterBase + cycles - 1L);
            commitSourceState(source, preserved | finalClockState);

            sequence += cycles;
            lastClockCycles = cycles;
            lastDisplayWrites = displayWrites;
            lastParallelWorkers = ranges;
            return emitted;
        }

        public long lastClockCycles() { return lastClockCycles; }
        public long lastDisplayWrites() { return lastDisplayWrites; }
        public int lastParallelWorkers() { return lastParallelWorkers; }
        public int minimumCyclesPerWorker() { return MIN_CYCLES_PER_WORKER; }

        private void runRange(
                int taskIndex,
                int start,
                int end,
                long counterBase,
                long preserved,
                boolean fixedCoordinateReject
        ) {
            int capacity = end - start;
            long[] scratch = ensureScratch(taskIndex, capacity);
            int out = 0;

            for (int cycle = start; cycle < end; cycle++) {
                long clockState = sampleClock(counterBase + cycle);
                if (exactCoordinatePrefilter) {
                    if (fixedCoordinateReject || (clockState & clockCoordinateRejectMask) != 0L) continue;
                    scratch[out++] = PIXEL_OPCODE | packBoundary(preserved | clockState);
                    continue;
                }

                long raw = PIXEL_OPCODE | packBoundary(preserved | clockState);
                int x = (int) ((raw >>> 16) & FIELD_MASK);
                int y = (int) ((raw >>> 32) & FIELD_MASK);
                if (x < displayWidth && y < displayHeight) scratch[out++] = raw;
            }
            outputCounts[taskIndex] = out;
        }

        private long sampleClock(long key) {
            long result = chance100;
            if (activeCommon == 0L) return result & clockOutputMask;

            long r0 = counterWord(key + COMMON_SALT0);
            result |= r0 & chance50;
            if (needsSecondWord) {
                long r1 = counterWord(key + COMMON_SALT1);
                result |= (r0 & r1) & chance25;
                result |= (r0 | r1) & chance75;
            }
            return result & clockOutputMask;
        }

        private long packBoundary(long state) {
            if (boundaryIdentity) return state & DISPLAY_DATA_MASK;
            if (boundaryFieldShift) {
                long color = (state >>> colorSourceShift) & FIELD_MASK;
                long x = (state >>> xSourceShift) & FIELD_MASK;
                long y = (state >>> ySourceShift) & FIELD_MASK;
                return color | (x << 16) | (y << 32);
            }

            long result = 0L;
            for (int chunk = 0; chunk < boundaryScatter.length; chunk++) {
                result |= boundaryScatter[chunk][(int) ((state >>> (chunk * 8)) & 0xFFL)];
            }
            return result;
        }

        private long[] ensureScratch(int taskIndex, int count) {
            long[] current = scratchByWorker[taskIndex];
            if (current != null && current.length >= count) return current;
            int next = current == null ? 8_192 : current.length;
            while (next < count) next = Math.max(next + 1, next << 1);
            long[] replacement = new long[next];
            scratchByWorker[taskIndex] = replacement;
            return replacement;
        }
    }

    private static long readSourceState(IndependentRandomDisplayFastPath.Plan source) {
        try {
            return SOURCE_STATE_MASK.getLong(source);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not read independent RANDOM packed state", exception);
        }
    }

    private static void commitSourceState(IndependentRandomDisplayFastPath.Plan source, long state) {
        try {
            SOURCE_COMMIT_STATE.invoke(source, state);
        } catch (ReflectiveOperationException exception) {
            throw reflectionFailure("commit independent RANDOM packed state", exception);
        }
    }

    private static long counterWord(long z) {
        z ^= z >>> 12;
        z ^= z << 25;
        z ^= z >>> 27;
        return z * 0x2545F4914F6CDD1DL;
    }

    private static long seed(long salt) {
        long z = System.nanoTime() ^ Thread.currentThread().threadId() ^ salt;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static RuntimeException reflectionFailure(String action, ReflectiveOperationException exception) {
        Throwable cause = exception instanceof InvocationTargetException invocation && invocation.getCause() != null
                ? invocation.getCause()
                : exception;
        if (cause instanceof RuntimeException runtime) return runtime;
        if (cause instanceof Error error) throw error;
        return new IllegalStateException("Could not " + action + " for parallel independent DISPLAY", cause);
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

    private static Method method(Class<?> owner, String name, Class<?>... parameters) {
        try {
            Method result = owner.getDeclaredMethod(name, parameters);
            result.setAccessible(true);
            return result;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
