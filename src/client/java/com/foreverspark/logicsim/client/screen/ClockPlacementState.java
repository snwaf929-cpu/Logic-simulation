package com.foreverspark.logicsim.client.screen;

/** One-shot client placement intent for the CLOCK infrastructure source. */
public final class ClockPlacementState {
    public static final long DEFAULT_FREQUENCY_HZ = 1_000_000L;
    private static boolean armed;
    private static long frequencyHz = DEFAULT_FREQUENCY_HZ;

    private ClockPlacementState() {}

    public static void arm() {
        armed = true;
    }

    public static boolean armed() {
        return armed;
    }

    public static long frequencyHz() {
        return frequencyHz;
    }

    public static void setFrequencyHz(long hz) {
        frequencyHz = Math.max(1L, Math.min(1_000_000_000L, hz));
    }

    public static void disarm() {
        armed = false;
    }
}
