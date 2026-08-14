package com.foreverspark.logicsim.editor.runtime;

import com.foreverspark.logicsim.core.CircuitSimulator;
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

/** Runtime clock sources and edge-triggered infrastructure layered on top of a compiled NAND circuit. */
public final class CircuitTimingController {
    private static final long EDGE_SETTLE_BUDGET = 10_000_000L;
    private static final long RNG_NONZERO_FALLBACK = 0x9E3779B97F4A7C15L;

    public record ClockAddress(String scopePath, int nodeId) {}
    public record RandomAddress(String scopePath, int nodeId) {}

    private static final class RandomState {
        private final int outputSignalId;
        private final int triggerSignalId;
        private int chancePercent;
        private boolean lastHigh;
        private long rngState;

        private RandomState(int outputSignalId, int triggerSignalId, int chancePercent, boolean lastHigh, long seed) {
            this.outputSignalId = outputSignalId;
            this.triggerSignalId = triggerSignalId;
            this.chancePercent = chancePercent;
            this.lastHigh = lastHigh;
            this.rngState = seed == 0L ? RNG_NONZERO_FALLBACK : seed;
        }
    }

    private final CompiledCircuit compiled;
    private final CircuitSimulator simulator;
    private final Map<ClockAddress, TimingSignalDriver> clocks = new LinkedHashMap<>();
    /** Only clocks with a physically wired ENABLE input appear here. Signal ids are cached once. */
    private final Map<ClockAddress, Integer> clockEnableSignalIds = new LinkedHashMap<>();
    private final Map<RandomAddress, RandomState> randomSources = new LinkedHashMap<>();
    /** Flat array avoids a Map.values() iterator on every MHz clock edge. */
    private RandomState[] randomRuntime = new RandomState[0];

    public CircuitTimingController(CompiledCircuit compiled, CircuitDocument root, ChipLookup chips) {
        if (compiled == null) throw new IllegalArgumentException("Compiled circuit is required");
        if (root == null) throw new IllegalArgumentException("Root circuit is required");
        this.compiled = compiled;
        this.simulator = compiled.simulator();
        collect(root, chips == null ? ChipLookup.empty() : chips, CompiledCircuit.ROOT_SCOPE, Set.of());
        randomRuntime = randomSources.values().toArray(RandomState[]::new);
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
        Integer enableId = clockEnableSignalIds.get(address);
        return enableId == null || simulator.isHigh(enableId);
    }

    public boolean active(String scopePath, int nodeId) {
        return running(scopePath, nodeId) && enabled(scopePath, nodeId);
    }

    public long pendingEdges(String scopePath, int nodeId) { return require(scopePath, nodeId).timing().pendingEdges(); }

    public long stepEdges(String scopePath, int nodeId, long edges) {
        return stepEdges(scopePath, nodeId, edges, null);
    }

    public long stepEdges(String scopePath, int nodeId, long edges, Runnable afterSettledEdge) {
        Runnable callback = sourceCallback(afterSettledEdge);
        return require(scopePath, nodeId).stepEdges(edges, callback);
    }

    public long advanceNanos(long elapsedNanos, long edgeBudgetPerClock) {
        return advanceNanos(elapsedNanos, edgeBudgetPerClock, null);
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
            Integer enableId = clockEnableSignalIds.get(address);
            if (enableId != null && !simulator.isHigh(enableId)) continue;
            long next = clock.advanceNanos(elapsedNanos, edgeBudgetPerClock, callback);
            emitted = emitted > Long.MAX_VALUE - next ? Long.MAX_VALUE : emitted + next;
        }
        return emitted;
    }

    /**
     * Samples every RANDOM source only on a trigger LOW -> HIGH transition.
     * Runtime sources are a flat primitive-handle array; no iterator, Signal.value(), or ThreadLocalRandom lookup is
     * performed in the MHz path.
     */
    public int processRandomSources() {
        RandomState[] states = randomRuntime;
        if (states.length == 0) return 0;

        int fired = 0;
        int maxPasses = Math.max(4, states.length * 4 + 4);
        for (int pass = 0; pass < maxPasses; pass++) {
            boolean outputChanged = false;
            for (int index = 0; index < states.length; index++) {
                RandomState state = states[index];
                boolean high = simulator.isHigh(state.triggerSignalId);
                boolean rising = high && !state.lastHigh;
                state.lastHigh = high;
                if (!rising) continue;

                fired++;
                outputChanged |= simulator.driveLevel(state.outputSignalId, sampleHigh(state));
            }
            if (!outputChanged) break;
            simulator.runUntilStable(EDGE_SETTLE_BUDGET);
        }
        return fired;
    }

    /** Aligns RANDOM edge memory to the current trigger levels without emitting a new value. */
    public void synchronizeRandomInputs() {
        RandomState[] states = randomRuntime;
        for (int index = 0; index < states.length; index++) {
            RandomState state = states[index];
            state.lastHigh = simulator.isHigh(state.triggerSignalId);
        }
    }

    private Runnable sourceCallback(Runnable afterSettledEdge) {
        if (randomRuntime.length == 0) return afterSettledEdge;
        return () -> {
            processRandomSources();
            if (afterSettledEdge != null) afterSettledEdge.run();
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
                Signal output = simulator.signalByPath(constantSignalPath(scope, node.id));
                if (output == null) throw new IllegalStateException("Compiled RANDOM signal not found: " + scope + "/" + node.id);
                Signal trigger = compiled.inputSignal(scope, node.id, 0, 0);
                if (trigger == null) throw new IllegalStateException("Compiled RANDOM trigger not found: " + scope + "/" + node.id);
                RandomAddress randomAddress = new RandomAddress(scope, node.id);
                long seed = mix64(System.nanoTime() ^ ((long) output.id() << 32) ^ trigger.id() ^ node.id);
                randomSources.put(randomAddress, new RandomState(
                        output.id(),
                        trigger.id(),
                        node.randomChancePercent,
                        simulator.isHigh(trigger.id()),
                        seed
                ));
            } else if (node.kind == NodeKind.CONSTANT && node.clockSource) {
                node.width = 1;
                node.constantValue = 0L;
                long frequency = Math.max(1L, Math.min(50_000_000L, node.clockFrequencyHz));
                node.clockFrequencyHz = frequency;
                Signal signal = simulator.signalByPath(constantSignalPath(scope, node.id));
                if (signal == null) throw new IllegalStateException("Compiled CLOCK signal not found: " + scope + "/" + node.id);
                ClockAddress clockAddress = new ClockAddress(scope, node.id);
                clocks.put(clockAddress, new TimingSignalDriver(frequency, simulator, signal, EDGE_SETTLE_BUDGET));
                if (hasEnableWire(document, node.id)) {
                    Signal enable = compiled.inputSignal(scope, node.id, 0, 0);
                    if (enable == null) throw new IllegalStateException("Compiled CLOCK enable not found: " + scope + "/" + node.id);
                    clockEnableSignalIds.put(clockAddress, enable.id());
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

    private static boolean sampleHigh(RandomState state) {
        int chancePercent = state.chancePercent;
        if (chancePercent <= 0) return false;
        if (chancePercent >= 100) return true;

        // xorshift64: tiny state, no ThreadLocal lookup, and more than adequate for a simulated RANDOM component.
        long x = state.rngState;
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        if (x == 0L) x = RNG_NONZERO_FALLBACK;
        state.rngState = x;

        long unsigned32 = (x >>> 32) & 0xFFFF_FFFFL;
        int bucket0to99 = (int) ((unsigned32 * 100L) >>> 32);
        return bucket0to99 < chancePercent;
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
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
