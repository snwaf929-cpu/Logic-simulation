package com.foreverspark.logicsim.client.render;

import com.foreverspark.logicsim.core.CircuitSimulator;
import com.foreverspark.logicsim.core.TimingSignalDriver;
import com.foreverspark.logicsim.interconnect.CircuitProgramRuntime;
import com.foreverspark.logicsim.mixin.client.DeferredColorSamplerAccess;
import com.foreverspark.logicsim.mixin.client.DeferredCommonSamplerAccess;
import com.foreverspark.logicsim.mixin.client.RealtimeDisplaySurfaceAccess;

import java.lang.reflect.Field;
import java.util.Arrays;

/**
 * v8 full-density DISPLAY specialization.
 *
 * <p>v5/v6 generate packed DISPLAY commands into a scratch array and then the native64 surface decodes those commands
 * in a second loop. At 80-90+ million accepted pixels/sec that staging pass is measurable. This wrapper reuses the
 * already-proven v5 topology/compiler and its v6 one-word COLOR sampler, but writes X/Y/RGB565 directly into the
 * realtime 64px/tile framebuffer in the same loop that samples RANDOM.</p>
 *
 * <p>The wrapper is intentionally narrow: the DISPLAY must be full-density 64px/tile, the physical boundary must use
 * the compiler's contiguous field-shift packing, all 16 COLOR bits must be CLOCK-driven, and v5 must already have
 * proven an exact power-of-two coordinate reject mask. Any other topology stays on v5 unchanged.</p>
 */
public final class FusedDeferredColorDisplayFastPath {
    private static final long FIELD_MASK = 0xFFFFL;

    private static final Field SOURCE_BASE = field(DeferredColorRandomDisplayFastPath.Plan.class, "base");
    private static final Field SOURCE_RUNTIME = field(DeferredColorRandomDisplayFastPath.Plan.class, "runtime");
    private static final Field SOURCE_DEVICE = field(DeferredColorRandomDisplayFastPath.Plan.class, "deviceIndex");
    private static final Field SOURCE_WIDTH = field(DeferredColorRandomDisplayFastPath.Plan.class, "displayWidth");
    private static final Field SOURCE_HEIGHT = field(DeferredColorRandomDisplayFastPath.Plan.class, "displayHeight");
    private static final Field SOURCE_SIMULATOR = field(DeferredColorRandomDisplayFastPath.Plan.class, "simulator");
    private static final Field SOURCE_CLOCK = field(DeferredColorRandomDisplayFastPath.Plan.class, "clock");
    private static final Field SOURCE_OUTPUT_IDS = field(DeferredColorRandomDisplayFastPath.Plan.class, "outputSignalIds");
    private static final Field SOURCE_CLOCK_OUTPUT_MASK = field(DeferredColorRandomDisplayFastPath.Plan.class, "clockOutputMask");
    private static final Field SOURCE_CLOCK_NON_COLOR_MASK = field(DeferredColorRandomDisplayFastPath.Plan.class, "clockNonColorMask");
    private static final Field SOURCE_CLOCK_COLOR_MASK = field(DeferredColorRandomDisplayFastPath.Plan.class, "clockColorMask");
    private static final Field SOURCE_NON_COLOR_SAMPLER = field(DeferredColorRandomDisplayFastPath.Plan.class, "nonColorSampler");
    private static final Field SOURCE_COLOR_SAMPLER = field(DeferredColorRandomDisplayFastPath.Plan.class, "colorSampler");
    private static final Field SOURCE_BOUNDARY_FIELD_SHIFT = field(DeferredColorRandomDisplayFastPath.Plan.class, "boundaryFieldShift");
    private static final Field SOURCE_COLOR_SHIFT = field(DeferredColorRandomDisplayFastPath.Plan.class, "colorSourceShift");
    private static final Field SOURCE_X_SHIFT = field(DeferredColorRandomDisplayFastPath.Plan.class, "xSourceShift");
    private static final Field SOURCE_Y_SHIFT = field(DeferredColorRandomDisplayFastPath.Plan.class, "ySourceShift");
    private static final Field SOURCE_REJECT_MASK = field(DeferredColorRandomDisplayFastPath.Plan.class, "coordinateRejectLaneMask");
    private static final Field SOURCE_CLOCK_REJECT_MASK = field(DeferredColorRandomDisplayFastPath.Plan.class, "clockCoordinateRejectMask");
    private static final Field BASE_STATE_MASK = field(IndependentRandomDisplayFastPath.Plan.class, "stateMask");

