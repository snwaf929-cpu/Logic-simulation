package com.foreverspark.logicsim.editor.runtime;

import com.foreverspark.logicsim.core.CircuitSimulator;
import com.foreverspark.logicsim.core.LogicValue;
import com.foreverspark.logicsim.core.Signal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Runtime view of one compiled circuit, including every flattened custom-chip instance scope. */
public final class CompiledCircuit {
    public static final String ROOT_SCOPE = "ROOT";

    private final CircuitSimulator simulator;
    private final Map<Integer, Signal[]> rootInputs;
    private final Map<String, Map<NodePortKey, Signal[]>> scopedInputs;
    private final Map<String, Map<NodePortKey, Signal[]>> scopedOutputs;
    private final long settleBudget;

    CompiledCircuit(
            CircuitSimulator simulator,
            Map<Integer, Signal[]> rootInputs,
            Map<String, Map<NodePortKey, Signal[]>> scopedInputs,
            Map<String, Map<NodePortKey, Signal[]>> scopedOutputs,
            long settleBudget
    ) {
        this.simulator = simulator;
        this.rootInputs = Map.copyOf(rootInputs);
        this.scopedInputs = immutableNestedMap(scopedInputs);
        this.scopedOutputs = immutableNestedMap(scopedOutputs);
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
        return inputValues(ROOT_SCOPE, nodeId, port);
    }

    public LogicValue[] outputValues(int nodeId, int port) {
        return outputValues(ROOT_SCOPE, nodeId, port);
    }

    public long inputUnsigned(int nodeId, int port) {
        return inputUnsigned(ROOT_SCOPE, nodeId, port);
    }

    public long outputUnsigned(int nodeId, int port) {
        return outputUnsigned(ROOT_SCOPE, nodeId, port);
    }

    public LogicValue[] inputValues(String scopePath, int nodeId, int port) {
        return values(signals(scopedInputs, scopePath, nodeId, port));
    }

    public LogicValue[] outputValues(String scopePath, int nodeId, int port) {
        return values(signals(scopedOutputs, scopePath, nodeId, port));
    }

    public long inputUnsigned(String scopePath, int nodeId, int port) {
        return unsigned(signals(scopedInputs, scopePath, nodeId, port));
    }

    public long outputUnsigned(String scopePath, int nodeId, int port) {
        return unsigned(signals(scopedOutputs, scopePath, nodeId, port));
    }

    public boolean hasScope(String scopePath) {
        return scopedInputs.containsKey(scopePath) || scopedOutputs.containsKey(scopePath);
    }

    public Set<String> scopePaths() {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>(scopedInputs.keySet());
        result.addAll(scopedOutputs.keySet());
        return Set.copyOf(result);
    }

    public CircuitSimulator simulator() {
        return simulator;
    }

    /** Deterministic scope path used by the compiler and the editor breadcrumb inspector. */
    public static String childScopePath(String parentScope, int nodeId, String chipName) {
        String parent = parentScope == null || parentScope.isBlank() ? ROOT_SCOPE : parentScope;
        String name = chipName == null ? "" : chipName.trim();
        return parent + "/CHIP" + nodeId + "[" + name + "]";
    }

    private static Signal[] signals(
            Map<String, Map<NodePortKey, Signal[]>> scopes,
            String scopePath,
            int nodeId,
            int port
    ) {
        String scope = scopePath == null || scopePath.isBlank() ? ROOT_SCOPE : scopePath;
        Map<NodePortKey, Signal[]> ports = scopes.get(scope);
        if (ports == null) {
            return null;
        }
        return ports.get(new NodePortKey(nodeId, port));
    }

    private static Map<String, Map<NodePortKey, Signal[]>> immutableNestedMap(
            Map<String, Map<NodePortKey, Signal[]>> source
    ) {
        Map<String, Map<NodePortKey, Signal[]>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<NodePortKey, Signal[]>> entry : source.entrySet()) {
            result.put(entry.getKey(), Map.copyOf(entry.getValue()));
        }
        return Map.copyOf(result);
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
