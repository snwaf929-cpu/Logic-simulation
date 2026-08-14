package com.foreverspark.logicsim.editor.runtime;

import com.foreverspark.logicsim.LogicSimulationMod;
import com.foreverspark.logicsim.core.CircuitSimulator;
import com.foreverspark.logicsim.core.Signal;
import com.foreverspark.logicsim.core.TimingSignalDriver;
import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.ChipLookup;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.WireConnection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Runtime clock sources and edge-triggered infrastructure layered on top of a compiled NAND circuit. */
public final class CircuitTimingController {
    private static final long EDGE_SETTLE_BUDGET = 10_000_000L;
    private static final long RNG_NONZERO_FALLBACK = 0x9E3779B97F4A7C15L;
    private static final long RNG_SEED_GAMMA = 0x9E3779B97F4A7C15L;

    public record ClockAddress(String scopePath, int nodeId) {}
    public record RandomAddress(String scopePath, int nodeId) {}

    private static final class RandomState {
        private final int outputSignalId;
        private final int triggerSignalId;
        private int chancePercent;

        private RandomState(int outputSignalId, int triggerSignalId, int chancePercent) {
            this.outputSignalId = outputSignalId;
            this.triggerSignalId = triggerSignalId;
            this.chancePercent = chancePercent;
        }
    }

    /** Sources sharing both a trigger and probability are sampled together as a 64-lane bit vector. */
    private static final class RandomChanceBucket {
        private final int chancePercent;
        private final RandomState[] sources;

        private RandomChanceBucket(int chancePercent, RandomState[] sources) {
            this.chancePercent = chancePercent;
            this.sources = sources;
        }
    }

    private static final class RandomTriggerGroup {
        private final int triggerSignalId;
        private final RandomState[] sources;
        private final RandomChanceBucket[] buckets;
        private boolean lastHigh;
        private long rng0;
        private long rng1;

        private RandomTriggerGroup(
                int triggerSignalId,
                RandomState[] sources,
                RandomChanceBucket[] buckets,
                boolean lastHigh,
                long seed
        ) {
            this.triggerSignalId = triggerSignalId;
            this.sources = sources;
            this.buckets = buckets;
            this.lastHigh = lastHigh;

            // xoroshiro128++ needs a non-zero 128-bit state. SplitMix-style seeding keeps neighboring trigger ids
            // from producing related streams while remaining a compile-time-only cost.
            this.rng0 = mix64(seed);
            this.rng1 = mix64(seed + RNG_SEED_GAMMA);
            if ((rng0 | rng1) == 0L) rng1 = RNG_NONZERO_FALLBACK;
        }

        /**
         * xoroshiro128++ output. The previous raw xorshift64 state was fast but linear: when lanes 0..47 were
         * reassembled into X/Y/RGB buses, a huge random-pixel display exposed visible bands and rectangular
         * sections. The ++ output scrambler removes those cross-bit lattice artifacts while keeping the vector path
         * allocation-free and only a handful of integer operations per 64 RANDOM lanes.
         */
        private long nextLong() {
            long s0 = rng0;
            long s1 = rng1;
            long result = Long.rotateLeft(s0 + s1, 17) + s0;

            s1 ^= s0;
            rng0 = Long.rotateLeft(s0, 49) ^ s1 ^ (s1 << 21);
            rng1 = Long.rotateLeft(s1, 28);
            return result;
        }
    }

    private final CompiledCircuit compiled;
    private final CircuitSimulator simulator;
    private final Map<ClockAddress, TimingSignalDriver> clocks = new LinkedHashMap<>();
    private final Map<ClockAddress, Integer> clockEnableSignalIds = new LinkedHashMap<>();
    private final Map<RandomAddress, RandomState> randomSources = new LinkedHashMap<>();
    private RandomTriggerGroup[] randomGroups = new RandomTriggerGroup[0];