    private FusedDeferredColorDisplayFastPath() {}

    public record CompileResult(Plan plan, String reason) {
        public boolean active() { return plan != null; }
    }

    public static CompileResult compile(
            DeferredColorRandomDisplayFastPath.Plan source,
            RealtimeDisplaySurface.Surface surface
    ) {
        if (source == null) return fail("source-null");
        if (surface == null) return fail("surface-null");
        if (surface.density() != 64) return fail("density-" + surface.density());

        try {
            CircuitProgramRuntime runtime = (CircuitProgramRuntime) SOURCE_RUNTIME.get(source);
            int deviceIndex = SOURCE_DEVICE.getInt(source);
            int width = SOURCE_WIDTH.getInt(source);
            int height = SOURCE_HEIGHT.getInt(source);
            if (surface.logicalWidth() != width || surface.logicalHeight() != height) {
                return fail("surface-size-mismatch");
            }
            if (!SOURCE_BOUNDARY_FIELD_SHIFT.getBoolean(source)) return fail("boundary-not-field-shift");

            long clockColorMask = SOURCE_CLOCK_COLOR_MASK.getLong(source);
            if (Long.bitCount(clockColorMask) != 16) {
                return fail("clock-color-lanes-" + Long.bitCount(clockColorMask));
            }

            int colorShift = SOURCE_COLOR_SHIFT.getInt(source);
            int xShift = SOURCE_X_SHIFT.getInt(source);
            int yShift = SOURCE_Y_SHIFT.getInt(source);
            if (!validFieldShift(colorShift) || !validFieldShift(xShift) || !validFieldShift(yShift)) {
                return fail("invalid-field-shift");
            }

            long rejectMask = SOURCE_REJECT_MASK.getLong(source);
            if (rejectMask == 0L) return fail("coordinate-reject-mask-missing");

            Object commonObject = SOURCE_NON_COLOR_SAMPLER.get(source);
            Object colorObject = SOURCE_COLOR_SAMPLER.get(source);
            if (!(commonObject instanceof DeferredCommonSamplerAccess commonSampler)) {
                return fail("common-sampler-bridge-missing");
            }
            if (!(colorObject instanceof DeferredColorSamplerAccess colorSampler)) {
                return fail("color-sampler-bridge-missing");
            }

            Object surfaceObject = surface;
            if (!(surfaceObject instanceof RealtimeDisplaySurfaceAccess surfaceAccess)) {
                return fail("surface-access-bridge-missing");
            }

            IndependentRandomDisplayFastPath.Plan base =
                    (IndependentRandomDisplayFastPath.Plan) SOURCE_BASE.get(source);
            CircuitSimulator simulator = (CircuitSimulator) SOURCE_SIMULATOR.get(source);
            TimingSignalDriver clock = (TimingSignalDriver) SOURCE_CLOCK.get(source);
            int[] outputSignalIds = ((int[]) SOURCE_OUTPUT_IDS.get(source)).clone();

            Plan plan = new Plan(
                    source,
                    base,
                    runtime,
                    deviceIndex,
                    width,
                    height,
                    simulator,
                    clock,
                    outputSignalIds,
                    SOURCE_CLOCK_OUTPUT_MASK.getLong(source),
                    SOURCE_CLOCK_NON_COLOR_MASK.getLong(source),
                    clockColorMask,
                    commonSampler,
                    colorSampler,
                    colorShift,
                    xShift,
                    yShift,
                    rejectMask,
                    SOURCE_CLOCK_REJECT_MASK.getLong(source),
                    surface,
                    surfaceAccess
            );
            return new CompileResult(
                    plan,
                    "active-fused-native64:rejectFree=" + plan.rejectFreeClockLoop()
                            + ":scratchCommands=0:decodePass=false"
            );
        } catch (IllegalAccessException exception) {
            return fail("reflection-access:" + exception.getClass().getSimpleName());
        }
    }

