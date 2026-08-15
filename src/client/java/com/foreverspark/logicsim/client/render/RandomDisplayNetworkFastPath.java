package com.foreverspark.logicsim.client.render;

import com.foreverspark.logicsim.core.CircuitSimulator;
import com.foreverspark.logicsim.core.Signal;
import com.foreverspark.logicsim.core.TimingSignalDriver;
import com.foreverspark.logicsim.display.DisplayCommandCodec;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.ExternalDeviceType;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.WireConnection;
import com.foreverspark.logicsim.editor.runtime.CircuitTimingController;
import com.foreverspark.logicsim.editor.runtime.CompiledCircuit;
import com.foreverspark.logicsim.interconnect.CircuitProgramRuntime;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiled fast path for physical DISPLAY stress boards with a zero-NAND RANDOM trigger network.
 *
 * <p>The MHz clock is handled in large pulse batches. RANDOM groups driven by the clock or another RANDOM lane are
 * evaluated as a primitive event DAG. Independent root INPUT/ordinary CONSTANT/floating-low triggers are polled once
 * per worker slice and keep normal LOW->HIGH semantics; they are never scanned once per virtual MHz edge.</p>
 *
 * <p>The hot loop deliberately stays in one packed 48-bit RANDOM state. Group probability masks are compiled directly
 * into those global lane coordinates, so firing a group no longer needs a local-to-global scatter. For power-of-two
 * displays, X/Y high bits are compiled back into RANDOM-lane space so out-of-range coordinates can be rejected before
 * DISPLAY packing. If COLOR/X/Y each occupy one contiguous 16-lane bank, command packing becomes three shifts/masks;
 * arbitrary editor wiring keeps the compact lookup-table fallback.</p>
 */
public final class RandomDisplayNetworkFastPath {
    private static final long RNG_NONZERO_FALLBACK = 0x9E3779B97F4A7C15L;
    private static final long RNG_SEED_GAMMA = 0x9E3779B97F4A7C15L;
    private static final long PIXEL_OPCODE = (long) DisplayCommandCodec.OP_PIXEL << 48;
    private static final long DISPLAY_DATA_MASK = (1L << 48) - 1L;
    private static final long FIELD_MASK = 0xFFFFL;
    private static final int DISPLAY_RANDOM_LANES = 48;
    private static final int TRIGGER_CLOCK = -1;
    private static final int TRIGGER_EXTERNAL = -2;
    private static final Field CLOCKS_FIELD = findClocksField();

    private RandomDisplayNetworkFastPath() {}

    public record CompileResult(Plan plan, String reason) {
        public boolean active() { return plan != null; }
    }

