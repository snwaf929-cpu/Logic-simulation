package com.foreverspark.logicsim.client.render;

import com.foreverspark.logicsim.block.CircuitBlockEntity;
import com.foreverspark.logicsim.block.CircuitSimulationWorker;

import java.lang.reflect.Field;
import java.util.Arrays;

/**
 * v11 second-stage framebuffer engine for the multi-worker RGB DISPLAY path.
 *
 * <p>Generation workers pre-bucket each accepted pixel into a deterministic Y-owner. Every owner receives packed
 * {@code (pixelIndex << 16) | rgb565} writes in original CLOCK order. Framebuffer workers therefore touch disjoint
 * row regions and can update the shared RGB565 array concurrently without locks or pixel races. Exact non-zero deltas
 * are accumulated per owner and merged after one barrier; revision/tile publication remains a tiny serial commit.</p>
 */
public final class ParallelRealtimeDisplayCommitFastPath {
    private static final Class<?> SURFACE = RealtimeDisplaySurface.Surface.class;
    private static final Field SURFACE_PIXELS = field("pixels");
    private static final Field SURFACE_TILE_REVISIONS = field("tileRevisions");
    private static final Field SURFACE_REVISION = field("revision");
    private static final Field SURFACE_PUBLISHED_REVISION = field("publishedRevision");
    private static final Field SURFACE_NON_ZERO_PIXELS = field("nonZeroPixels");

    private ParallelRealtimeDisplayCommitFastPath() {}

    /**
     * Apply pre-bucketed pixel writes using the Circuit Block's current worker ceiling.
     *
     * @return number of framebuffer worker ranges scheduled for the commit stage
     */
    public static int commit(
            CircuitBlockEntity circuit,
            RealtimeDisplaySurface.Surface surface,
            long[][][] writesByGenerationAndOwner,
            int[][] countsByGenerationAndOwner,
            int generationRanges,
            int ownerCount,
            int[] nonZeroDeltas,
            long[] writesPerOwner
    ) {
        if (circuit == null || surface == null) throw new IllegalArgumentException("circuit and surface are required");
        if (ownerCount <= 0 || generationRanges <= 0) return 0;

        char[] framebuffer = pixels(surface);
        long[] tileRevisions = tileRevisions(surface);
        prepareCounters(nonZeroDeltas, writesPerOwner, ownerCount);

        int workers = CircuitSimulationWorker.runParallelRanges(
                circuit,
                ownerCount,
                1,
                (taskIndex, startOwner, endOwner) -> {
                    for (int owner = startOwner; owner < endOwner; owner++) {
                        commitOwner(
                                owner,
                                generationRanges,
                                writesByGenerationAndOwner,
                                countsByGenerationAndOwner,
                                framebuffer,
                                nonZeroDeltas,
                                writesPerOwner
                        );
                    }
                }
        );

        finish(surface, tileRevisions, nonZeroDeltas, writesPerOwner, ownerCount);
        return workers;
    }

    /** Shared production logic exposed package-private for the dependency-light regression test. */
    static long commitSequentialForTest(
            RealtimeDisplaySurface.Surface surface,
            long[][][] writesByGenerationAndOwner,
            int[][] countsByGenerationAndOwner,
            int generationRanges,
            int ownerCount,
            int[] nonZeroDeltas,
            long[] writesPerOwner
    ) {
        char[] framebuffer = pixels(surface);
        long[] tileRevisions = tileRevisions(surface);
        prepareCounters(nonZeroDeltas, writesPerOwner, ownerCount);
        for (int owner = 0; owner < ownerCount; owner++) {
            commitOwner(
                    owner,
                    generationRanges,
                    writesByGenerationAndOwner,
                    countsByGenerationAndOwner,
                    framebuffer,
                    nonZeroDeltas,
                    writesPerOwner
            );
        }
        return finish(surface, tileRevisions, nonZeroDeltas, writesPerOwner, ownerCount);
    }

