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
 * v10 multi-worker companion for DeferredColorRandomDisplayFastPath.
 *
 * <p>The v9 one-worker path owns mutable X/Y and RGB PRNG state, so splitting one batch between CPU cores would race.
 * This plan keeps the already-proven v9 topology and boundary mapping but uses a counter-addressed random permutation:
 * each virtual cycle can therefore be evaluated independently by a different worker. Contiguous cycle ranges write to
 * private scratch buffers, then the coordinator publishes those buffers in original cycle order. The final packed
 * RANDOM state is committed once after the barrier, preserving deterministic simulator state without concurrent writes
 * into CircuitSimulator.</p>
 *
 * <p>This specialization is deliberately limited to the existing zero-gate physical DISPLAY path. It does not claim
 * that arbitrary stateful NAND feedback can be parallelized by cycle; those circuits retain ordered execution.</p>
 */
public final class ParallelDeferredColorDisplayFastPath {
    private static final long PIXEL_OPCODE = (long) DisplayCommandCodec.OP_PIXEL << 48;
    private static final long DISPLAY_DATA_MASK = (1L << 48) - 1L;
    private static final int MIN_CYCLES_PER_WORKER = 8_192;

    private static final Class<?> SOURCE_PLAN = DeferredColorRandomDisplayFastPath.Plan.class;
    private static final Field SOURCE_CLOCK = field(SOURCE_PLAN, "clock");
    private static final Field SOURCE_CLOCK_OUTPUT_MASK = field(SOURCE_PLAN, "clockOutputMask");
    private static final Field SOURCE_CLOCK_NON_COLOR_MASK = field(SOURCE_PLAN, "clockNonColorMask");
    private static final Field SOURCE_CLOCK_COLOR_MASK = field(SOURCE_PLAN, "clockColorMask");
    private static final Field SOURCE_ARBITRARY_COLOR_MASK = field(SOURCE_PLAN, "arbitraryColorMask");
    private static final Field SOURCE_NON_COLOR_SAMPLER = field(SOURCE_PLAN, "nonColorSampler");
    private static final Field SOURCE_COLOR_SAMPLER = field(SOURCE_PLAN, "colorSampler");
    private static final Field SOURCE_BOUNDARY_SCATTER = field(SOURCE_PLAN, "boundaryScatter");
    private static final Field SOURCE_BOUNDARY_IDENTITY = field(SOURCE_PLAN, "boundaryIdentity");
    private static final Field SOURCE_BOUNDARY_FIELD_SHIFT = field(SOURCE_PLAN, "boundaryFieldShift");
    private static final Field SOURCE_COLOR_SHIFT = field(SOURCE_PLAN, "colorSourceShift");
    private static final Field SOURCE_X_SHIFT = field(SOURCE_PLAN, "xSourceShift");
    private static final Field SOURCE_Y_SHIFT = field(SOURCE_PLAN, "ySourceShift");
    private static final Field SOURCE_COORDINATE_REJECT_MASK = field(SOURCE_PLAN, "coordinateRejectLaneMask");
    private static final Field SOURCE_CLOCK_COORDINATE_REJECT_MASK = field(SOURCE_PLAN, "clockCoordinateRejectMask");
    private static final Method SOURCE_READ_STATE = method(SOURCE_PLAN, "readBaseState");
    private static final Method SOURCE_COMMIT_STATE = method(SOURCE_PLAN, "commitState", long.class);

    private static final Class<?> COMMON_SAMPLER = nested("com.foreverspark.logicsim.client.render.DeferredColorRandomDisplayFastPath$CommonSampler");
    private static final Field COMMON_CHANCE25 = field(COMMON_SAMPLER, "chance25Mask");
    private static final Field COMMON_CHANCE50 = field(COMMON_SAMPLER, "chance50Mask");
    private static final Field COMMON_CHANCE75 = field(COMMON_SAMPLER, "chance75Mask");
    private static final Field COMMON_CHANCE100 = field(COMMON_SAMPLER, "chance100Mask");

    private static final Class<?> COLOR_SAMPLER = nested("com.foreverspark.logicsim.client.render.DeferredColorRandomDisplayFastPath$ColorSampler");
    private static final Field COLOR_CHANCE25 = field(COLOR_SAMPLER, "chance25Mask");
    private static final Field COLOR_CHANCE50 = field(COLOR_SAMPLER, "chance50Mask");
    private static final Field COLOR_CHANCE75 = field(COLOR_SAMPLER, "chance75Mask");
    private static final Field COLOR_CHANCE100 = field(COLOR_SAMPLER, "chance100Mask");
    private static final Field COLOR_ARBITRARY_CHUNKS = field(COLOR_SAMPLER, "arbitraryChunks");

