package com.foreverspark.logicsim.interconnect;

/** Physical interconnect type used by future world-space cable blocks. */
public enum CableKind {
    SIGNAL,
    BUS;

    public void validateWidth(int width) {
        if (width <= 0 || width > 64) {
            throw new IllegalArgumentException("Cable width must be between 1 and 64 bits");
        }
        if (this == SIGNAL && width != 1) {
            throw new IllegalArgumentException("Signal wire requires a 1-bit port, got " + width + " bits");
        }
        if (this == BUS && width == 1) {
            throw new IllegalArgumentException("Bus cable requires a multi-bit port; use a signal wire for 1 bit");
        }
    }
}