    private static void prepareCounters(int[] nonZeroDeltas, long[] writesPerOwner, int ownerCount) {
        if (nonZeroDeltas == null || nonZeroDeltas.length < ownerCount) {
            throw new IllegalArgumentException("nonZeroDeltas is smaller than ownerCount");
        }
        if (writesPerOwner == null || writesPerOwner.length < ownerCount) {
            throw new IllegalArgumentException("writesPerOwner is smaller than ownerCount");
        }
        Arrays.fill(nonZeroDeltas, 0, ownerCount, 0);
        Arrays.fill(writesPerOwner, 0, ownerCount, 0L);
    }

    private static void commitOwner(
            int owner,
            int generationRanges,
            long[][][] writesByGenerationAndOwner,
            int[][] countsByGenerationAndOwner,
            char[] framebuffer,
            int[] nonZeroDeltas,
            long[] writesPerOwner
    ) {
        int nonZeroDelta = 0;
        long writes = 0L;

        // generationRange order is CLOCK order. Each bucket also preserves order inside its own contiguous range.
        for (int generation = 0; generation < generationRanges; generation++) {
            long[] packedWrites = writesByGenerationAndOwner[generation][owner];
            int count = countsByGenerationAndOwner[generation][owner];
            if (packedWrites == null || count <= 0) continue;
            int limit = Math.min(count, packedWrites.length);

            for (int index = 0; index < limit; index++) {
                long packed = packedWrites[index];
                int rgb565 = (int) packed & 0xFFFF;
                int pixelIndex = (int) (packed >>> 16);
                int previous = framebuffer[pixelIndex];
                framebuffer[pixelIndex] = (char) rgb565;
                nonZeroDelta += nonZeroFlag(rgb565) - nonZeroFlag(previous);
            }
            writes += limit;
        }

        nonZeroDeltas[owner] = nonZeroDelta;
        writesPerOwner[owner] = writes;
    }

    /** One small serial publication after all framebuffer owners have joined. */
    private static long finish(
            RealtimeDisplaySurface.Surface surface,
            long[] tileRevisions,
            int[] nonZeroDeltas,
            long[] writesPerOwner,
            int ownerCount
    ) {
        int totalNonZeroDelta = 0;
        long totalWrites = 0L;
        for (int owner = 0; owner < ownerCount; owner++) {
            totalNonZeroDelta += nonZeroDeltas[owner];
            totalWrites += writesPerOwner[owner];
        }
        if (totalWrites == 0L) return 0L;

        try {
            int previousNonZero = SURFACE_NON_ZERO_PIXELS.getInt(surface);
            SURFACE_NON_ZERO_PIXELS.setInt(surface, previousNonZero + totalNonZeroDelta);

            long nextRevision = SURFACE_REVISION.getLong(surface) + 1L;
            SURFACE_REVISION.setLong(surface, nextRevision);
            // Full-2K random batches touch the wall densely. Over-invalidating metadata is cheaper than per-pixel bits
            // and does not change framebuffer or simulator semantics.
            Arrays.fill(tileRevisions, nextRevision);
            SURFACE_PUBLISHED_REVISION.setLong(surface, nextRevision);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not publish parallel realtime DISPLAY framebuffer", exception);
        }
        return totalWrites;
    }

    private static char[] pixels(RealtimeDisplaySurface.Surface surface) {
        try {
            return (char[]) SURFACE_PIXELS.get(surface);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not access realtime DISPLAY pixels", exception);
        }
    }

    private static long[] tileRevisions(RealtimeDisplaySurface.Surface surface) {
        try {
            return (long[]) SURFACE_TILE_REVISIONS.get(surface);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not access realtime DISPLAY tile revisions", exception);
        }
    }

    private static int nonZeroFlag(int value) {
        return (value | -value) >>> 31;
    }

    private static Field field(String name) {
        try {
            Field result = SURFACE.getDeclaredField(name);
            result.setAccessible(true);
            return result;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