    public CircuitTimingController(CompiledCircuit compiled, CircuitDocument root, ChipLookup chips) {
        if (compiled == null) throw new IllegalArgumentException("Compiled circuit is required");
        if (root == null) throw new IllegalArgumentException("Root circuit is required");
        this.compiled = compiled;
        this.simulator = compiled.simulator();
        collect(root, chips == null ? ChipLookup.empty() : chips, CompiledCircuit.ROOT_SCOPE, Set.of());
        compileRandomGroups();
        logCompileTopology();
    }

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
        // Chance changes are rare editor operations; rebuilding buckets keeps the MHz hot path branch-light.
        compileRandomGroups();
    }

    public boolean enabled(String scopePath, int nodeId) {
        ClockAddress address = address(scopePath, nodeId);
        require(scopePath, nodeId);
        Integer enableId = clockEnableSignalIds.get(address);
        return enableId == null || (simulator.turboMode() ? simulator.isHighFast(enableId) : simulator.isHigh(enableId));
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
        boolean turbo = simulator.turboMode();
        long emitted = 0L;
        for (Map.Entry<ClockAddress, TimingSignalDriver> entry : clocks.entrySet()) {
            ClockAddress address = entry.getKey();
            TimingSignalDriver clock = entry.getValue();
            if (!clock.timing().running()) continue;
            Integer enableId = clockEnableSignalIds.get(address);
            if (enableId != null && !(turbo ? simulator.isHighFast(enableId) : simulator.isHigh(enableId))) continue;
            long next = clock.advanceNanos(elapsedNanos, edgeBudgetPerClock, callback);
            emitted = emitted > Long.MAX_VALUE - next ? Long.MAX_VALUE : emitted + next;
        }
        return emitted;
    }

    /**
     * RANDOM sources are grouped by shared trigger and then by probability. Up to 64 equal-probability sources are
     * sampled as one bit vector. Common 50/25/75% cases require only 1-2 PRNG state updates for an entire bus;
     * arbitrary integer percentages use an 8-bit bit-sliced comparator (max probability error < 0.2%).
     */
    public int processRandomSources() {
        RandomTriggerGroup[] groups = randomGroups;
        if (groups.length == 0) return 0;

        boolean turbo = simulator.turboMode();
        int fired = 0;
        int maxPasses = Math.max(4, groups.length * 4 + 4);
        for (int pass = 0; pass < maxPasses; pass++) {
            boolean outputChanged = false;

            for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
                RandomTriggerGroup group = groups[groupIndex];
                boolean high = turbo
                        ? simulator.isHighFast(group.triggerSignalId)
                        : simulator.isHigh(group.triggerSignalId);
                boolean rising = high && !group.lastHigh;
                group.lastHigh = high;
                if (!rising) continue;

                fired += group.sources.length;
                RandomChanceBucket[] buckets = group.buckets;
                for (int bucketIndex = 0; bucketIndex < buckets.length; bucketIndex++) {
                    RandomChanceBucket bucket = buckets[bucketIndex];
                    RandomState[] sources = bucket.sources;
                    for (int base = 0; base < sources.length; base += 64) {
                        int count = Math.min(64, sources.length - base);
                        long highMask = sampleMask(group, bucket.chancePercent, count);
                        for (int lane = 0; lane < count; lane++) {
                            RandomState state = sources[base + lane];
                            boolean nextHigh = ((highMask >>> lane) & 1L) != 0L;
                            outputChanged |= turbo
                                    ? simulator.driveLevelFast(state.outputSignalId, nextHigh)
                                    : simulator.driveLevel(state.outputSignalId, nextHigh);
                        }
                    }
                }
            }

            if (!outputChanged) break;
            if (turbo) simulator.runUntilStableFast(EDGE_SETTLE_BUDGET);
            else simulator.runUntilStable(EDGE_SETTLE_BUDGET);
        }
        return fired;
    }

    public void synchronizeRandomInputs() {
        RandomTriggerGroup[] groups = randomGroups;
        boolean turbo = simulator.turboMode();
        for (int index = 0; index < groups.length; index++) {
            RandomTriggerGroup group = groups[index];
            group.lastHigh = turbo
                    ? simulator.isHighFast(group.triggerSignalId)
                    : simulator.isHigh(group.triggerSignalId);
        }
    }

    private Runnable sourceCallback(Runnable afterSettledEdge) {
        if (randomGroups.length == 0) return afterSettledEdge;
        return () -> {
            processRandomSources();
            if (afterSettledEdge != null) afterSettledEdge.run();
        };
    }

    private void compileRandomGroups() {
        if (randomSources.isEmpty()) {
            randomGroups = new RandomTriggerGroup[0];
            return;
        }

        Map<Integer, List<RandomState>> byTrigger = new LinkedHashMap<>();
        for (RandomState state : randomSources.values()) {
            byTrigger.computeIfAbsent(state.triggerSignalId, ignored -> new ArrayList<>()).add(state);
        }

        RandomTriggerGroup[] groups = new RandomTriggerGroup[byTrigger.size()];
        int out = 0;
        for (Map.Entry<Integer, List<RandomState>> entry : byTrigger.entrySet()) {
            RandomState[] sources = entry.getValue().toArray(RandomState[]::new);
            int triggerSignalId = entry.getKey();
            boolean high = simulator.isHigh(triggerSignalId);

            Map<Integer, List<RandomState>> byChance = new LinkedHashMap<>();
            for (RandomState source : sources) {
                byChance.computeIfAbsent(source.chancePercent, ignored -> new ArrayList<>()).add(source);
            }
            RandomChanceBucket[] buckets = new RandomChanceBucket[byChance.size()];
            int bucketOut = 0;
            for (Map.Entry<Integer, List<RandomState>> chanceEntry : byChance.entrySet()) {
                buckets[bucketOut++] = new RandomChanceBucket(
                        chanceEntry.getKey(),
                        chanceEntry.getValue().toArray(RandomState[]::new)
                );
            }

            long seed = mix64(System.nanoTime() ^ ((long) triggerSignalId << 32) ^ sources.length ^ out);
            groups[out++] = new RandomTriggerGroup(triggerSignalId, sources, buckets, high, seed);
        }
        randomGroups = groups;
    }

    private void logCompileTopology() {
        int queueFreeClocks = 0;
        int feedbackClocks = 0;
        long totalConeGates = 0L;
        int maxConeGates = 0;
        int chanceBuckets = 0;
        for (TimingSignalDriver driver : clocks.values()) {
            int cone = driver.compiledConeGateCount();
            if (cone >= 0) {
                queueFreeClocks++;
                totalConeGates += cone;
                maxConeGates = Math.max(maxConeGates, cone);
            } else {
                feedbackClocks++;
            }
        }
        for (RandomTriggerGroup group : randomGroups) chanceBuckets += group.buckets.length;
        LogicSimulationMod.LOGGER.info(
                "[SIM COMPILE] clocks={} queueFreeClocks={} feedbackClocks={} totalClockConeGates={} maxClockConeGates={} randomSources={} randomTriggerGroups={} randomChanceBuckets={}",
                clocks.size(), queueFreeClocks, feedbackClocks, totalConeGates, maxConeGates,
                randomSources.size(), randomGroups.length, chanceBuckets
        );
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
                randomSources.put(randomAddress, new RandomState(
                        output.id(),
                        trigger.id(),
                        node.randomChancePercent
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

    private static long sampleMask(RandomTriggerGroup group, int chancePercent, int count) {
        if (count <= 0) return 0L;
        long validMask = count >= 64 ? -1L : (1L << count) - 1L;
        if (chancePercent <= 0) return 0L;
        if (chancePercent >= 100) return validMask;

        // Very common digital-test probabilities have exact, exceptionally cheap bit-vector implementations.
        if (chancePercent == 50) return group.nextLong() & validMask;
        if (chancePercent == 25) return (~group.nextLong() & ~group.nextLong()) & validMask;
        if (chancePercent == 75) return (~(group.nextLong() & group.nextLong())) & validMask;

        // Eight independent, scrambled 64-bit words represent the eight bits of 64 independent random bytes.
        // Compare all 64 lanes against one scalar threshold simultaneously using bitwise less-than logic.
        int threshold = (chancePercent * 256 + 50) / 100;
        if (threshold <= 0) return 0L;
        if (threshold >= 256) return validMask;

        long less = 0L;
        long equal = validMask;
        for (int bit = 7; bit >= 0; bit--) {
            long randomPlane = group.nextLong();
            if (((threshold >>> bit) & 1) != 0) {
                less |= equal & ~randomPlane;
                equal &= randomPlane;
            } else {
                equal &= ~randomPlane;
            }
        }
        return less & validMask;
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
