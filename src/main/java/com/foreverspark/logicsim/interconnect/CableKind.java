package com.foreverspark.logicsim.interconnect;

import java.util.Set;

/** Physical interconnect type used by world-space cable blocks. */
public enum CableKind {
    SIGNAL,
    BUS;

    private static final Set<Integer> PHYSICAL_BUS_WIDTHS = Set.of(2, 4, 8, 16, 32, 64);

    public void validateWidth(int width) {
        if (this == SIGNAL) {
            if (width != 1) {
                throw new IllegalArgumentException("Signal wire requires a 1-bit port, got " + width + " bits");
            }
            return;
        }
        if (!PHYSICAL_BUS_WIDTHS.contains(width)) {
            throw new IllegalArgumentException("Physical bus cable width must be one of 2, 4, 8, 16, 32, or 64 bits; got " + width);
        }
    }

    public boolean supportsWidth(int width) {
        try {
            validateWidth(width);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