    private static CompileResult fail(String reason) {
        return new CompileResult(null, reason);
    }

    public static final class Plan {
        private final DeferredColorRandomDisplayFastPath.Plan source;
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
        private final DeferredCommonSamplerAccess nonColorSampler;
        private final DeferredColorSamplerAccess colorSampler;
        private final int colorSourceShift;
        private final int xSourceShift;
        private final int ySourceShift;
        private final long coordinateRejectLaneMask;
        private final long clockCoordinateRejectMask;
        private final RealtimeDisplaySurface.Surface surface;
        private final RealtimeDisplaySurfaceAccess surfaceAccess;
        private long lastClockCycles;
        private long lastColorSamples;
        private long lastDisplayWrites;

        private Plan(
                DeferredColorRandomDisplayFastPath.Plan source,
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
                DeferredCommonSamplerAccess nonColorSampler,
                DeferredColorSamplerAccess colorSampler,
                int colorSourceShift,
                int xSourceShift,
                int ySourceShift,
                long coordinateRejectLaneMask,
                long clockCoordinateRejectMask,
                RealtimeDisplaySurface.Surface surface,
                RealtimeDisplaySurfaceAccess surfaceAccess
        ) {
            this.source = source;
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
            this.nonColorSampler = nonColorSampler;
            this.colorSampler = colorSampler;
            this.colorSourceShift = colorSourceShift;
            this.xSourceShift = xSourceShift;
            this.ySourceShift = ySourceShift;
            this.coordinateRejectLaneMask = coordinateRejectLaneMask;
            this.clockCoordinateRejectMask = clockCoordinateRejectMask;
            this.surface = surface;
            this.surfaceAccess = surfaceAccess;
        }

        public boolean matches(
                CircuitProgramRuntime candidate,
                int candidateDevice,
                RealtimeDisplaySurface.Surface candidateSurface
        ) {
            return candidate == runtime
                    && candidateDevice == deviceIndex
                    && candidateSurface == surface
                    && candidateSurface.logicalWidth() == displayWidth
                    && candidateSurface.logicalHeight() == displayHeight;
        }

        public boolean rejectFreeClockLoop() { return clockCoordinateRejectMask == 0L; }
        public long lastClockCycles() { return lastClockCycles; }
        public long lastColorSamples() { return lastColorSamples; }
        public long lastDisplayWrites() { return lastDisplayWrites; }

        public long advance(long elapsedNanos, long edgeBudget) {
            if (elapsedNanos < 0L || edgeBudget < 0L) {
                throw new IllegalArgumentException("clock arguments must be >= 0");
            }

            // Poll/edge-detect all independent RANDOM trigger groups without consuming a MHz clock edge.
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
                throw new IllegalStateException("Fused RGB display batch is too large: " + risingEdges);
            }

            int cycles = (int) risingEdges;
            long preserved = state & ~clockOutputMask;
            long finalNonColor = state & clockNonColorMask;
            long finalColor = state & clockColorMask;
            boolean fixedCoordinateReject = (preserved & coordinateRejectLaneMask) != 0L;
            boolean colorSampledOnLastCycle = false;
            long colorSamples = 0L;
            int writes = 0;

