package com.foreverspark.logicsim.editor.model;

/** Saved presentation settings for a reusable custom chip instance. */
public final class ChipVisualSettings {
    public static final double DEFAULT_WIDTH = 90.0;
    public static final double DEFAULT_MIN_HEIGHT = 48.0;
    public static final double DEFAULT_PORT_SPACING = 18.0;

    public double width = DEFAULT_WIDTH;
    public double minHeight = DEFAULT_MIN_HEIGHT;
    public double portSpacing = DEFAULT_PORT_SPACING;

    public ChipVisualSettings() {
    }

    public ChipVisualSettings(double width, double minHeight, double portSpacing) {
        this.width = clamp(width, 72.0, 260.0);
        this.minHeight = clamp(minHeight, 42.0, 300.0);
        this.portSpacing = clamp(portSpacing, 10.0, 48.0);
    }

    public void normalize() {
        width = clamp(width, 72.0, 260.0);
        minHeight = clamp(minHeight, 42.0, 300.0);
        portSpacing = clamp(portSpacing, 10.0, 48.0);
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }
}