    private static final Class<?> ARBITRARY_CHUNK = nested("com.foreverspark.logicsim.client.render.DeferredColorRandomDisplayFastPath$ArbitraryChunk");
    private static final Field CHUNK_LANE_MASK = field(ARBITRARY_CHUNK, "laneMask");
    private static final Field CHUNK_THRESHOLD_BYTES = field(ARBITRARY_CHUNK, "thresholdBytes");

    private ParallelDeferredColorDisplayFastPath() {}

    public record CompileResult(Plan plan, String reason) {
        public boolean active() { return plan != null; }
    }

    public static CompileResult compile(DeferredColorRandomDisplayFastPath.Plan source) {
        if (source == null) return new CompileResult(null, "source-plan-missing");
        try {
            Object common = SOURCE_NON_COLOR_SAMPLER.get(source);
            Object color = SOURCE_COLOR_SAMPLER.get(source);
            if (!COMMON_SAMPLER.isInstance(common) || !COLOR_SAMPLER.isInstance(color)) {
                return new CompileResult(null, "sampler-layout-unresolved");
            }

            long arbitraryColorMask = SOURCE_ARBITRARY_COLOR_MASK.getLong(source);
            if (arbitraryColorMask == 0L) return new CompileResult(null, "no-arbitrary-color");
            long[] thresholds = reconstructThresholdPlanes(color, arbitraryColorMask);
            long seed = seed(0xA0761D6478BD642FL);
            SingleWordRgbMaskSampler arbitraryColor = new SingleWordRgbMaskSampler(
                    arbitraryColorMask,
                    thresholds,
                    seed ^ 0xD1B54A32D192ED03L
            );

            Plan plan = new Plan(
                    source,
                    (TimingSignalDriver) SOURCE_CLOCK.get(source),
                    SOURCE_CLOCK_OUTPUT_MASK.getLong(source),
                    SOURCE_CLOCK_NON_COLOR_MASK.getLong(source),
                    SOURCE_CLOCK_COLOR_MASK.getLong(source),
                    SOURCE_COORDINATE_REJECT_MASK.getLong(source),
                    SOURCE_CLOCK_COORDINATE_REJECT_MASK.getLong(source),
                    COMMON_CHANCE25.getLong(common),
                    COMMON_CHANCE50.getLong(common),
                    COMMON_CHANCE75.getLong(common),
                    COMMON_CHANCE100.getLong(common),
                    COLOR_CHANCE25.getLong(color),
                    COLOR_CHANCE50.getLong(color),
                    COLOR_CHANCE75.getLong(color),
                    COLOR_CHANCE100.getLong(color),
                    arbitraryColor,
                    (long[][]) SOURCE_BOUNDARY_SCATTER.get(source),
                    SOURCE_BOUNDARY_IDENTITY.getBoolean(source),
                    SOURCE_BOUNDARY_FIELD_SHIFT.getBoolean(source),
                    SOURCE_COLOR_SHIFT.getInt(source),
                    SOURCE_X_SHIFT.getInt(source),
                    SOURCE_Y_SHIFT.getInt(source),
                    seed
            );
            return new CompileResult(plan,
                    "active-counter-ranges:arbitraryColorLanes=" + arbitraryColor.laneCount()
                            + ":tableBytes=" + arbitraryColor.tableBytes());
        } catch (IllegalAccessException exception) {
            return new CompileResult(null, "reflection-access:" + exception.getClass().getSimpleName());
        }
    }

    public static final class Plan {
        private static final long NON_COLOR_SALT0 = 0x9E3779B97F4A7C15L;
        private static final long NON_COLOR_SALT1 = 0xD1B54A32D192ED03L;
        private static final long COLOR_COMMON_SALT0 = 0x94D049BB133111EBL;
        private static final long COLOR_COMMON_SALT1 = 0xBF58476D1CE4E5B9L;
        private static final long COLOR_ARBITRARY_SALT = 0xDB4F0B9175AE2165L;

        private final DeferredColorRandomDisplayFastPath.Plan source;
        private final TimingSignalDriver clock;
        private final long clockOutputMask;
        private final long clockNonColorMask;
        private final long clockColorMask;
        private final long coordinateRejectLaneMask;
        private final long clockCoordinateRejectMask;
        private final long nonColor25;
        private final long nonColor50;
        private final long nonColor75;
        private final long nonColor100;
        private final long color25;
        private final long color50;
        private final long color75;
        private final long color100;
        private final SingleWordRgbMaskSampler arbitraryColor;
        private final long[][] boundaryScatter;
        private final boolean boundaryIdentity;
        private final boolean boundaryFieldShift;
        private final int colorSourceShift;
        private final int xSourceShift;
        private final int ySourceShift;
        private final long[][] scratchByWorker = new long[CircuitWorkerPolicyBridge.MAX_PERSISTED_WORKERS][];
        private final int[] outputCounts = new int[CircuitWorkerPolicyBridge.MAX_PERSISTED_WORKERS];
        private long sequence;
        private long lastClockCycles;
        private long lastDisplayWrites;
        private int lastParallelWorkers = 1;

