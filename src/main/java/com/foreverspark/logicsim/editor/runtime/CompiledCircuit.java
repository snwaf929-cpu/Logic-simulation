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
        if (signals == null) throw new IllegalArgumentException("Node " + nodeId + " is not a root input");
        for (int bit = 0; bit < signals.length; bit++) {
            simulator.driveLevel(signals[bit], ((value >>> bit) & 1L) != 0L);
        }
        simulator.runUntilStable(settleBudget);
    }

    public LogicValue[] inputValues(int nodeId, int port) { return inputValues(ROOT_SCOPE, nodeId, port); }
    public LogicValue[] outputValues(int nodeId, int port) { return outputValues(ROOT_SCOPE, nodeId, port); }
    public long inputUnsigned(int nodeId, int port) { return inputUnsigned(ROOT_SCOPE, nodeId, port); }
    public long outputUnsigned(int nodeId, int port) { return outputUnsigned(ROOT_SCOPE, nodeId, port); }

    public LogicValue[] inputValues(String scopePath, int nodeId, int port) {
        return values(signals(scopedInputs, scopePath, nodeId, port));
    }

    public LogicValue[] outputValues(String scopePath, int nodeId, int port) {
        return values(signals(scopedOutputs, scopePath, nodeId, port));
    }

    public long inputUnsigned(String scopePath, int nodeId, int port) {
        return simulator.readUnsigned(requireSignals(scopedInputs, scopePath, nodeId, port));
    }

    public long outputUnsigned(String scopePath, int nodeId, int port) {
        return simulator.readUnsigned(requireSignals(scopedOutputs, scopePath, nodeId, port));
    }

    /** Direct bus handles for high-rate runtime users. These arrays are immutable after compile. */
    public Signal[] inputSignals(String scopePath, int nodeId, int port) {
        return signals(scopedInputs, scopePath, nodeId, port);
    }

    public Signal[] outputSignals(String scopePath, int nodeId, int port) {
        return signals(scopedOutputs, scopePath, nodeId, port);
    }

    /**
     * Returns one already-compiled input signal directly. Timing sources cache this handle once instead of
     * allocating LogicValue arrays / NodePortKey lookups on every simulated edge.
     */
    public Signal inputSignal(String scopePath, int nodeId, int port, int bit) {
        Signal[] found = signals(scopedInputs, scopePath, nodeId, port);
        if (found == null || bit < 0 || bit >= found.length) return null;
        return found[bit];
    }

    /** Direct compiled output-signal counterpart used by runtime devices that need a stable handle. */
    public Signal outputSignal(String scopePath, int nodeId, int port, int bit) {
        Signal[] found = signals(scopedOutputs, scopePath, nodeId, port);
        if (found == null || bit < 0 || bit >= found.length) return null;
        return found[bit];
    }

    public boolean hasScope(String scopePath) {
        return scopedInputs.containsKey(scopePath) || scopedOutputs.containsKey(scopePath);
    }

    public Set<String> scopePaths() {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>(scopedInputs.keySet());
        result.addAll(scopedOutputs.keySet());
        return Set.copyOf(result);
    }

    public CircuitSimulator simulator() { return simulator; }

    /**
     * Deterministic scope path used by the compiler and editor inspector.
     * The chip name is deliberately not part of the identity: renaming a saved chip must not
     * invalidate an already-open live instance path. Node ids define instance identity.
     */
    public static String childScopePath(String parentScope, int nodeId, String chipName) {
        String parent = parentScope == null || parentScope.isBlank() ? ROOT_SCOPE : parentScope;
        return parent + "/CHIP" + nodeId;
    }

    private static Signal[] signals(
            Map<String, Map<NodePortKey, Signal[]>> scopes,
            String scopePath,
            int nodeId,
            int port
    ) {
        String scope = scopePath == null || scopePath.isBlank() ? ROOT_SCOPE : scopePath;
        Map<NodePortKey, Signal[]> ports = scopes.get(scope);
        if (ports == null) return null;
        return ports.get(new NodePortKey(nodeId, port));
    }

    private static Signal[] requireSignals(
            Map<String, Map<NodePortKey, Signal[]>> scopes,
            String scopePath,
            int nodeId,
            int port
    ) {
        Signal[] found = signals(scopes, scopePath, nodeId, port);
        if (found == null) throw new IllegalArgumentException("Port is not available");
        return found;
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

    private LogicValue[] values(Signal[] signals) {
        if (signals == null) return new LogicValue[0];
        LogicValue[] result = new LogicValue[signals.length];
        for (int i = 0; i < signals.length; i++) result[i] = simulator.read(signals[i]);
        return result;
    }
}
