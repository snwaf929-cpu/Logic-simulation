package com.foreverspark.logicsim.editor.runtime;

import com.foreverspark.logicsim.core.LogicValue;
import com.foreverspark.logicsim.core.Signal;
import com.foreverspark.logicsim.core.TimingSignalDriver;
import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.ChipLookup;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.WireConnection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Runtime clock sources and edge-triggered infrastructure layered on top of a compiled NAND circuit. */
public final class CircuitTimingController {
    private static final long EDGE_SETTLE_BUDGET = 10_000_000L;

    public record ClockAddress(String scopePath, int nodeId) {}
    public record RandomAddress(String scopePath, int nodeId) {}

    private static final class RandomState {
        private final Signal output;
        private final Signal trigger;
        private int chancePercent;
        private boolean lastHigh;

        private RandomState(Signal output, Signal trigger, int chancePercent) {
            this.output = output;
            this.trigger = trigger;
            this.chancePercent = chancePercent;
            this.lastHigh = trigger != null && trigger.value() == LogicValue.HIGH;
        }
    }

    private final CompiledCircuit compiled;
    private final Map<ClockAddress, TimingSignalDriver> clocks = new LinkedHashMap<>();
    /** Only clocks with a physically wired ENABLE input appear here. The Signal handle is cached once. */
    private final Map<ClockAddress, Signal> clockEnableSignals = new LinkedHashMap<>();
    private final Map<RandomAddress, RandomState> randomSources = new LinkedHashMap<>();

    public CircuitTimingController(CompiledCircuit compiled, CircuitDocument root, ChipLookup chips) {
        if (compiled == null) throw new IllegalArgumentException("Compiled circuit is required");
        if (root == null) throw new IllegalArgumentException("Root circuit is required");
        this.compiled = compiled;
        collect(root, chips == null ? ChipLookup.empty() : chips, CompiledCircuit.ROOT_SCOPE, Set.of());
    }

    /** Stable read-only views; constructing Set.copyOf on every worker slice was unnecessary allocation. */
    public Set<ClockAddress> clocks() { return Collections.unmodifiableSet(clocks.keySet()); }
    public boolean hasClock(String scopePath, int nodeId) { return clocks.containsKey(address(scopePath, nodeId)); }
    public long frequencyHz(String scopePath, int nodeId) { return require(scopePath, nodeId).timing().frequencyHz(); }
    public void setFrequencyHz(String scopePath, int nodeId, long frequencyHz) { require(scopePath, nodeId).timing().setFrequencyHz(frequencyHz); }
    public boolean running(String scopePath, int nodeId) { return require(scopePath, nodeId).timing().running(); }
    public void setRunning(String scopePath, int nodeId, boolean running) { require(scopePath, nodeId).timing().setRunning(running); }

    public Set<RandomAddress> randomSources() { return Collections.unmodifiableSet(randomSources.keySet()); }
    public boolean hasRandom(String scopePath, int nodeId) { return randomSources.containsKey(randomAddress(scopePath, nodeId)); }
    public int randomChancePercent(String scopePath, int nodeId) { return requireRandom(scopePath, nodeId).chancePercent; }
    public void setRandomChancePercent(String scopePath, int nodeId, int chancePercent) {
        requireRandom(scopePath, nodeId).chancePercent = Math.max(0, Math.min(100, chancePercent));
    }

    public boolean enabled(String scopePath, int nodeId) {
        ClockAddress address = address(scopePath, nodeId);
        require(scopePath, nodeId);
        Signal enable = clockEnableSignals.get(address);
        return enable == null || enable.value() == LogicValue.HIGH;
    }

    public boolean active(String scopePath, int nodeId) {
        return running(scopePath, nodeId) && enabled(scopePath, nodeId);
    }

    public long pendingEdges(String scopePath, int nodeId) { return require(scopePath, nodeId).timing().pendingEdges(); }

    public long stepEdges(String scopePath, int nodeId, long edges) {
        return stepEdges(scopePath, nodeId, edges, () -> {});
    }

    public long stepEdges(String scopePath, int nodeId, long edges, Runnable afterSettledEdge) {
        Runnable callback = sourceCallback(afterSettledEdge);
        return require(scopePath, nodeId).stepEdges(edges, callback);
    }

    public long advanceNanos(long elapsedNanos, long edgeBudgetPerClock) {
        return advanceNanos(elapsedNanos, edgeBudgetPerClock, () -> {});
    }

    public long advanceNanos(long elapsedNanos, long edgeBudgetPerClock, Runnable afterSettledEdge) {
        if (elapsedNanos < 0L) throw new IllegalArgumentException("elapsedNanos must be >= 0");
        if (edgeBudgetPerClock < 0L) throw new IllegalArgumentException("edgeBudgetPerClock must be >= 0");
        Runnable callback = sourceCallback(afterSettledEdge);
        long emitted = 0L;
        for (Map.Entry<ClockAddress, TimingSignalDriver> entry : clocks.entrySet()) {
            ClockAddress address = entry.getKey();
            TimingSignalDriver clock = entry.getValue();
            if (!clock.timing().running()) continue;
            Signal enable = clockEnableSignals.get(address);
            if (enable != null && enable.value() != LogicValue.HIGH) continue;
            long next = clock.advanceNanos(elapsedNanos, edgeBudgetPerClock, callback);
            emitted = emitted > Long.MAX_VALUE - next ? Long.MAX_VALUE : emitted + next;
        }
        return emitted;
    }

