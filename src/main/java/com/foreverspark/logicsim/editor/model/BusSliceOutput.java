package com.foreverspark.logicsim.editor.model;

import java.util.Objects;

/** One named output range on a BUS_SLICE routing component. Bits are zero-based, LSB first. */
public final class BusSliceOutput {
    public String name = "OUT";
    public int startBit = 0;
    public int width = 1;

    public BusSliceOutput() {}

    public BusSliceOutput(String name, int startBit, int width) {
        this.name = name == null ? "" : name;
        this.startBit = startBit;
        this.width = width;
    }

    public BusSliceOutput copy() {
        return new BusSliceOutput(name, startBit, width);
    }

    public void normalize(int inputWidth, int index) {
        inputWidth = Math.max(1, Math.min(64, inputWidth));
        startBit = Math.max(0, Math.min(inputWidth - 1, startBit));
        width = Math.max(1, Math.min(64, width));
        if (startBit + width > inputWidth) width = inputWidth - startBit;
        if (name == null || name.isBlank()) name = "OUT" + index;
        name = name.trim();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof BusSliceOutput other)) return false;
        return startBit == other.startBit && width == other.width && Objects.equals(name, other.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, startBit, width);
    }
}
