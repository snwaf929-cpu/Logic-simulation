package com.foreverspark.logicsim.client.render;

import net.minecraft.core.BlockPos;

import java.lang.reflect.Constructor;

/** Regression checks for the v11 partitioned realtime framebuffer commit. */
public final class ParallelRealtimeDisplayCommitSelfTest {
    private ParallelRealtimeDisplayCommitSelfTest() {}

    public static void main(String[] args) throws Exception {
        RealtimeDisplaySurface.Surface surface = newSurface(1, 2, 64, 2);
        int width = surface.backingWidth();

        long[][][] writes = new long[2][2][];
        int[][] counts = new int[2][2];

        // Owner 0: generation range 1 overwrites range 0 at the same pixel. Final color must respect CLOCK order.
        writes[0][0] = new long[] {
                packed(width, 1, 1, 0xF800),
                packed(width, 2, 2, 0x07E0)
        };
        counts[0][0] = 2;
        writes[1][0] = new long[] {
                packed(width, 1, 1, 0x001F)
        };
        counts[1][0] = 1;

        // Owner 1: white followed by black at the same pixel should net back to zero non-zero pixels.
        writes[0][1] = new long[] {
                packed(width, 3, 65, 0xFFFF)
        };
        counts[0][1] = 1;
        writes[1][1] = new long[] {
                packed(width, 3, 65, 0x0000)
        };
        counts[1][1] = 1;

        int[] deltas = new int[2];
        long[] writesPerOwner = new long[2];
        long applied = ParallelRealtimeDisplayCommitFastPath.commitSequentialForTest(
                surface, writes, counts, 2, 2, deltas, writesPerOwner
        );

        require(applied == 5L, "first commit write count");
        require(surface.pixelRgb565(1, 1) == 0x001F, "duplicate write CLOCK order");
        require(surface.pixelRgb565(2, 2) == 0x07E0, "owner 0 independent pixel");
        require(surface.pixelRgb565(3, 65) == 0x0000, "owner 1 final black");
        require(surface.nonZeroPixels() == 2, "exact nonZeroPixels after first commit");
        require(surface.publishedRevision() == 1L, "single first revision publication");
        for (int tile = 0; tile < surface.tileSlots(); tile++) {
            require(surface.tileRevision(tile) == 1L, "first whole-wall tile publication " + tile);
        }

        // Second commit: one non-zero becomes black while another owner creates a new non-zero pixel. Count stays 2.
        writes = new long[1][2][];
        counts = new int[1][2];
        writes[0][0] = new long[] { packed(width, 2, 2, 0x0000) };
        counts[0][0] = 1;
        writes[0][1] = new long[] { packed(width, 4, 66, 0xFFFF) };
        counts[0][1] = 1;

        applied = ParallelRealtimeDisplayCommitFastPath.commitSequentialForTest(
                surface, writes, counts, 1, 2, deltas, writesPerOwner
        );

        require(applied == 2L, "second commit write count");
        require(surface.nonZeroPixels() == 2, "exact nonZeroPixels after cross-owner delta merge");
        require(surface.pixelRgb565(2, 2) == 0x0000, "owner 0 black transition");
        require(surface.pixelRgb565(4, 66) == 0xFFFF, "owner 1 non-zero transition");
        require(surface.publishedRevision() == 2L, "single second revision publication");
        for (int tile = 0; tile < surface.tileSlots(); tile++) {
            require(surface.tileRevision(tile) == 2L, "second whole-wall tile publication " + tile);
        }

        System.out.println(
                "Parallel realtime DISPLAY framebuffer v11 self-test: PASS"
                        + " owners=2 writes=7 ordering=exact nonZero=2 revisions=2"
        );
    }

    private static RealtimeDisplaySurface.Surface newSurface(
            int columns,
            int rows,
            int density,
            int tileCount
    ) throws Exception {
        Constructor<RealtimeDisplaySurface.Surface> constructor = RealtimeDisplaySurface.Surface.class
                .getDeclaredConstructor(BlockPos.class, int.class, int.class, int.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(new BlockPos(0, 64, 0), columns, rows, density, tileCount);
    }

    private static long packed(int width, int x, int y, int rgb565) {
        int pixelIndex = y * width + x;
        return ((long) pixelIndex << 16) | (rgb565 & 0xFFFFL);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Parallel framebuffer v11 check failed: " + message);
    }
}