    /**
     * Samples every RANDOM source only on a trigger LOW -> HIGH transition.
     * Trigger Signal handles are cached during compile, so the MHz hot path performs no LogicValue[] allocation
     * and no scope/NodePortKey lookup for every edge.
     */
    public int processRandomSources() {
        if (randomSources.isEmpty()) return 0;
        int fired = 0;
        int maxPasses = Math.max(4, randomSources.size() * 4 + 4);
        for (int pass = 0; pass < maxPasses; pass++) {
            boolean outputChanged = false;
            for (RandomState state : randomSources.values()) {
                boolean high = state.trigger != null && state.trigger.value() == LogicValue.HIGH;
                boolean rising = high && !state.lastHigh;
                state.lastHigh = high;
                if (!rising) continue;

                fired++;
                boolean emitHigh = sampleHigh(state.chancePercent);
                outputChanged |= compiled.simulator().drive(state.output, LogicValue.fromBoolean(emitHigh));
            }
            if (!outputChanged) break;
            compiled.simulator().runUntilStable(EDGE_SETTLE_BUDGET);
        }
        return fired;
    }

    /** Aligns RANDOM edge memory to the current trigger levels without emitting a new value. */
    public void synchronizeRandomInputs() {
        for (RandomState state : randomSources.values()) {
            state.lastHigh = state.trigger != null && state.trigger.value() == LogicValue.HIGH;
        }
    }

    private Runnable sourceCallback(Runnable afterSettledEdge) {
        Runnable downstream = afterSettledEdge == null ? () -> {} : afterSettledEdge;
        return () -> {
            processRandomSources();
            downstream.run();
        };
    }

    private void collect(CircuitDocument document, ChipLookup chips, String scope, Set<String> chipStack) {
        document.normalize();
        for (EditorNode node : document.nodes) {
            if (node.kind == NodeKind.CONSTANT && node.randomSource) {
                node.clockSource = false;
                node.width = 1;
                node.constantValue = 0L;
                node.randomChancePercent = Math.max(0, Math.min(100, node.randomChancePercent));
                Signal output = compiled.simulator().signalByPath(constantSignalPath(scope, node.id));
                if (output == null) throw new IllegalStateException("Compiled RANDOM signal not found: " + scope + "/" + node.id);
                Signal trigger = compiled.inputSignal(scope, node.id, 0, 0);
                if (trigger == null) throw new IllegalStateException("Compiled RANDOM trigger not found: " + scope + "/" + node.id);
                RandomAddress randomAddress = new RandomAddress(scope, node.id);
                randomSources.put(randomAddress, new RandomState(output, trigger, node.randomChancePercent));
            } else if (node.kind == NodeKind.CONSTANT && node.clockSource) {
                node.width = 1;
                node.constantValue = 0L;
                long frequency = Math.max(1L, Math.min(50_000_000L, node.clockFrequencyHz));
                node.clockFrequencyHz = frequency;
                Signal signal = compiled.simulator().signalByPath(constantSignalPath(scope, node.id));
                if (signal == null) throw new IllegalStateException("Compiled CLOCK signal not found: " + scope + "/" + node.id);
                ClockAddress clockAddress = new ClockAddress(scope, node.id);
                clocks.put(clockAddress, new TimingSignalDriver(frequency, compiled.simulator(), signal, EDGE_SETTLE_BUDGET));
                if (hasEnableWire(document, node.id)) {
                    Signal enable = compiled.inputSignal(scope, node.id, 0, 0);
                    if (enable == null) throw new IllegalStateException("Compiled CLOCK enable not found: " + scope + "/" + node.id);
                    clockEnableSignals.put(clockAddress, enable);
                }
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

    private static boolean sampleHigh(int chancePercent) {
        if (chancePercent <= 0) return false;
        if (chancePercent >= 100) return true;
        return ThreadLocalRandom.current().nextInt(100) < chancePercent;
    }

    private static boolean hasEnableWire(CircuitDocument document, int clockNodeId) {
        for (WireConnection wire : document.wires) {
            if (wire.targetNodeId() == clockNodeId && wire.targetPort() == 0) return true;
        }
        return false;
    }

    private TimingSignalDriver require(String scopePath, int nodeId) {
        TimingSignalDriver result = clocks.get(address(scopePath, nodeId));
        if (result == null) throw new IllegalArgumentException("CLOCK not found: " + scopePath + "/" + nodeId);
        return result;
    }

    private RandomState requireRandom(String scopePath, int nodeId) {
        RandomState result = randomSources.get(randomAddress(scopePath, nodeId));
        if (result == null) throw new IllegalArgumentException("RANDOM not found: " + scopePath + "/" + nodeId);
        return result;
    }

    private static ClockAddress address(String scopePath, int nodeId) {
        String scope = scopePath == null || scopePath.isBlank() ? CompiledCircuit.ROOT_SCOPE : scopePath;
        return new ClockAddress(scope, nodeId);
    }

    private static RandomAddress randomAddress(String scopePath, int nodeId) {
        String scope = scopePath == null || scopePath.isBlank() ? CompiledCircuit.ROOT_SCOPE : scopePath;
        return new RandomAddress(scope, nodeId);
    }

    private static String constantSignalPath(String scope, int nodeId) {
        return scope + "/CONST" + nodeId + "[0]";
    }
}
