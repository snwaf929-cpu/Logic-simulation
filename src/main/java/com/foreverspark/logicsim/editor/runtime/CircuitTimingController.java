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
        /** Structural fallback when a direct CLOCK -> RANDOM wire aliases unexpectedly through compiled routing. */
        private final ClockAddress directClock;
        private int chancePercent;

        private RandomState(int outputSignalId, int triggerSignalId, ClockAddress directClock, int chancePercent) {
            this.outputSignalId = outputSignalId;
            this.triggerSignalId = triggerSignalId;
            this.directClock = directClock;
            this.chancePercent = chancePercent;
        }
    }

    /** One probability bucket is stored as primitive output ids so the MHz loop never dereferences RandomState. */
    private static final class RandomChanceBucket {
        private final int chancePercent;
        private final int[] outputSignalIds;

        private RandomChanceBucket(int chancePercent, int[] outputSignalIds) {
            this.chancePercent = chancePercent;
            this.outputSignalIds = outputSignalIds;
        }
    }

    private static final class RandomTriggerGroup {
        private final int triggerSignalId;
        private final int sourceCount;
        private final RandomChanceBucket[] buckets;
        /** Non-null only when every RANDOM in this trigger group is structurally wired from the same CLOCK. */
        private final ClockAddress directClock;
        private boolean lastHigh;
        private long rng0;
        private long rng1;

        private RandomTriggerGroup(
                int triggerSignalId,
                int sourceCount,
                RandomChanceBucket[] buckets,
                ClockAddress directClock,
                boolean lastHigh,
                long seed
        ) {
            this.triggerSignalId = triggerSignalId;
            this.sourceCount = sourceCount;
            this.buckets = buckets;
            this.directClock = directClock;
            this.lastHigh = lastHigh;

            // xoroshiro128++ needs a non-zero 128-bit state. SplitMix-style seeding keeps neighboring trigger ids
            // from producing related streams while remaining a compile-time-only cost.
            this.rng0 = mix64(seed);
            this.rng1 = mix64(seed + RNG_SEED_GAMMA);
            if ((rng0 | rng1) == 0L) rng1 = RNG_NONZERO_FALLBACK;
        }

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
    private Map<Integer, RandomTriggerGroup> randomGroupByTriggerSignal = Map.of();
    private Map<ClockAddress, RandomTriggerGroup> randomGroupByDirectClock = Map.of();
    /** Signals participating in physical streams where intermediate transitions must never be collapsed. */
    private Set<Integer> losslessBoundarySignalIds = Set.of();

    public CircuitTimingController(CompiledCircuit compiled, CircuitDocument root, ChipLookup chips) {
        if (compiled == null) throw new IllegalArgumentException("Compiled circuit is required");
        if (root == null) throw new IllegalArgumentException("Root circuit is required");
        this.compiled = compiled;
        this.simulator = compiled.simulator();
        collect(root, chips == null ? ChipLookup.empty() : chips, CompiledCircuit.ROOT_SCOPE, Set.of());
        compileRandomGroups();
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
        compileRandomGroups();
    }

    /**
     * Programmed runtimes call this once after boundary ports have been indexed. Ordinary world outputs expose only
     * their newest sampled level and therefore do not need every intermediate clock edge. Lossless command streams do.
     */
    public void configureLosslessBoundarySignals(Set<Integer> signalIds) {
        losslessBoundarySignalIds = signalIds == null || signalIds.isEmpty() ? Set.of() : Set.copyOf(signalIds);
        logCompileTopology();
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

    /** Diagnostic/test hook: reports whether this CLOCK can use the direct rising-edge pulse-batch engine. */
    public boolean pulseBatchEligible(String scopePath, int nodeId) {
        ClockAddress address = address(scopePath, nodeId);
        TimingSignalDriver clock = require(scopePath, nodeId);
        return randomGroups.length == 1
                && directRandomGroup(address, clock) != null
                && clock.compiledConeGateCount() == 0
                && pulseBatchBoundarySafe(clock.signalId());
    }

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

        boolean turbo = simulator.turboMode();
        long emitted = 0L;
        for (Map.Entry<ClockAddress, TimingSignalDriver> entry : clocks.entrySet()) {
            ClockAddress address = entry.getKey();
            TimingSignalDriver clock = entry.getValue();
            if (!clock.timing().running()) continue;

            Integer enableId = clockEnableSignalIds.get(address);
            if (enableId != null && !(turbo ? simulator.isHighFast(enableId) : simulator.isHigh(enableId))) continue;

            RandomTriggerGroup directRandom = directRandomGroup(address, clock);
            boolean pulseBatch = turbo
                    && randomGroups.length == 1
                    && directRandom != null
                    && clock.compiledConeGateCount() == 0
                    && pulseBatchBoundarySafe(clock.signalId());

            long next;
            if (pulseBatch) {
                // ENABLE is sampled once per worker chunk just like the normal timing path. While it is HIGH, falling
                // clock levels are irrelevant to RANDOM and ordinary sampled outputs, so consume H/L bookkeeping in
                // O(1) and execute only useful rising-edge device work.
                next = clock.advanceNanosPulseBatch(elapsedNanos, edgeBudgetPerClock);
                long risingEdges = clock.lastPulseRisingEdges();
                for (long cycle = 0L; cycle < risingEdges; cycle++) {
                    boolean changed = sampleGroupOutputs(directRandom, true);
                    if (changed) simulator.runUntilStableFast(EDGE_SETTLE_BUDGET);
                    runOutputCallbackIfNeeded(afterSettledEdge);
                }
                directRandom.lastHigh = clock.timing().high();
            } else {
                next = clock.advanceNanos(elapsedNanos, edgeBudgetPerClock, sourceCallback(afterSettledEdge));
            }

            emitted = emitted > Long.MAX_VALUE - next ? Long.MAX_VALUE : emitted + next;
        }
        return emitted;
    }

    /**
     * RANDOM sources are grouped by shared trigger and probability. The turbo path writes up to 64 output lanes in
     * one primitive simulator loop instead of making one Java method call and object dereference per RANDOM bit.
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

                fired += group.sourceCount;
                outputChanged |= sampleGroupOutputs(group, turbo);
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

    private boolean sampleGroupOutputs(RandomTriggerGroup group, boolean turbo) {
        boolean outputChanged = false;
        RandomChanceBucket[] buckets = group.buckets;
        for (int bucketIndex = 0; bucketIndex < buckets.length; bucketIndex++) {
            RandomChanceBucket bucket = buckets[bucketIndex];
            int[] outputSignalIds = bucket.outputSignalIds;
            for (int base = 0; base < outputSignalIds.length; base += 64) {
                int count = Math.min(64, outputSignalIds.length - base);
                long highMask = sampleMask(group, bucket.chancePercent, count);
                if (turbo) {
                    outputChanged |= simulator.driveBitVectorFast(outputSignalIds, base, count, highMask);
                } else {
                    for (int lane = 0; lane < count; lane++) {
                        outputChanged |= simulator.driveLevel(
                                outputSignalIds[base + lane],
                                ((highMask >>> lane) & 1L) != 0L
                        );
                    }
                }
            }
        }
        return outputChanged;
    }

    private Runnable sourceCallback(Runnable afterSettledEdge) {
        if (randomGroups.length == 0) {
            if (afterSettledEdge == null) return null;
            return () -> runOutputCallbackIfNeeded(afterSettledEdge);
        }
        return () -> {
            processRandomSources();
            runOutputCallbackIfNeeded(afterSettledEdge);
        };
    }

    private void runOutputCallbackIfNeeded(Runnable afterSettledEdge) {
        if (afterSettledEdge == null) return;
        // Programmed runtimes track boundary signals with dirty bits. Avoid entering the block/display bridge on
        // clock edges that changed nothing externally. Editor/debug runtimes without watchers preserve old behavior.
        if (!simulator.dirtyWatchEnabledFast() || simulator.hasDirtyWatchBitsFast()) afterSettledEdge.run();
    }

    private RandomTriggerGroup directRandomGroup(ClockAddress address, TimingSignalDriver clock) {
        RandomTriggerGroup direct = randomGroupByTriggerSignal.get(clock.signalId());
        return direct != null ? direct : randomGroupByDirectClock.get(address);
    }

    private boolean pulseBatchBoundarySafe(int clockSignalId) {
        return !losslessBoundarySignalIds.contains(clockSignalId);
    }

    private void compileRandomGroups() {
        if (randomSources.isEmpty()) {
            randomGroups = new RandomTriggerGroup[0];
            randomGroupByTriggerSignal = Map.of();
            randomGroupByDirectClock = Map.of();
            return;
        }

        Map<Integer, List<RandomState>> byTrigger = new LinkedHashMap<>();
        for (RandomState state : randomSources.values()) {
            byTrigger.computeIfAbsent(state.triggerSignalId, ignored -> new ArrayList<>()).add(state);
        }

        RandomTriggerGroup[] groups = new RandomTriggerGroup[byTrigger.size()];
        Map<Integer, RandomTriggerGroup> byTriggerSignal = new LinkedHashMap<>();
        Map<ClockAddress, RandomTriggerGroup> byDirectClock = new LinkedHashMap<>();
        int out = 0;
        for (Map.Entry<Integer, List<RandomState>> entry : byTrigger.entrySet()) {
            List<RandomState> sourceList = entry.getValue();
            int triggerSignalId = entry.getKey();
            boolean high = simulator.isHigh(triggerSignalId);

            ClockAddress directClock = commonDirectClock(sourceList);
            Map<Integer, List<RandomState>> byChance = new LinkedHashMap<>();
            for (RandomState source : sourceList) {
                byChance.computeIfAbsent(source.chancePercent, ignored -> new ArrayList<>()).add(source);
            }
            RandomChanceBucket[] buckets = new RandomChanceBucket[byChance.size()];
            int bucketOut = 0;
            for (Map.Entry<Integer, List<RandomState>> chanceEntry : byChance.entrySet()) {
                List<RandomState> chanceSources = chanceEntry.getValue();
                int[] outputIds = new int[chanceSources.size()];
                for (int index = 0; index < outputIds.length; index++) {
                    outputIds[index] = chanceSources.get(index).outputSignalId;
                }
                buckets[bucketOut++] = new RandomChanceBucket(chanceEntry.getKey(), outputIds);
            }

            long seed = mix64(System.nanoTime() ^ ((long) triggerSignalId << 32) ^ sourceList.size() ^ out);
            RandomTriggerGroup group = new RandomTriggerGroup(
                    triggerSignalId,
                    sourceList.size(),
                    buckets,
                    directClock,
                    high,
                    seed
            );
            groups[out++] = group;
            byTriggerSignal.put(triggerSignalId, group);
            if (directClock != null) byDirectClock.putIfAbsent(directClock, group);
        }
        randomGroups = groups;
        randomGroupByTriggerSignal = Map.copyOf(byTriggerSignal);
        randomGroupByDirectClock = Map.copyOf(byDirectClock);
    }

    private static ClockAddress commonDirectClock(List<RandomState> sourceList) {
        ClockAddress direct = null;
        for (RandomState source : sourceList) {
            if (source.directClock == null) return null;
            if (direct == null) direct = source.directClock;
            else if (!direct.equals(source.directClock)) return null;
        }
        return direct;
    }

    private void logCompileTopology() {
        int queueFreeClocks = 0;
        int feedbackClocks = 0;
        int pulseBatchClocks = 0;
        int pulseBatchBoundaryBlocked = 0;
        int pulseBatchEnableWired = 0;
        int pulseBatchStructuralFallbacks = 0;
        int pulseBatchNoDirectRandom = 0;
        long totalConeGates = 0L;
        int maxConeGates = 0;
        int chanceBuckets = 0;
        for (Map.Entry<ClockAddress, TimingSignalDriver> entry : clocks.entrySet()) {
            TimingSignalDriver driver = entry.getValue();
            int cone = driver.compiledConeGateCount();
            if (cone >= 0) {
                queueFreeClocks++;
                totalConeGates += cone;
                maxConeGates = Math.max(maxConeGates, cone);
            } else {
                feedbackClocks++;
            }

            RandomTriggerGroup exactDirect = randomGroupByTriggerSignal.get(driver.signalId());
            RandomTriggerGroup direct = exactDirect != null ? exactDirect : randomGroupByDirectClock.get(entry.getKey());
            boolean directPulseCandidate = randomGroups.length == 1 && direct != null && cone == 0;
            if (directPulseCandidate) {
                if (clockEnableSignalIds.containsKey(entry.getKey())) pulseBatchEnableWired++;
                if (exactDirect == null) pulseBatchStructuralFallbacks++;
                if (pulseBatchBoundarySafe(driver.signalId())) pulseBatchClocks++;
                else pulseBatchBoundaryBlocked++;
            } else if (randomGroups.length == 1 && cone == 0 && direct == null) {
                pulseBatchNoDirectRandom++;
            }
        }
        for (RandomTriggerGroup group : randomGroups) chanceBuckets += group.buckets.length;
        LogicSimulationMod.LOGGER.info(
                "[SIM COMPILE] clocks={} queueFreeClocks={} pulseBatchClocks={} pulseBatchBoundaryBlocked={} pulseBatchEnableWired={} pulseBatchStructuralFallbacks={} pulseBatchNoDirectRandom={} feedbackClocks={} totalClockConeGates={} maxClockConeGates={} randomSources={} randomTriggerGroups={} randomChanceBuckets={} losslessBoundarySignals={}",
                clocks.size(), queueFreeClocks, pulseBatchClocks, pulseBatchBoundaryBlocked, pulseBatchEnableWired,
                pulseBatchStructuralFallbacks, pulseBatchNoDirectRandom, feedbackClocks, totalConeGates, maxConeGates,
                randomSources.size(), randomGroups.length, chanceBuckets, losslessBoundarySignalIds.size()
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
                        directClockSource(document, scope, node.id),
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

    private static ClockAddress directClockSource(CircuitDocument document, String scope, int randomNodeId) {
        for (WireConnection wire : document.wires) {
            if (wire.targetNodeId() != randomNodeId || wire.targetPort() != 0 || wire.sourcePort() != 0) continue;
            for (EditorNode source : document.nodes) {
                if (source.id != wire.sourceNodeId()) continue;
                if (source.kind == NodeKind.CONSTANT && source.clockSource && !source.randomSource) {
                    return new ClockAddress(scope, source.id);
                }
                return null;
            }
        }
        return null;
    }

    private static long sampleMask(RandomTriggerGroup group, int chancePercent, int count) {
        if (count <= 0) return 0L;
        long validMask = count >= 64 ? -1L : (1L << count) - 1L;
        if (chancePercent <= 0) return 0L;
        if (chancePercent >= 100) return validMask;

        if (chancePercent == 50) return group.nextLong() & validMask;
        if (chancePercent == 25) return (~group.nextLong() & ~group.nextLong()) & validMask;
        if (chancePercent == 75) return (~(group.nextLong() & group.nextLong())) & validMask;

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
