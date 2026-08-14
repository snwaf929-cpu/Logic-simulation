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
    private static final int DIRECT_RANDOM_MAX_LANES = 64;
    private static final int DIRECT_BOUNDARY_MAX_BITS = 64;

    @FunctionalInterface
    public interface LongBatchConsumer {
        void accept(long[] values, int count);
    }

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

    /** Retained for the detailed/editor path and for diagnostics. */
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
        /** All RANDOM outputs in stable source order. Up to 64 lanes can use one packed simulator write. */
        private final int[] packedOutputSignalIds;
        private final long validLaneMask;
        private final long chance25Mask;
        private final long chance50Mask;
        private final long chance75Mask;
        private final long chance100Mask;
        private final long probabilisticMask;
        /** Per threshold bit, which RANDOM lanes have that bit set in their 0..256 threshold. */
        private final long[] thresholdBitMasks;
        /** True when every non-trivial chance is 25/50/75, allowing just two RNG words for the whole group. */
        private final boolean commonChanceFastPath;
        /** Non-null only when every RANDOM in this trigger group is structurally wired from the same CLOCK. */
        private final ClockAddress directClock;
        /** False for direct device/boundary RANDOM buses with no NAND consumers; those need no settle pass per sample. */
        private final boolean settleRequired;
        private boolean lastHigh;
        private long rng0;
        private long rng1;

        private RandomTriggerGroup(
                int triggerSignalId,
                List<RandomState> sourceList,
                RandomChanceBucket[] buckets,
                ClockAddress directClock,
                boolean settleRequired,
                boolean lastHigh,
                long seed
        ) {
            this.triggerSignalId = triggerSignalId;
            this.sourceCount = sourceList.size();
            this.buckets = buckets;
            this.directClock = directClock;
            this.settleRequired = settleRequired;
            this.lastHigh = lastHigh;

            this.packedOutputSignalIds = new int[sourceCount];
            this.validLaneMask = sourceCount >= 64 ? -1L : (sourceCount == 0 ? 0L : (1L << sourceCount) - 1L);
            long chance25 = 0L;
            long chance50 = 0L;
            long chance75 = 0L;
            long chance100 = 0L;
            long probabilistic = 0L;
            long[] thresholdBits = new long[8];
            boolean common = true;

            for (int lane = 0; lane < sourceCount; lane++) {
                RandomState state = sourceList.get(lane);
                packedOutputSignalIds[lane] = state.outputSignalId;
                if (lane >= 64) {
                    common = false;
                    continue;
                }
                long laneBit = 1L << lane;
                int chance = Math.max(0, Math.min(100, state.chancePercent));
                switch (chance) {
                    case 0 -> {
                    }
                    case 25 -> {
                        chance25 |= laneBit;
                        probabilistic |= laneBit;
                    }
                    case 50 -> {
                        chance50 |= laneBit;
                        probabilistic |= laneBit;
                    }
                    case 75 -> {
                        chance75 |= laneBit;
                        probabilistic |= laneBit;
                    }
                    case 100 -> chance100 |= laneBit;
                    default -> {
                        common = false;
                        probabilistic |= laneBit;
                    }
                }

                if (chance > 0 && chance < 100) {
                    int threshold = (chance * 256 + 50) / 100;
                    for (int bit = 0; bit < 8; bit++) {
                        if (((threshold >>> bit) & 1) != 0) thresholdBits[bit] |= laneBit;
                    }
                }
            }

            this.chance25Mask = chance25;
            this.chance50Mask = chance50;
            this.chance75Mask = chance75;
            this.chance100Mask = chance100;
            this.probabilisticMask = probabilistic;
            this.thresholdBitMasks = thresholdBits;
            this.commonChanceFastPath = common && sourceCount <= DIRECT_RANDOM_MAX_LANES;

            // xoroshiro128++ needs a non-zero 128-bit state.
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

    /**
     * Compiled zero-gate CLOCK -> RANDOM -> one boundary bus plan.
     *
     * Instead of writing 48 RANDOM signals and rereading a 64-bit output for every virtual cycle, this plan keeps
     * RANDOM values packed in one long, scatters those lanes directly into the boundary word through six tiny lookup
     * tables for the 48-lane benchmark, and commits only the final RANDOM state back to the simulator.
     */
    public final class DirectRandomBoundaryPlan {
        private final int generation;
        private final ClockAddress clockAddress;
        private final TimingSignalDriver clock;
        private final RandomTriggerGroup group;
        private final int[] boundarySignalIds;
        private final long randomBoundaryMask;
        private final long clockBoundaryMask;
        private final long[][] scatterTables;
        private long[] scratch = new long[2_048];

        private DirectRandomBoundaryPlan(
                int generation,
                ClockAddress clockAddress,
                TimingSignalDriver clock,
                RandomTriggerGroup group,
                int[] boundarySignalIds,
                long randomBoundaryMask,
                long clockBoundaryMask,
                long[][] scatterTables
        ) {
            this.generation = generation;
            this.clockAddress = clockAddress;
            this.clock = clock;
            this.group = group;
            this.boundarySignalIds = boundarySignalIds;
            this.randomBoundaryMask = randomBoundaryMask;
            this.clockBoundaryMask = clockBoundaryMask;
            this.scatterTables = scatterTables;
        }

        public int randomLaneCount() {
            return group.sourceCount;
        }

        public int boundaryWidth() {
            return boundarySignalIds.length;
        }

        private void ensureScratch(int count) {
            if (count <= scratch.length) return;
            int next = scratch.length;
            while (next < count) next = Math.max(next + 1, next << 1);
            scratch = new long[next];
        }

        private long scatter(long laneMask) {
            long result = 0L;
            for (int chunk = 0; chunk < scatterTables.length; chunk++) {
                result |= scatterTables[chunk][(int) ((laneMask >>> (chunk * 8)) & 0xFFL)];
            }
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
    private int randomGeneration;
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

    /**
     * Compile the aggressive boundary fast path once. It is intentionally narrow: exactly one zero-gate clock, one
     * direct RANDOM trigger group, no NAND consumers, <=64 RANDOM lanes, and every RANDOM lane visible on this boundary.
     * That makes direct packed synthesis equivalent to evaluating the source net at each RANDOM rising edge.
     */
    public DirectRandomBoundaryPlan compileDirectRandomBoundaryPlan(int[] boundarySignalIds) {
        if (boundarySignalIds == null || boundarySignalIds.length == 0 || boundarySignalIds.length > DIRECT_BOUNDARY_MAX_BITS) {
            return null;
        }
        if (clocks.size() != 1 || randomGroups.length != 1) return null;

        Map.Entry<ClockAddress, TimingSignalDriver> clockEntry = clocks.entrySet().iterator().next();
        ClockAddress clockAddress = clockEntry.getKey();
        TimingSignalDriver clock = clockEntry.getValue();
        RandomTriggerGroup group = directRandomGroup(clockAddress, clock);
        if (group == null
                || group.sourceCount <= 0
                || group.sourceCount > DIRECT_RANDOM_MAX_LANES
                || group.settleRequired
                || clock.compiledConeGateCount() != 0) {
            return null;
        }

        int[] boundary = boundarySignalIds.clone();
        long[] laneBoundaryMasks = new long[group.sourceCount];
        long randomBoundaryMask = 0L;
        long clockBoundaryMask = 0L;

        for (int bit = 0; bit < boundary.length; bit++) {
            int signalId = boundary[bit];
            long boundaryBit = 1L << bit;
            if (signalId == clock.signalId()) clockBoundaryMask |= boundaryBit;

            for (int lane = 0; lane < group.sourceCount; lane++) {
                if (signalId != group.packedOutputSignalIds[lane]) continue;
                laneBoundaryMasks[lane] |= boundaryBit;
                randomBoundaryMask |= boundaryBit;
            }
        }

        // Every RANDOM source must be represented in the selected boundary. Otherwise intermediate values could be
        // observed somewhere that this direct boundary synthesizer is not publishing.
        for (long laneMask : laneBoundaryMasks) {
            if (laneMask == 0L) return null;
        }

        int chunks = (group.sourceCount + 7) >>> 3;
        long[][] scatterTables = new long[chunks][256];
        for (int chunk = 0; chunk < chunks; chunk++) {
            int laneBase = chunk << 3;
            long[] table = scatterTables[chunk];
            for (int value = 1; value < 256; value++) {
                int lowest = Integer.numberOfTrailingZeros(value);
                int without = value & (value - 1);
                int lane = laneBase + lowest;
                table[value] = table[without] | (lane < laneBoundaryMasks.length ? laneBoundaryMasks[lane] : 0L);
            }
        }

        return new DirectRandomBoundaryPlan(
                randomGeneration,
                clockAddress,
                clock,
                group,
                boundary,
                randomBoundaryMask,
                clockBoundaryMask,
                scatterTables
        );
    }

    /**
     * Advance one compiled direct RANDOM boundary plan. Return -1 only if the plan became stale and the caller should
     * fall back to the ordinary engine. Otherwise the return value is the exact number of virtual clock edges consumed.
     */
    public long advanceDirectRandomBoundaryNanos(
            DirectRandomBoundaryPlan plan,
            long elapsedNanos,
            long edgeBudget,
            LongBatchConsumer sink
    ) {
        if (plan == null || plan.generation != randomGeneration || randomGroups.length != 1 || clocks.size() != 1) return -1L;
        if (elapsedNanos < 0L) throw new IllegalArgumentException("elapsedNanos must be >= 0");
        if (edgeBudget < 0L) throw new IllegalArgumentException("edgeBudget must be >= 0");

        TimingSignalDriver clock = plan.clock;
        if (!clock.timing().running()) return 0L;

        Integer enableId = clockEnableSignalIds.get(plan.clockAddress);
        boolean turbo = simulator.turboMode();
        if (enableId != null && !(turbo ? simulator.isHighFast(enableId) : simulator.isHigh(enableId))) return 0L;

        long emitted = clock.advanceNanosPulseBatch(elapsedNanos, edgeBudget);
        long risingEdges = clock.lastPulseRisingEdges();
        if (risingEdges <= 0L) {
            plan.group.lastHigh = clock.timing().high();
            return emitted;
        }

        if (risingEdges > Integer.MAX_VALUE) {
            throw new IllegalStateException("Direct RANDOM boundary batch is unexpectedly large: " + risingEdges);
        }

        int count = (int) risingEdges;
        plan.ensureScratch(count);

        long currentBoundary = simulator.readUnsignedFast(plan.boundarySignalIds);
        // A RANDOM sample happens on LOW->HIGH. Any boundary bit physically tied to the clock therefore reads HIGH at
        // the sampling instant. Falling-only bus changes are transport noise for this edge-triggered device stream.
        long baseRising = (currentBoundary & ~(plan.randomBoundaryMask | plan.clockBoundaryMask)) | plan.clockBoundaryMask;

        long finalLaneMask = 0L;
        for (int cycle = 0; cycle < count; cycle++) {
            long laneMask = samplePackedGroupMask(plan.group);
            finalLaneMask = laneMask;
            plan.scratch[cycle] = baseRising | plan.scatter(laneMask);
        }

        // Commit only the final RANDOM state to the primitive simulator. This replaces 48 signal writes per cycle with
        // one packed write per worker chunk while preserving the exact boundary word produced on every rising edge.
        simulator.driveBitVectorFast(plan.group.packedOutputSignalIds, 0, plan.group.sourceCount, finalLaneMask);
        plan.group.lastHigh = clock.timing().high();

        if (sink != null) sink.accept(plan.scratch, count);
        return emitted;
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
                next = clock.advanceNanosPulseBatch(elapsedNanos, edgeBudgetPerClock);
                long risingEdges = clock.lastPulseRisingEdges();
                runDirectRandomPulses(directRandom, risingEdges, afterSettledEdge);
                directRandom.lastHigh = clock.timing().high();
            } else {
                next = clock.advanceNanos(elapsedNanos, edgeBudgetPerClock, sourceCallback(afterSettledEdge));
            }

            emitted = emitted > Long.MAX_VALUE - next ? Long.MAX_VALUE : emitted + next;
        }
        return emitted;
    }

    private void runDirectRandomPulses(RandomTriggerGroup group, long risingEdges, Runnable afterSettledEdge) {
        long cycle = 0L;
        for (; cycle + 3L < risingEdges; cycle += 4L) {
            runDirectRandomPulse(group, afterSettledEdge);
            runDirectRandomPulse(group, afterSettledEdge);
            runDirectRandomPulse(group, afterSettledEdge);
            runDirectRandomPulse(group, afterSettledEdge);
        }
        for (; cycle < risingEdges; cycle++) runDirectRandomPulse(group, afterSettledEdge);
    }

    private void runDirectRandomPulse(RandomTriggerGroup group, Runnable afterSettledEdge) {
        boolean changed = sampleGroupOutputsTurbo(group);
        if (changed && group.settleRequired) simulator.runUntilStableFast(EDGE_SETTLE_BUDGET);
        runOutputCallbackIfNeeded(afterSettledEdge);
    }

    public int processRandomSources() {
        RandomTriggerGroup[] groups = randomGroups;
        if (groups.length == 0) return 0;

        boolean turbo = simulator.turboMode();
        int fired = 0;
        int maxPasses = Math.max(4, groups.length * 4 + 4);
        for (int pass = 0; pass < maxPasses; pass++) {
            boolean outputChanged = false;
            boolean settleRequired = false;

            for (RandomTriggerGroup group : groups) {
                boolean high = turbo
                        ? simulator.isHighFast(group.triggerSignalId)
                        : simulator.isHigh(group.triggerSignalId);
                boolean rising = high && !group.lastHigh;
                group.lastHigh = high;
                if (!rising) continue;

                fired += group.sourceCount;
                boolean changed = turbo ? sampleGroupOutputsTurbo(group) : sampleGroupOutputsDetailed(group);
                outputChanged |= changed;
                settleRequired |= changed && group.settleRequired;
            }

            if (!outputChanged) break;
            if (settleRequired) {
                if (turbo) simulator.runUntilStableFast(EDGE_SETTLE_BUDGET);
                else simulator.runUntilStable(EDGE_SETTLE_BUDGET);
            }
        }
        return fired;
    }

    public void synchronizeRandomInputs() {
        RandomTriggerGroup[] groups = randomGroups;
        boolean turbo = simulator.turboMode();
        for (RandomTriggerGroup group : groups) {
            group.lastHigh = turbo
                    ? simulator.isHighFast(group.triggerSignalId)
                    : simulator.isHigh(group.triggerSignalId);
        }
    }

    /**
     * Turbo RANDOM path. <=64 sources across any number of chance buckets become one packed simulator write. Common
     * 25/50/75% groups consume only two xoroshiro words total instead of two words per probability bucket.
     */
    private boolean sampleGroupOutputsTurbo(RandomTriggerGroup group) {
        if (group.sourceCount <= DIRECT_RANDOM_MAX_LANES) {
            long highMask = samplePackedGroupMask(group);
            return simulator.driveBitVectorFast(group.packedOutputSignalIds, 0, group.sourceCount, highMask);
        }

        boolean outputChanged = false;
        for (RandomChanceBucket bucket : group.buckets) {
            int[] outputSignalIds = bucket.outputSignalIds;
            for (int base = 0; base < outputSignalIds.length; base += 64) {
                int count = Math.min(64, outputSignalIds.length - base);
                long highMask = sampleMask(group, bucket.chancePercent, count);
                outputChanged |= simulator.driveBitVectorFast(outputSignalIds, base, count, highMask);
            }
        }
        return outputChanged;
    }

    private boolean sampleGroupOutputsDetailed(RandomTriggerGroup group) {
        boolean outputChanged = false;
        for (RandomChanceBucket bucket : group.buckets) {
            int[] outputSignalIds = bucket.outputSignalIds;
            for (int base = 0; base < outputSignalIds.length; base += 64) {
                int count = Math.min(64, outputSignalIds.length - base);
                long highMask = sampleMask(group, bucket.chancePercent, count);
                for (int lane = 0; lane < count; lane++) {
                    outputChanged |= simulator.driveLevel(
                            outputSignalIds[base + lane],
                            ((highMask >>> lane) & 1L) != 0L
                    );
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
        randomGeneration++;
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
            boolean settleRequired = randomOutputsNeedSettling(sourceList);
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
                    sourceList,
                    buckets,
                    directClock,
                    settleRequired,
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

    /** Compile-time only: determine whether changing any RANDOM lane can reach NAND logic. */
    private boolean randomOutputsNeedSettling(List<RandomState> sourceList) {
        for (RandomState source : sourceList) {
            int[] cone = simulator.compileAcyclicCone(source.outputSignalId);
            if (cone == null || cone.length != 0) return true;
        }
        return false;
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
        int randomNoSettleGroups = 0;
        int packedRandomGroups = 0;
        int twoWordRandomGroups = 0;
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

        for (RandomTriggerGroup group : randomGroups) {
            chanceBuckets += group.buckets.length;
            if (!group.settleRequired) randomNoSettleGroups++;
            if (group.sourceCount <= DIRECT_RANDOM_MAX_LANES) packedRandomGroups++;
            if (group.commonChanceFastPath) twoWordRandomGroups++;
        }

        LogicSimulationMod.LOGGER.info(
                "[SIM COMPILE] clocks={} queueFreeClocks={} pulseBatchClocks={} pulseBatchBoundaryBlocked={} pulseBatchEnableWired={} pulseBatchStructuralFallbacks={} pulseBatchNoDirectRandom={} feedbackClocks={} totalClockConeGates={} maxClockConeGates={} randomSources={} randomTriggerGroups={} randomChanceBuckets={} randomNoSettleGroups={} packedRandomGroups={} twoWordRandomGroups={} losslessBoundarySignals={}",
                clocks.size(), queueFreeClocks, pulseBatchClocks, pulseBatchBoundaryBlocked, pulseBatchEnableWired,
                pulseBatchStructuralFallbacks, pulseBatchNoDirectRandom, feedbackClocks, totalConeGates, maxConeGates,
                randomSources.size(), randomGroups.length, chanceBuckets, randomNoSettleGroups,
                packedRandomGroups, twoWordRandomGroups, losslessBoundarySignalIds.size()
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

    /**
     * Packed <=64-lane RANDOM sampler. For the common 25/50/75% benchmark all chance buckets share the same two
     * independent random bit-planes because their lane masks are disjoint. Arbitrary percentages use one eight-plane
     * vector compare for the whole group rather than eight random words per probability bucket.
     */
    private static long samplePackedGroupMask(RandomTriggerGroup group) {
        long valid = group.validLaneMask;
        if (valid == 0L) return 0L;

        if (group.commonChanceFastPath) {
            long result = group.chance100Mask;
            long active = group.chance25Mask | group.chance50Mask | group.chance75Mask;
            if (active == 0L) return result & valid;

            long r0 = group.nextLong();
            result |= r0 & group.chance50Mask;
            if ((group.chance25Mask | group.chance75Mask) != 0L) {
                long r1 = group.nextLong();
                result |= (r0 & r1) & group.chance25Mask;
                result |= (r0 | r1) & group.chance75Mask;
            }
            return result & valid;
        }

        long less = 0L;
        long equal = group.probabilisticMask;
        for (int bit = 7; bit >= 0; bit--) {
            long randomPlane = group.nextLong();
            long thresholdPlane = group.thresholdBitMasks[bit];
            less |= equal & thresholdPlane & ~randomPlane;
            equal &= ~(randomPlane ^ thresholdPlane);
        }
        return (group.chance100Mask | less) & valid;
    }

    private static long sampleMask(RandomTriggerGroup group, int chancePercent, int count) {
        if (count <= 0) return 0L;
        long validMask = count >= 64 ? -1L : (1L << count) - 1L;
        if (chancePercent <= 0) return 0L;
        if (chancePercent >= 100) return validMask;

        if (chancePercent == 50) return group.nextLong() & validMask;
        if (chancePercent == 25) return (group.nextLong() & group.nextLong()) & validMask;
        if (chancePercent == 75) return (group.nextLong() | group.nextLong()) & validMask;

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