    public static CompileResult compile(CircuitProgramRuntime runtime, int deviceIndex, int displayWidth, int displayHeight) {
        if (runtime == null) return fail("runtime-null");
        if (displayWidth <= 0 || displayHeight <= 0) return fail("display-size-invalid");
        if (deviceIndex < 0 || deviceIndex >= runtime.externalDeviceCount()) return fail("device-index-invalid");
        if (runtime.externalDeviceType(deviceIndex) != ExternalDeviceType.DISPLAY) return fail("device-not-display");
        if (runtime.outputPortCount() != 0) return fail("root-output-observer");
        if (runtime.externalDeviceCount() != 1) return fail("multiple-device-observers");

        CircuitDocument board = runtime.program().root.circuit;
        if (board == null) return fail("board-null");

        List<EditorNode> clocks = new ArrayList<>();
        List<EditorNode> randomNodes = new ArrayList<>();
        for (EditorNode node : board.nodes) {
            if (node == null || node.kind != NodeKind.CONSTANT) continue;
            if (node.randomSource) randomNodes.add(node);
            else if (node.clockSource) clocks.add(node);
        }
        if (clocks.size() != 1) return fail("clock-count-" + clocks.size());
        if (randomNodes.size() != DISPLAY_RANDOM_LANES) return fail("random-lanes-" + randomNodes.size());

        EditorNode clockNode = clocks.getFirst();
        if (hasInputWire(board, clockNode.id, 0)) return fail("clock-enable-wired");

        EditorNode displayNode = findDisplayNode(runtime, board, deviceIndex);
        if (displayNode == null) return fail("display-node-unresolved");
        if (hasInputWire(board, displayNode.id, 4)) return fail("display-reset-wired");

        TimingSignalDriver clock = clockDriver(runtime.timing(), clockNode.id);
        if (clock == null) return fail("clock-driver-unresolved");
        if (clock.compiledConeGateCount() != 0) return fail("clock-nand-cone");

        CompiledCircuit compiled = runtime.compiled();
        CircuitSimulator simulator = compiled.simulator();
        Signal write = compiled.inputSignal(CompiledCircuit.ROOT_SCOPE, displayNode.id, 3, 0);
        if (write == null || write.id() != clock.signalId()) return fail("display-write-not-clock");

        int[] outputSignalIds = new int[DISPLAY_RANDOM_LANES];
        int[] chances = new int[DISPLAY_RANDOM_LANES];
        Map<Integer, Integer> laneBySignal = new HashMap<>(DISPLAY_RANDOM_LANES * 2);
        for (int lane = 0; lane < randomNodes.size(); lane++) {
            EditorNode random = randomNodes.get(lane);
            Signal output = compiled.outputSignal(CompiledCircuit.ROOT_SCOPE, random.id, 0, 0);
            if (output == null) return fail("random-output-unresolved-" + random.id);
            if (laneBySignal.putIfAbsent(output.id(), lane) != null) return fail("random-output-alias");
            int[] cone = simulator.compileAcyclicCone(output.id());
            if (cone == null || cone.length != 0) return fail("random-nand-consumer-" + random.id);
            outputSignalIds[lane] = output.id();
            chances[lane] = Math.max(0, Math.min(100, random.randomChancePercent));
        }

        int[] boundarySignalIds = physicalDisplayBoundary(compiled, displayNode);
        if (boundarySignalIds == null) return fail("display-data-unresolved");
        int[] boundaryLane = new int[DISPLAY_RANDOM_LANES];
        boolean[] represented = new boolean[DISPLAY_RANDOM_LANES];
        boolean boundaryIdentity = true;
        for (int bit = 0; bit < boundarySignalIds.length; bit++) {
            Integer lane = laneBySignal.get(boundarySignalIds[bit]);
            if (lane == null) return fail("display-data-not-random-bit-" + bit);
            boundaryLane[bit] = lane;
            represented[lane] = true;
            boundaryIdentity &= lane == bit;
        }
        for (int lane = 0; lane < represented.length; lane++) {
            if (!represented[lane]) return fail("random-not-on-display-" + lane);
        }

        int colorSourceShift = contiguousFieldShift(boundaryLane, 0);
        int xSourceShift = contiguousFieldShift(boundaryLane, 16);
        int ySourceShift = contiguousFieldShift(boundaryLane, 32);
        boolean boundaryFieldShift = !boundaryIdentity
                && colorSourceShift >= 0
                && xSourceShift >= 0
                && ySourceShift >= 0;

        LinkedHashMap<Integer, List<LaneSpec>> grouped = new LinkedHashMap<>();
        Map<Integer, Signal> triggerSignals = new HashMap<>();
        for (int lane = 0; lane < randomNodes.size(); lane++) {
            EditorNode random = randomNodes.get(lane);
            Signal trigger = compiled.inputSignal(CompiledCircuit.ROOT_SCOPE, random.id, 0, 0);
            if (trigger == null) return fail("random-trigger-unresolved-" + random.id);
            grouped.computeIfAbsent(trigger.id(), ignored -> new ArrayList<>())
                    .add(new LaneSpec(lane, chances[lane]));
            triggerSignals.putIfAbsent(trigger.id(), trigger);
        }

        GroupPlan[] groups = new GroupPlan[grouped.size()];
        int[] ownerGroupByLane = new int[DISPLAY_RANDOM_LANES];
        Arrays.fill(ownerGroupByLane, -1);
        int groupOut = 0;
        int clockGroup = -1;
        int externalGroupCount = 0;
        for (Map.Entry<Integer, List<LaneSpec>> entry : grouped.entrySet()) {
            int triggerSignalId = entry.getKey();
            int triggerLane;
            int externalSignalId = -1;
            if (triggerSignalId == clock.signalId()) {
                triggerLane = TRIGGER_CLOCK;
                if (clockGroup >= 0) return fail("multiple-clock-trigger-groups");
                clockGroup = groupOut;
            } else {
                Integer sourceLane = laneBySignal.get(triggerSignalId);
                if (sourceLane != null) {
                    triggerLane = sourceLane;
                } else {
                    Signal trigger = triggerSignals.get(triggerSignalId);
                    ExternalTriggerProof proof = proveIndependentExternalTrigger(compiled, board, trigger);
                    if (!proof.safe()) {
                        return fail("trigger-outside-clock-random-network-" + triggerSignalId + ":" + proof.detail());
                    }
                    triggerLane = TRIGGER_EXTERNAL;
                    externalSignalId = triggerSignalId;
                    externalGroupCount++;
                }
            }

            GroupPlan group = new GroupPlan(
                    triggerLane,
                    externalSignalId,
                    entry.getValue(),
                    mix64(System.nanoTime() ^ ((long) triggerSignalId << 32) ^ groupOut)
            );
            groups[groupOut] = group;
            for (int lane : group.globalLanes) {
                if (ownerGroupByLane[lane] >= 0) return fail("random-owned-by-two-groups");
                ownerGroupByLane[lane] = groupOut;
            }
            groupOut++;
        }
        if (clockGroup < 0) return fail("no-clock-triggered-random-group");

        int[] dependentGroupByLane = new int[DISPLAY_RANDOM_LANES];
        Arrays.fill(dependentGroupByLane, -1);
        int[] parentGroup = new int[groups.length];
        Arrays.fill(parentGroup, -1);
        long triggerConsumerMask = 0L;
        for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
            int triggerLane = groups[groupIndex].triggerLane;
            if (triggerLane < 0) continue;
            if (dependentGroupByLane[triggerLane] >= 0) return fail("duplicate-random-trigger-lane-" + triggerLane);
            dependentGroupByLane[triggerLane] = groupIndex;
            triggerConsumerMask |= 1L << triggerLane;
            int owner = ownerGroupByLane[triggerLane];
            if (owner < 0) return fail("trigger-owner-missing-" + triggerLane);
            parentGroup[groupIndex] = owner;
        }
        if (hasTriggerCycle(parentGroup)) return fail("random-trigger-cycle");
        for (GroupPlan group : groups) {
            group.downstreamTriggerMask = group.globalOutputMask & triggerConsumerMask;
        }

