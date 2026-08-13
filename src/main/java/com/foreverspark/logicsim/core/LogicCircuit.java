package com.foreverspark.logicsim.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Mutable circuit definition used by the first accurate/event-driven engine. */
public final class LogicCircuit {
    private final List<Signal> signals = new ArrayList<>();
    private final List<NandGate> gates = new ArrayList<>();

    public Signal signal(String path) {
        return signal(path, LogicValue.UNKNOWN);
    }

    public Signal signal(String path, LogicValue initialValue) {
        Signal signal = new Signal(signals.size(), path, initialValue);
        signals.add(signal);
        return signal;
    }

    public NandGate nand(String path, Signal inputA, Signal inputB, Signal output) {
        NandGate gate = new NandGate(gates.size(), path, inputA, inputB, output);
        gates.add(gate);
        inputA.addConsumer(gate);
        if (inputB != inputA) {
            inputB.addConsumer(gate);
        }
        return gate;
    }

    public List<Signal> signals() {
        return Collections.unmodifiableList(signals);
    }

    public List<NandGate> gates() {
        return Collections.unmodifiableList(gates);
    }
}
