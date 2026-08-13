package com.foreverspark.logicsim.editor.runtime;

import com.foreverspark.logicsim.core.Signal;
import com.foreverspark.logicsim.core.TimingSignalDriver;
import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.ChipLookup;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Runtime clock sources layered on top of a compiled NAND circuit. */
public final class CircuitTimingController {
    private static final long EDGE_SETTLE_BUDGET = 10_000_000L;

    public record ClockAddress(String scopePath, int nodeId) {}

    private final CompiledCircuit compiled;
    private final Map<ClockAddress, TimingSignalDriver> clocks = new LinkedHashMap<>();

    public CircuitTimingController(CompiledCircuit compiled, CircuitDocument root, ChipLookup chips) {
        if (compiled == null) throw new IllegalArgumentException("Compiled circuit is required");
        if (root == null) throw new IllegalArgumentException("Root circuit is required");
        this.compiled = compiled;
        collect(root, chips == null ? ChipLookup.empty() : chips, CompiledCircuit.ROOT_SCOPE, Set.of());
    }

    public Set<ClockAddress> clocks() { return Set.copyOf(clocks.keySet()); }
    public boolean hasClock(String scopePath, int nodeId) { return clocks.containsKey(address(scopePath, nodeId)); }
    public long frequencyHz(String scopePath, int nodeId) { return require(scopePath, nodeId).timing().frequencyHz(); }
    public void setFrequencyHz(String scopePath, int nodeId, long frequencyHz) { require(scopePath, nodeId).timing().setFrequencyHz(frequencyHz); }
    public boolean running(String scopePath, int nodeId) { return require(scopePath, nodeId).timing().running(); }
    public void setRunning(String scopePath, int nodeId, boolean running) { require(scopePath, nodeId).timing().setRunning(running); }
    public long pendingEdges(String scopePath, int nodeId) { return require(scopePath, nodeId).timing().pendingEdges(); }

    public long stepEdges(String scopePath, int nodeId, long edges) {
        return stepEdges(scopePath, nodeId, edges, () -> {});
    }

    public long stepEdges(String scopePath, int nodeId, long edges, Runnable afterSettledEdge) {
        return require(scopePath, nodeId).stepEdges(edges, afterSettledEdge);
    }

    public long advanceNanos(long elapsedNanos, long edgeBudgetPerClock) {
        return advanceNanos(elapsedNanos, edgeBudgetPerClock, () -> {});
    }

    public long advanceNanos(long elapsedNanos, long edgeBudgetPerClock, Runnable afterSettledEdge) {
        long emitted = 0L;
        for (TimingSignalDriver clock : clocks.values()) {
            long next = clock.advanceNanos(elapsedNanos, edgeBudgetPerClock, afterSettledEdge);
            emitted = emitted > Long.MAX_VALUE - next ? Long.MAX_VALUE : emitted + next;
        }
        return emitted;
    }

    private void collect(CircuitDocument document, ChipLookup chips, String scope, Set<String> chipStack) {
        document.normalize();
        for (EditorNode node : document.nodes) {
            if (node.kind == NodeKind.CONSTANT && node.clockSource) {
                node.width = 1;
                node.constantValue = 0L;
                long frequency = Math.max(1L, Math.min(1_000_000_000L, node.clockFrequencyHz));
                node.clockFrequencyHz = frequency;
                Signal signal = compiled.simulator().signalByPath(constantSignalPath(scope, node.id));
                if (signal == null) throw new IllegalStateException("Compiled CLOCK signal not found: " + scope + "/" + node.id);
                clocks.put(new ClockAddress(scope, node.id), new TimingSignalDriver(frequency, compiled.simulator(), signal, EDGE_SETTLE_BUDGET));
            }

            if (node.kind != NodeKind.CUSTOM_CHIP) continue;
            String chipName = node.chipName == null ? "" : node.chipName.trim();
            if (chipName.isBlank() || chipStack.contains(chipName)) continue;
            ChipDefinition definition = chips.find(chipName);
            if (definition == null || definition.circuit == null) continue;
            Set<String> nestedStack = new LinkedHashSet<>(chipStack);
            nestedStack.add(chipName);
            collect(definition.circuit, chips, CompiledCircuit.childScopePath(scope, node.id, chipName), Set.copyOf(nestedStack));
        }
    }

    private TimingSignalDriver require(String scopePath, int nodeId) {
        TimingSignalDriver result = clocks.get(address(scopePath, nodeId));
        if (result == null) throw new IllegalArgumentException("CLOCK not found: " + scopePath + "/" + nodeId);
        return result;
    }

    private static ClockAddress address(String scopePath, int nodeId) {
        String scope = scopePath == null || scopePath.isBlank() ? CompiledCircuit.ROOT_SCOPE : scopePath;
        return new ClockAddress(scope, nodeId);
    }

    private static String constantSignalPath(String scope, int nodeId) {
        return scope + "/CONST" + nodeId + "[0]";
    }
}