        int[] externalGroupIndices = new int[externalGroupCount];
        int[] externalSignalIds = new int[externalGroupCount];
        int externalOut = 0;
        for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
            GroupPlan group = groups[groupIndex];
            if (group.triggerLane != TRIGGER_EXTERNAL) continue;
            externalGroupIndices[externalOut] = groupIndex;
            externalSignalIds[externalOut] = group.externalTriggerSignalId;
            externalOut++;
        }

        long[] laneBoundaryMasks = new long[DISPLAY_RANDOM_LANES];
        for (int bit = 0; bit < boundaryLane.length; bit++) {
            laneBoundaryMasks[boundaryLane[bit]] |= 1L << bit;
        }
        long[][] boundaryScatter = boundaryIdentity || boundaryFieldShift ? null : scatterTables(laneBoundaryMasks);

        boolean exactCoordinatePrefilter = exactCoordinateLimit(displayWidth) && exactCoordinateLimit(displayHeight);
        long coordinateRejectLaneMask = exactCoordinatePrefilter
                ? coordinateRejectLaneMask(displayWidth, 16, boundaryLane)
                        | coordinateRejectLaneMask(displayHeight, 32, boundaryLane)
                : 0L;

        long initialState = 0L;
        for (int lane = 0; lane < outputSignalIds.length; lane++) {
            if (simulator.isHighFast(outputSignalIds[lane])) initialState |= 1L << lane;
        }

        return new CompileResult(new Plan(
                runtime,
                deviceIndex,
                displayWidth,
                displayHeight,
                simulator,
                clock,
                outputSignalIds,
                groups,
                clockGroup,
                dependentGroupByLane,
                boundaryScatter,
                boundaryIdentity,
                boundaryFieldShift,
                colorSourceShift,
                xSourceShift,
                ySourceShift,
                exactCoordinatePrefilter,
                coordinateRejectLaneMask,
                externalGroupIndices,
                externalSignalIds,
                initialState
        ), externalGroupCount == 0 ? "active" : "active-external-triggers=" + externalGroupCount);
    }

    private static CompileResult fail(String reason) {
        return new CompileResult(null, reason);
    }

    /** One immutable compiled network plus mutable clock/RNG/state owned by one simulation worker. */
    public static final class Plan {
        private final CircuitProgramRuntime runtime;
        private final int deviceIndex;
        private final int displayWidth;
        private final int displayHeight;
        private final CircuitSimulator simulator;
        private final TimingSignalDriver clock;
        private final int[] outputSignalIds;
        private final GroupPlan[] groups;
        private final int clockGroup;
        private final int[] dependentGroupByLane;
        private final long[][] boundaryScatter;
        private final boolean boundaryIdentity;
        private final boolean boundaryFieldShift;
        private final int colorSourceShift;
        private final int xSourceShift;
        private final int ySourceShift;
        private final boolean exactCoordinatePrefilter;
        private final long coordinateRejectLaneMask;
        private final int[] eventQueue;
        private final int[] externalGroupIndices;
        private final int[] externalSignalIds;
        private final boolean[] externalLastHigh;
        private long[] scratch = new long[32_768];
        private long stateMask;
        private long externalTriggerFires;

        private Plan(
                CircuitProgramRuntime runtime,
                int deviceIndex,
                int displayWidth,
                int displayHeight,
                CircuitSimulator simulator,
                TimingSignalDriver clock,
                int[] outputSignalIds,
                GroupPlan[] groups,
                int clockGroup,
                int[] dependentGroupByLane,
                long[][] boundaryScatter,
                boolean boundaryIdentity,
                boolean boundaryFieldShift,
                int colorSourceShift,
                int xSourceShift,
                int ySourceShift,
                boolean exactCoordinatePrefilter,
                long coordinateRejectLaneMask,
                int[] externalGroupIndices,
                int[] externalSignalIds,
                long initialState
        ) {
            this.runtime = runtime;
            this.deviceIndex = deviceIndex;
            this.displayWidth = displayWidth;
            this.displayHeight = displayHeight;
            this.simulator = simulator;
            this.clock = clock;
            this.outputSignalIds = outputSignalIds;
            this.groups = groups;
            this.clockGroup = clockGroup;
            this.dependentGroupByLane = dependentGroupByLane;
            this.boundaryScatter = boundaryScatter;
            this.boundaryIdentity = boundaryIdentity;
            this.boundaryFieldShift = boundaryFieldShift;
            this.colorSourceShift = colorSourceShift;
            this.xSourceShift = xSourceShift;
            this.ySourceShift = ySourceShift;
            this.exactCoordinatePrefilter = exactCoordinatePrefilter;
            this.coordinateRejectLaneMask = coordinateRejectLaneMask;
            this.eventQueue = new int[Math.max(1, groups.length)];
            this.externalGroupIndices = externalGroupIndices;
            this.externalSignalIds = externalSignalIds;
            this.externalLastHigh = new boolean[externalSignalIds.length];
            for (int index = 0; index < externalSignalIds.length; index++) {
                this.externalLastHigh[index] = simulator.isHighFast(externalSignalIds[index]);
            }
            this.stateMask = initialState;
        }

        public int randomLaneCount() { return outputSignalIds.length; }
        public int triggerGroupCount() { return groups.length; }
        public int externalTriggerGroupCount() { return externalSignalIds.length; }
        public long externalTriggerFireCount() { return externalTriggerFires; }
        public int deviceIndex() { return deviceIndex; }
        public int displayWidth() { return displayWidth; }
        public int displayHeight() { return displayHeight; }
        public boolean coordinatePrefilterEnabled() { return exactCoordinatePrefilter && coordinateRejectLaneMask != 0L; }
        public int coordinatePrefilterLaneCount() { return Long.bitCount(coordinateRejectLaneMask); }
        public boolean boundaryIdentity() { return boundaryIdentity; }
        public String boundaryPackMode() {
            if (boundaryIdentity) return "identity";
            if (boundaryFieldShift) return "field-shift";
            return "table";
        }

        public boolean matches(CircuitProgramRuntime candidate, int candidateDevice, int width, int height) {
            return candidate == runtime
                    && candidateDevice == deviceIndex
                    && width == displayWidth
                    && height == displayHeight;
        }

        /**
         * Poll independent triggers once, then consume a bulk CLOCK edge chunk. External trigger changes update RANDOM
         * state but do not publish a pixel by themselves; DISPLAY WRITE remains the CLOCK rising edge.
         */
        public long advance(long elapsedNanos, long edgeBudget, CircuitTimingController.LongBatchConsumer sink) {
            if (elapsedNanos < 0L || edgeBudget < 0L) throw new IllegalArgumentException("clock arguments must be >= 0");

            long state = stateMask;
            for (int index = 0; index < externalSignalIds.length; index++) {
                boolean high = simulator.isHighFast(externalSignalIds[index]);
                boolean rising = high && !externalLastHigh[index];
                externalLastHigh[index] = high;
                if (!rising) continue;
                state = runCascade(state, externalGroupIndices[index]);
                externalTriggerFires++;
            }

            if (!clock.timing().running()) {
                commitState(state);
                return 0L;
            }

            long emitted = clock.advanceNanosPulseBatch(elapsedNanos, edgeBudget);
            long risingEdges = clock.lastPulseRisingEdges();
            if (risingEdges <= 0L) {
                commitState(state);
                return emitted;
            }
            if (risingEdges > Integer.MAX_VALUE) {
                throw new IllegalStateException("RANDOM display batch is too large: " + risingEdges);
            }

            int cycles = (int) risingEdges;
            ensureScratch(cycles);
            int outputCount = 0;

            if (exactCoordinatePrefilter) {
                if (coordinateRejectLaneMask == 0L) {
                    for (int cycle = 0; cycle < cycles; cycle++) {
                        state = runCascade(state, clockGroup);
                        scratch[outputCount++] = PIXEL_OPCODE | packBoundary(state);
                    }
                } else {
                    for (int cycle = 0; cycle < cycles; cycle++) {
                        state = runCascade(state, clockGroup);
                        if ((state & coordinateRejectLaneMask) != 0L) continue;
                        scratch[outputCount++] = PIXEL_OPCODE | packBoundary(state);
                    }
                }
            } else {
                for (int cycle = 0; cycle < cycles; cycle++) {
                    state = runCascade(state, clockGroup);
                    long raw = PIXEL_OPCODE | packBoundary(state);
                    int x = (int) ((raw >>> 16) & FIELD_MASK);
                    int y = (int) ((raw >>> 32) & FIELD_MASK);
                    if (x < displayWidth && y < displayHeight) scratch[outputCount++] = raw;
                }
            }

            commitState(state);
            if (sink != null && outputCount > 0) sink.accept(scratch, outputCount);
            return emitted;
        }

        /** Run one trigger and all RANDOM-output rising-edge dependents in the proven acyclic graph. */
        private long runCascade(long state, int initialGroup) {
            int head = 0;
            int tail = 0;
            eventQueue[tail++] = initialGroup;
            while (head < tail) {
                int groupIndex = eventQueue[head++];
                GroupPlan group = groups[groupIndex];
                long sampledGlobal = group.sampleGlobal();
                long nextState = (state & ~group.globalOutputMask) | sampledGlobal;
                long changed = state ^ nextState;
                state = nextState;

                long rising = changed & state & group.downstreamTriggerMask;
                while (rising != 0L) {
                    int lane = Long.numberOfTrailingZeros(rising);
                    int dependent = dependentGroupByLane[lane];
                    if (dependent >= 0) {
                        if (tail >= eventQueue.length) {
                            throw new IllegalStateException("Acyclic RANDOM trigger graph fired a group twice in one cascade");
                        }
                        eventQueue[tail++] = dependent;
                    }
                    rising &= rising - 1L;
                }
            }
            return state;
        }

        private long packBoundary(long state) {
            if (boundaryIdentity) return state & DISPLAY_DATA_MASK;
            if (boundaryFieldShift) {
                long color = (state >>> colorSourceShift) & FIELD_MASK;
                long x = (state >>> xSourceShift) & FIELD_MASK;
                long y = (state >>> ySourceShift) & FIELD_MASK;
                return color | (x << 16) | (y << 32);
            }
            return scatter(boundaryScatter, state);
        }

        private void commitState(long state) {
            if (state == stateMask) return;
            stateMask = state;
            simulator.driveBitVectorFast(outputSignalIds, 0, outputSignalIds.length, state);
        }

        /** Re-arm the ordinary RANDOM edge detector before a fallback from this compiled network. */
        public void synchronizeFallback() {
            runtime.timing().synchronizeRandomInputs();
        }

        private void ensureScratch(int count) {
            if (count <= scratch.length) return;
            int next = scratch.length;
            while (next < count) next = Math.max(next + 1, next << 1);
            scratch = new long[next];
        }
    }

    private static final class GroupPlan {
        private final int triggerLane;
        private final int externalTriggerSignalId;
        private final int[] globalLanes;
        private final long globalOutputMask;
        private final long chance25Mask;
        private final long chance50Mask;
        private final long chance75Mask;
        private final long chance100Mask;
        private final long commonActiveMask;
        private final long probabilisticMask;
        private final long[] thresholdBitMasks;
        private final boolean commonChanceFastPath;
        private final boolean commonNeedsSecondWord;
        private long downstreamTriggerMask;
        private long rng0;
        private long rng1;

        private GroupPlan(int triggerLane, int externalTriggerSignalId, List<LaneSpec> lanes, long seed) {
            this.triggerLane = triggerLane;
            this.externalTriggerSignalId = externalTriggerSignalId;
            this.globalLanes = new int[lanes.size()];

            long outputMask = 0L;
            long chance25 = 0L;
            long chance50 = 0L;
            long chance75 = 0L;
            long chance100 = 0L;
            long probabilistic = 0L;
            long[] thresholdBits = new long[8];
            boolean common = true;

            for (int index = 0; index < lanes.size(); index++) {
                LaneSpec lane = lanes.get(index);
                globalLanes[index] = lane.globalLane;
                long laneBit = 1L << lane.globalLane;
                outputMask |= laneBit;

                int chance = lane.chancePercent;
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

            this.globalOutputMask = outputMask;
            this.chance25Mask = chance25;
            this.chance50Mask = chance50;
            this.chance75Mask = chance75;
            this.chance100Mask = chance100;
            this.commonActiveMask = chance25 | chance50 | chance75;
            this.probabilisticMask = probabilistic;
            this.thresholdBitMasks = thresholdBits;
            this.commonChanceFastPath = common;
            this.commonNeedsSecondWord = (chance25 | chance75) != 0L;
            this.rng0 = mix64(seed);
            this.rng1 = mix64(seed + RNG_SEED_GAMMA);
            if ((rng0 | rng1) == 0L) rng1 = RNG_NONZERO_FALLBACK;
        }

        /** Sample directly into global 48-lane state coordinates; no local->global scatter exists in the hot loop. */
        private long sampleGlobal() {
            if (globalOutputMask == 0L) return 0L;
            if (commonChanceFastPath) {
                long result = chance100Mask;
                if (commonActiveMask == 0L) return result;
                long r0 = nextLong();
                result |= r0 & chance50Mask;
                if (commonNeedsSecondWord) {
                    long r1 = nextLong();
                    result |= (r0 & r1) & chance25Mask;
                    result |= (r0 | r1) & chance75Mask;
                }
                return result;
            }

            long less = 0L;
            long equal = probabilisticMask;
            for (int bit = 7; bit >= 0; bit--) {
                long randomPlane = nextLong();
                long thresholdPlane = thresholdBitMasks[bit];
                less |= equal & thresholdPlane & ~randomPlane;
                equal &= ~(randomPlane ^ thresholdPlane);
            }
            return chance100Mask | less;
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

    private record LaneSpec(int globalLane, int chancePercent) {}
    private record ExternalTriggerProof(boolean safe, String detail) {}

    /**
     * Passive editor routing aliases the original compiled Signal, so checking root INPUT/ordinary CONSTANT identities
     * also accepts BUS/SPLITTER/MERGER/BUS_SLICE/NET_LABEL routes from those sources. A compiler-created FLOAT is
     * immutable LOW unless another source is structurally attached later, which requires recompiling the BOARD.
     */
    private static ExternalTriggerProof proveIndependentExternalTrigger(
            CompiledCircuit compiled,
            CircuitDocument board,
            Signal trigger
    ) {
        if (trigger == null) return new ExternalTriggerProof(false, "signal-missing");
        int signalId = trigger.id();

        for (EditorNode node : board.nodes) {
            if (node == null) continue;
            boolean eligibleSource = node.kind == NodeKind.INPUT
                    || (node.kind == NodeKind.CONSTANT && !node.clockSource && !node.randomSource);
            if (!eligibleSource) continue;
            Signal[] outputs = compiled.outputSignals(CompiledCircuit.ROOT_SCOPE, node.id, 0);
            if (containsSignal(outputs, signalId)) {
                return new ExternalTriggerProof(true, node.kind + "-node-" + node.id + ":signal=" + signalId);
            }
        }

        String path = trigger.path() == null ? "" : trigger.path();
        if (path.contains("/FLOAT")) {
            return new ExternalTriggerProof(true, "floating-low:signal=" + signalId + ":path=" + path);
        }
        return new ExternalTriggerProof(false, "signal=" + signalId + ":path=" + path);
    }

    private static boolean containsSignal(Signal[] signals, int signalId) {
        if (signals == null) return false;
        for (Signal signal : signals) {
            if (signal != null && signal.id() == signalId) return true;
        }
        return false;
    }

    private static EditorNode findDisplayNode(CircuitProgramRuntime runtime, CircuitDocument board, int deviceIndex) {
        String id = runtime.externalDeviceId(deviceIndex);
        EditorNode found = null;
        for (EditorNode node : board.externalDeviceNodes()) {
            if (node.externalDeviceType != ExternalDeviceType.DISPLAY) continue;
            if (!id.equals(node.externalDeviceId)) continue;
            if (found != null) return null;
            found = node;
        }
        return found;
    }

    /** Internal physical command layout: COLOR[15:0], X[31:16], Y[47:32]. */
    private static int[] physicalDisplayBoundary(CompiledCircuit compiled, EditorNode display) {
        Signal[] x = compiled.inputSignals(CompiledCircuit.ROOT_SCOPE, display.id, 0);
        Signal[] y = compiled.inputSignals(CompiledCircuit.ROOT_SCOPE, display.id, 1);
        Signal[] color = compiled.inputSignals(CompiledCircuit.ROOT_SCOPE, display.id, 2);
        if (x == null || x.length != 16 || y == null || y.length != 16 || color == null || color.length != 16) return null;
        int[] result = new int[48];
        for (int bit = 0; bit < 16; bit++) {
            result[bit] = color[bit].id();
            result[16 + bit] = x[bit].id();
            result[32 + bit] = y[bit].id();
        }
        return result;
    }

    /** Returns the first global RANDOM lane when one boundary field is an ascending contiguous 16-lane bank. */
    private static int contiguousFieldShift(int[] boundaryLane, int boundaryOffset) {
        int shift = boundaryLane[boundaryOffset];
        if (shift < 0 || shift > DISPLAY_RANDOM_LANES - 16) return -1;
        for (int bit = 1; bit < 16; bit++) {
            if (boundaryLane[boundaryOffset + bit] != shift + bit) return -1;
        }
        return shift;
    }

    /** True when a 16-bit unsigned coordinate can be rejected exactly by testing only its high bits. */
    private static boolean exactCoordinateLimit(int limit) {
        return limit >= 65_536 || (limit > 0 && (limit & (limit - 1)) == 0);
    }

    /** Convert the high coordinate bits that must be zero into the corresponding RANDOM global-lane mask. */
    private static long coordinateRejectLaneMask(int limit, int boundaryShift, int[] boundaryLane) {
        if (limit >= 65_536) return 0L;
        int lowBits = Integer.numberOfTrailingZeros(limit);
        long result = 0L;
        for (int bit = lowBits; bit < 16; bit++) {
            int lane = boundaryLane[boundaryShift + bit];
            result |= 1L << lane;
        }
        return result;
    }

    private static boolean hasInputWire(CircuitDocument board, int nodeId, int port) {
        for (WireConnection wire : board.wires) {
            if (wire.targetNodeId() == nodeId && wire.targetPort() == port) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static TimingSignalDriver clockDriver(CircuitTimingController timing, int rootClockNodeId) {
        if (CLOCKS_FIELD == null || timing == null) return null;
        try {
            Object value = CLOCKS_FIELD.get(timing);
            if (!(value instanceof Map<?, ?> map) || map.size() != 1) return null;
            Map.Entry<?, ?> entry = map.entrySet().iterator().next();
            if (!(entry.getKey() instanceof CircuitTimingController.ClockAddress address)) return null;
            if (!CompiledCircuit.ROOT_SCOPE.equals(address.scopePath()) || address.nodeId() != rootClockNodeId) return null;
            return entry.getValue() instanceof TimingSignalDriver driver ? driver : null;
        } catch (IllegalAccessException ignored) {
            return null;
        }
    }

    private static Field findClocksField() {
        try {
            Field field = CircuitTimingController.class.getDeclaredField("clocks");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    /** A RANDOM-triggered group has one parent; external/CLOCK groups are roots. */
    private static boolean hasTriggerCycle(int[] parentGroup) {
        int[] marks = new int[parentGroup.length];
        int generation = 1;
        for (int start = 0; start < parentGroup.length; start++, generation++) {
            int current = start;
            while (current >= 0) {
                if (marks[current] == generation) return true;
                if (marks[current] < 0) break;
                marks[current] = generation;
                current = parentGroup[current];
            }
            current = start;
            while (current >= 0 && marks[current] == generation) {
                int next = parentGroup[current];
                marks[current] = -1;
                current = next;
            }
        }
        return false;
    }

    private static long[][] scatterTables(long[] laneMasks) {
        int chunks = (laneMasks.length + 7) >>> 3;
        long[][] tables = new long[chunks][256];
        for (int chunk = 0; chunk < chunks; chunk++) {
            int laneBase = chunk << 3;
            long[] table = tables[chunk];
            for (int value = 1; value < 256; value++) {
                int lowest = Integer.numberOfTrailingZeros(value);
                int without = value & (value - 1);
                int lane = laneBase + lowest;
                table[value] = table[without] | (lane < laneMasks.length ? laneMasks[lane] : 0L);
            }
        }
        return tables;
    }

    private static long scatter(long[][] tables, long laneMask) {
        long result = 0L;
        for (int chunk = 0; chunk < tables.length; chunk++) {
            result |= tables[chunk][(int) ((laneMask >>> (chunk * 8)) & 0xFFL)];
        }
        return result;
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