            char[] framebuffer = surfaceAccess.logic$getPixels();
            int stride = surface.backingWidth();
            int nonZero = surfaceAccess.logic$getNonZeroPixels();

            if (!fixedCoordinateReject && clockCoordinateRejectMask == 0L) {
                // Current 2K stress topology: all ten upper X/Y reject bits are independent/preserved and LOW.
                // Every CLOCK sample is therefore in range; keep the MHz loop branch-free.
                for (int cycle = 0; cycle < cycles; cycle++) {
                    finalNonColor = nonColorSampler.logic$sampleCommon();
                    finalColor = colorSampler.logic$sampleColor();

                    long coordinateState = preserved | finalNonColor;
                    int x = (int) ((coordinateState >>> xSourceShift) & FIELD_MASK);
                    int y = (int) ((coordinateState >>> ySourceShift) & FIELD_MASK);
                    int rgb565 = (int) ((finalColor >>> colorSourceShift) & FIELD_MASK);
                    int pixelIndex = y * stride + x;
                    nonZero = FusedNative64PixelMath.write(framebuffer, pixelIndex, rgb565, nonZero);
                }
                writes = cycles;
                colorSamples = cycles;
                colorSampledOnLastCycle = true;
            } else {
                for (int cycle = 0; cycle < cycles; cycle++) {
                    finalNonColor = nonColorSampler.logic$sampleCommon();
                    boolean rejected = fixedCoordinateReject
                            || (finalNonColor & clockCoordinateRejectMask) != 0L;
                    if (rejected) {
                        colorSampledOnLastCycle = false;
                        continue;
                    }

                    finalColor = colorSampler.logic$sampleColor();
                    colorSamples++;
                    colorSampledOnLastCycle = true;

                    long coordinateState = preserved | finalNonColor;
                    int x = (int) ((coordinateState >>> xSourceShift) & FIELD_MASK);
                    int y = (int) ((coordinateState >>> ySourceShift) & FIELD_MASK);
                    int rgb565 = (int) ((finalColor >>> colorSourceShift) & FIELD_MASK);
                    int pixelIndex = y * stride + x;
                    nonZero = FusedNative64PixelMath.write(framebuffer, pixelIndex, rgb565, nonZero);
                    writes++;
                }
            }

            // Match v5 state semantics when the final virtual coordinate was rejected.
            if (!colorSampledOnLastCycle) {
                finalColor = colorSampler.logic$sampleColor();
                colorSamples++;
            }

            long finalState = preserved | finalNonColor | finalColor;
            commitState(finalState);

            if (writes > 0) publishSurface(nonZero);

            lastClockCycles = cycles;
            lastColorSamples = colorSamples;
            lastDisplayWrites = writes;
            return emitted;
        }

        public void synchronizeFallback() {
            source.synchronizeFallback();
        }

        private long readBaseState() {
            try {
                return BASE_STATE_MASK.getLong(base);
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("Could not read v3 packed RANDOM state", exception);
            }
        }

        private void commitState(long state) {
            try {
                long previous = BASE_STATE_MASK.getLong(base);
                if (state != previous) {
                    simulator.driveBitVectorFast(outputSignalIds, 0, outputSignalIds.length, state);
                }
                BASE_STATE_MASK.setLong(base, state);
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("Could not synchronize v3 packed RANDOM state", exception);
            }
        }

        private void publishSurface(int nonZeroPixels) {
            surfaceAccess.logic$setNonZeroPixels(nonZeroPixels);
            long next = surfaceAccess.logic$getRevision() + 1L;
            surfaceAccess.logic$setRevision(next);
            Arrays.fill(surfaceAccess.logic$getTileRevisions(), next);
            // Volatile write is the release barrier after framebuffer + metadata writes.
            surfaceAccess.logic$setPublishedRevision(next);
        }
    }

    private static boolean validFieldShift(int shift) {
        return shift >= 0 && shift <= 48 - 16;
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