        private Plan(
                DeferredColorRandomDisplayFastPath.Plan source,
                TimingSignalDriver clock,
                long clockOutputMask,
                long clockNonColorMask,
                long clockColorMask,
                long coordinateRejectLaneMask,
                long clockCoordinateRejectMask,
                long nonColor25,
                long nonColor50,
                long nonColor75,
                long nonColor100,
                long color25,
                long color50,
                long color75,
                long color100,
                SingleWordRgbMaskSampler arbitraryColor,
                long[][] boundaryScatter,
                boolean boundaryIdentity,
                boolean boundaryFieldShift,
                int colorSourceShift,
                int xSourceShift,
                int ySourceShift,
                long seed
        ) {
            this.source = source;
            this.clock = clock;
            this.clockOutputMask = clockOutputMask;
            this.clockNonColorMask = clockNonColorMask;
            this.clockColorMask = clockColorMask;
            this.coordinateRejectLaneMask = coordinateRejectLaneMask;
            this.clockCoordinateRejectMask = clockCoordinateRejectMask;
            this.nonColor25 = nonColor25 & clockNonColorMask;
            this.nonColor50 = nonColor50 & clockNonColorMask;
            this.nonColor75 = nonColor75 & clockNonColorMask;
            this.nonColor100 = nonColor100 & clockNonColorMask;
            this.color25 = color25 & clockColorMask;
            this.color50 = color50 & clockColorMask;
            this.color75 = color75 & clockColorMask;
            this.color100 = color100 & clockColorMask;
            this.arbitraryColor = arbitraryColor;
            this.boundaryScatter = boundaryScatter;
            this.boundaryIdentity = boundaryIdentity;
            this.boundaryFieldShift = boundaryFieldShift;
            this.colorSourceShift = colorSourceShift;
            this.xSourceShift = xSourceShift;
            this.ySourceShift = ySourceShift;
            this.sequence = seed;
        }

        public long advance(
                CircuitBlockEntity circuit,
                long elapsedNanos,
                long edgeBudget,
                CircuitTimingController.LongBatchConsumer sink
        ) {
            if (circuit == null) throw new IllegalArgumentException("Circuit Block is required");
            if (elapsedNanos < 0L || edgeBudget < 0L) throw new IllegalArgumentException("clock arguments must be >= 0");

            // Let the existing v9 plan process independent external trigger groups without consuming CLOCK work.
            source.advance(0L, 0L, null);
            long state = readSourceState(source);

            if (!clock.timing().running()) {
                lastClockCycles = 0L;
                lastDisplayWrites = 0L;
                lastParallelWorkers = 1;
                return 0L;
            }

            long emitted = clock.advanceNanosPulseBatch(elapsedNanos, edgeBudget);
            long risingEdges = clock.lastPulseRisingEdges();
            if (risingEdges <= 0L) {
                lastClockCycles = 0L;
                lastDisplayWrites = 0L;
                lastParallelWorkers = 1;
                return emitted;
            }
            if (risingEdges > Integer.MAX_VALUE) {
                throw new IllegalStateException("Parallel deferred RGB batch is unexpectedly large: " + risingEdges);
            }

            int cycles = (int) risingEdges;
            long counterBase = sequence;
            long preserved = state & ~clockOutputMask;
            boolean fixedCoordinateReject = (preserved & coordinateRejectLaneMask) != 0L;

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
                            fixedCoordinateReject,
                            sink != null
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
                for (int taskIndex = 0; taskIndex < ranges; taskIndex++) displayWrites += outputCounts[taskIndex];
            }

