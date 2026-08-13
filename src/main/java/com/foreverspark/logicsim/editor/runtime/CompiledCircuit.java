package com.foreverspark.logicsim.editor.runtime;

import com.foreverspark.logicsim.core.CircuitSimulator;
import com.foreverspark.logicsim.core.LogicValue;
import com.foreverspark.logicsim.core.Signal;

import java.util.Map;

public final class CompiledCircuit {
    private final CircuitSimulator simulator;
    private final Map<Integer, Signal[]> rootInputs;
    private final Map<NodePortKey, Signal[]> nodeInputs;
    private final Map<NodePortKey, Signal[]> nodeOutputs;
    private final long settleBudget;

    CompiledCircuit(
            CircuitSimulator simulator,
            Map<Integer, Signal[]> rootInputs,
            Map<NodePortKey, Signal[]> nodeInputs,
            Map<NodePortKey, Signal[]> nodeOutputs,
            long settleBudget
    ) {
        this.simulator = simulator;
        this.rootInputs = Map.copyOf(rootInputs);
        this.nodeInputs = Map.copyOf(nodeInputs);
        this.nodeOutputs = Map.copyOf(nodeOutputs);
        this.settleBudget = settleBudget;
    }

    public void driveInputUnsigned(int nodeId, long value) {
        Signal[] signals = rootInputs.get(nodeId);
        if (signals == null) {
            throw new IllegalArgumentException("Node " + nodeId + " is not a root input");
        }
        for (int bit = 0; bit < signals.length; bit++) {
            simulator.drive(signals[bit], LogicValue.fromBoolean(((value >>> bit) & 1L) != 0));
        }
        simulator.runUntilStable(settleBudget);
    }

    public LogicValue[] inputValues(int nodeId, int port) {
        return values(nodeInputs.get(new NodePortKey(nodeId, port)));
    }

    public LogicValue[] outputValues(int nodeId, int port) {
        return values(nodeOutputs.get(new NodePortKey(nodeId, port)));
    }

    public long inputUnsigned(int nodeId, int port) {
        return unsigned(nodeInputs.get(new NodePortKey(nodeId, port)));
    }

    public long outputUnsigned(int nodeId, int port) {
        return unsigned(nodeOutputs.get(new NodePortKey(nodeId, port)));
    }

    public CircuitSimulator simulator() {
        return simulator;
    }

    private static LogicValue[] values(Signal[] signals) {
        if (signals == null) {
            return new LogicValue[0];
        }
        LogicValue[] result = new LogicValue[signals.length];
        for (int i = 0; i < signals.length; i++) {
            result[i] = signals[i].value();
        }
        return result;
    }

    private static long unsigned(Signal[] signals) {
        if (signals == null) {
            throw new IllegalArgumentException("Port is not available");
        }
        long value = 0L;
        for (int bit = 0; bit < signals.length; bit++) {
            LogicValue logicValue = signals[bit].value();
            if (logicValue == LogicValue.UNKNOWN) {
                throw new IllegalStateException("Port contains UNKNOWN at bit " + bit);
            }
            if (logicValue == LogicValue.HIGH) {
                value |= (1L << bit);
            }
        }
        return value;
    }
}
