package com.foreverspark.logicsim.core;

import java.util.Arrays;

/**
 * Structural view over multiple 1-bit signals. Bit 0 is the least-significant bit.
 * Split/merge operations only remap wires; they do not introduce hidden logic gates.
 */
public final class Bus {
    private final String name;
    private final Signal[] bits;

    public Bus(String name, Signal[] bits) {
        if (bits.length == 0 || bits.length > 64) {
            throw new IllegalArgumentException("Bus width must be between 1 and 64 bits");
        }
        this.name = name;
        this.bits = bits.clone();
    }

    public static Bus create(LogicCircuit circuit, String name, int width) {
        if (width <= 0 || width > 64) {
            throw new IllegalArgumentException("Bus width must be between 1 and 64 bits");
        }
        Signal[] bits = new Signal[width];
        for (int bit = 0; bit < width; bit++) {
            bits[bit] = circuit.signal(name + "[" + bit + "]", LogicValue.UNKNOWN);
        }
        return new Bus(name, bits);
    }

    public String name() {
        return name;
    }

    public int width() {
        return bits.length;
    }

    public Signal bit(int index) {
        return bits[index];
    }

    public Bus slice(String sliceName, int startBit, int width) {
        if (startBit < 0 || width <= 0 || startBit + width > bits.length) {
            throw new IndexOutOfBoundsException("Invalid bus slice");
        }
        return new Bus(sliceName, Arrays.copyOfRange(bits, startBit, startBit + width));
    }

    /** Merge segments ordered from least-significant segment to most-significant segment. */
    public static Bus merge(String name, Bus... lowToHighSegments) {
        int width = Arrays.stream(lowToHighSegments).mapToInt(Bus::width).sum();
        if (width <= 0 || width > 64) {
            throw new IllegalArgumentException("Merged bus width must be between 1 and 64 bits");
        }

        Signal[] merged = new Signal[width];
        int offset = 0;
        for (Bus segment : lowToHighSegments) {
            System.arraycopy(segment.bits, 0, merged, offset, segment.bits.length);
            offset += segment.bits.length;
        }
        return new Bus(name, merged);
    }

    public void driveUnsigned(long value, CircuitSimulator simulator) {
        for (int bit = 0; bit < bits.length; bit++) {
            boolean high = ((value >>> bit) & 1L) != 0;
            simulator.drive(bits[bit], LogicValue.fromBoolean(high));
        }
    }

    public long readUnsigned() {
        long value = 0L;
        for (int bit = 0; bit < bits.length; bit++) {
            LogicValue logic = bits[bit].value();
            if (logic == LogicValue.UNKNOWN) {
                throw new IllegalStateException("Bus " + name + " contains UNKNOWN at bit " + bit);
            }
            if (logic == LogicValue.HIGH) {
                value |= (1L << bit);
            }
        }
        return value;
    }
}