            // The final packed RANDOM state is derived by cycle index, so it is independent of task completion order.
            long lastKey = counterBase + (cycles - 1L);
            long finalNonColor = sampleNonColor(lastKey);
            long finalColor = sampleColor(lastKey);
            commitSourceState(source, preserved | finalNonColor | finalColor);

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
                boolean fixedCoordinateReject,
                boolean produceCommands
        ) {
            int capacity = end - start;
            long[] scratch = produceCommands ? ensureScratch(taskIndex, capacity) : null;
            int out = 0;
            for (int cycle = start; cycle < end; cycle++) {
                long key = counterBase + cycle;
                long nonColor = sampleNonColor(key);
                if (fixedCoordinateReject || (nonColor & clockCoordinateRejectMask) != 0L) continue;

                long color = sampleColor(key);
                if (scratch != null) {
                    long finalState = preserved | nonColor | color;
                    scratch[out] = PIXEL_OPCODE | packBoundary(finalState);
                }
                out++;
            }
            outputCounts[taskIndex] = out;
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

        private long sampleNonColor(long key) {
            long result = nonColor100;
            long active = nonColor25 | nonColor50 | nonColor75;
            if (active == 0L) return result & clockNonColorMask;
            long r0 = counterWord(key + NON_COLOR_SALT0);
            result |= r0 & nonColor50;
            if ((nonColor25 | nonColor75) != 0L) {
                long r1 = counterWord(key + NON_COLOR_SALT1);
                result |= (r0 & r1) & nonColor25;
                result |= (r0 | r1) & nonColor75;
            }
            return result & clockNonColorMask;
        }

        private long sampleColor(long key) {
            long result = color100;
            long activeCommon = color25 | color50 | color75;
            if (activeCommon != 0L) {
                long r0 = counterWord(key + COLOR_COMMON_SALT0);
                result |= r0 & color50;
                if ((color25 | color75) != 0L) {
                    long r1 = counterWord(key + COLOR_COMMON_SALT1);
                    result |= (r0 & r1) & color25;
                    result |= (r0 | r1) & color75;
                }
            }
            long arbitraryWord = counterWord(key + COLOR_ARBITRARY_SALT);
            int word32 = (int) (arbitraryWord ^ (arbitraryWord >>> 32));
            result |= arbitraryColor.sampleMaskFromWord(word32);
            return result & clockColorMask;
        }

        private long packBoundary(long state) {
            if (boundaryIdentity) return state & DISPLAY_DATA_MASK;
            if (boundaryFieldShift) {
                long color = (state >>> colorSourceShift) & 0xFFFFL;
                long x = (state >>> xSourceShift) & 0xFFFFL;
                long y = (state >>> ySourceShift) & 0xFFFFL;
                return color | (x << 16) | (y << 32);
            }
            long result = 0L;
            for (int chunk = 0; chunk < boundaryScatter.length; chunk++) {
                result |= boundaryScatter[chunk][(int) ((state >>> (chunk * 8)) & 0xFFL)];
            }
            return result;
        }
    }

    /** Avoid a client-source dependency on policy implementation details beyond its public persisted ceiling. */
    private static final class CircuitWorkerPolicyBridge {
        private static final int MAX_PERSISTED_WORKERS = 64;
    }

    private static long[] reconstructThresholdPlanes(Object colorSampler, long arbitraryMask) throws IllegalAccessException {
        long[] planes = new long[8];
        Object[] chunks = (Object[]) COLOR_ARBITRARY_CHUNKS.get(colorSampler);
        if (chunks == null) return planes;

        for (Object chunk : chunks) {
            if (chunk == null || !ARBITRARY_CHUNK.isInstance(chunk)) continue;
            long lanes = CHUNK_LANE_MASK.getLong(chunk) & arbitraryMask;
            long thresholdBytes = CHUNK_THRESHOLD_BYTES.getLong(chunk);
            int byteIndex = 0;
            long remaining = lanes;
            while (remaining != 0L) {
                int lane = Long.numberOfTrailingZeros(remaining);
                long laneBit = 1L << lane;
                remaining &= ~laneBit;
                int threshold = (int) ((thresholdBytes >>> (byteIndex * 8)) & 0xFFL);
                for (int bit = 0; bit < 8; bit++) {
                    if (((threshold >>> bit) & 1) != 0) planes[bit] |= laneBit;
                }
                byteIndex++;
            }
        }
        return planes;
    }

    private static long readSourceState(DeferredColorRandomDisplayFastPath.Plan source) {
        try {
            return (long) SOURCE_READ_STATE.invoke(source);
        } catch (ReflectiveOperationException exception) {
            throw reflectionFailure("read packed RANDOM state", exception);
        }
    }

    private static void commitSourceState(DeferredColorRandomDisplayFastPath.Plan source, long state) {
        try {
            SOURCE_COMMIT_STATE.invoke(source, state);
        } catch (ReflectiveOperationException exception) {
            throw reflectionFailure("commit packed RANDOM state", exception);
        }
    }

    /** Fast bijective counter permutation: independent cycle indexes need no shared mutable PRNG state. */
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
        return new IllegalStateException("Could not " + action + " for parallel RGB DISPLAY", cause);
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
