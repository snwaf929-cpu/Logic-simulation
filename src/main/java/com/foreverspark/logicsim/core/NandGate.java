package com.foreverspark.logicsim.core;

/** NAND is intentionally the only primitive logic gate. */
public final class NandGate {
    private final int id;
    private final String path;
    private final Signal inputA;
    private final Signal inputB;
    private final Signal output;

    NandGate(int id, String path, Signal inputA, Signal inputB, Signal output) {
        this.id = id;
        this.path = path;
        this.inputA = inputA;
        this.inputB = inputB;
        this.output = output;
    }

    public int id() {
        return id;
    }

    public String path() {
        return path;
    }

    public Signal inputA() {
        return inputA;
    }

    public Signal inputB() {
        return inputB;
    }

    public Signal output() {
        return output;
    }

    LogicValue evaluate() {
        return LogicValue.nand(inputA.value(), inputB.value());
    }
}
