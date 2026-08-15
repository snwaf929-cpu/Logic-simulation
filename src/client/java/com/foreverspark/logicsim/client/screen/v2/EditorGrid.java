package com.foreverspark.logicsim.client.screen.v2;

/**
 * Single source of truth for Logic Editor V2 geometry.
 *
 * <p>All editor-space geometry is expressed in multiples of {@link #STEP}. The renderer may
 * choose to emphasize every fourth line, but movement, duplication, routes, pins and component
 * sizing all use the same six-unit lattice.</p>
 */
public final class EditorGrid {
    public static final double STEP = 6.0;
    public static final int MAJOR_EVERY = 4;
    public static final double MAJOR_STEP = STEP * MAJOR_EVERY;

    private EditorGrid() {}

    public static double snap(double value) {
        return Math.round(value / STEP) * STEP;
    }

    public static double snapUp(double value) {
        return Math.ceil(value / STEP) * STEP;
    }

    public static double snapDown(double value) {
        return Math.floor(value / STEP) * STEP;
    }

    public static boolean aligned(double value) {
        return Math.abs(value - snap(value)) < 0.0001;
    }

    public static double duplicateGap() {
        return STEP;
    }
}
