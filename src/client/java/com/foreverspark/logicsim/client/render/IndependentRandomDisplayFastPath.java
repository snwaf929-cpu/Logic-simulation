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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Narrower physical DISPLAY hot path for the common high-rate topology where one RANDOM group is driven by the MHz
 * CLOCK and every other RANDOM group is driven by an independent root INPUT/constant/floating-low signal.
 *
 * <p>The generic RANDOM-DAG engine must maintain an event queue because a RANDOM output may trigger another RANDOM
 * group. This specialization proves that no RANDOM output is a trigger at all. The MHz loop can therefore replace the
 * clock group's packed bits directly, perform one coordinate reject test, and pack the DISPLAY command. Independent
 * groups are edge-detected once per worker slice exactly like the generic fast path.</p>
 *
 * <p>RESET is intentionally outside the MHz data loop. The caller must monitor the compiled RESET signal and emit one
 * CLEAR on LOW->HIGH. This compiler accepts only an independent RESET source so polling it at worker-slice cadence is
 * lossless for the topology it claims to optimize.</p>
 */
public final class IndependentRandomDisplayFastPath {
    private static final long RNG_NONZERO_FALLBACK = 0x9E3779B97F4A7C15L;
    private static final long RNG_SEED_GAMMA = 0x9E3779B97F4A7C15L;
    private static final long PIXEL_OPCODE = (long) DisplayCommandCodec.OP_PIXEL << 48;
    private static final long DISPLAY_DATA_MASK = (1L << 48) - 1L;
    private static final long FIELD_MASK = 0xFFFFL;
    private static final int DISPLAY_RANDOM_LANES = 48;
    private static final Field CLOCKS_FIELD = findClocksField();

    private IndependentRandomDisplayFastPath() {}

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

        Signal reset = compiled.inputSignal(CompiledCircuit.ROOT_SCOPE, displayNode.id, 4, 0);
        if (reset == null) return fail("display-reset-unresolved");
        if (reset.id() == clock.signalId()) return fail("display-reset-clock-driven");
        if (laneBySignal.containsKey(reset.id())) return fail("display-reset-random-driven");
        ExternalSignalProof resetProof = proveIndependentSignal(compiled, board, reset);
        if (!resetProof.safe()) return fail("display-reset-not-independent:" + resetProof.detail());

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

        GroupPlan clockGroup = null;
        List<GroupPlan> externalGroups = new ArrayList<>();
        List<Integer> externalSignalIds = new ArrayList<>();
        int groupOrdinal = 0;
        for (Map.Entry<Integer, List<LaneSpec>> entry : grouped.entrySet()) {
            int triggerSignalId = entry.getKey();
            GroupPlan group = new GroupPlan(
                    entry.getValue(),
                    mix64(System.nanoTime() ^ ((long) triggerSignalId << 32) ^ groupOrdinal)
            );
            groupOrdinal++;

            if (triggerSignalId == clock.signalId()) {
                if (clockGroup != null) return fail("multiple-clock-trigger-groups");
                clockGroup = group;
                continue;
            }

            if (laneBySignal.containsKey(triggerSignalId)) {
                return fail("random-dependent-trigger-" + triggerSignalId);
            }

            ExternalSignalProof proof = proveIndependentSignal(compiled, board, triggerSignals.get(triggerSignalId));
            if (!proof.safe()) {
                return fail("trigger-not-independent-" + triggerSignalId + ":" + proof.detail());
            }
            externalGroups.add(group);
            externalSignalIds.add(triggerSignalId);
        }
        if (clockGroup == null) return fail("no-clock-triggered-random-group");

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

        int[] externalIds = externalSignalIds.stream().mapToInt(Integer::intValue).toArray();
        GroupPlan[] external = externalGroups.toArray(GroupPlan[]::new);
        return new CompileResult(new Plan(
                runtime,
                deviceIndex,
                displayWidth,
                displayHeight,
                simulator,
                clock,
                outputSignalIds,
                clockGroup,
                external,
                externalIds,
                boundaryScatter,
                boundaryIdentity,
                boundaryFieldShift,
                colorSourceShift,
                xSourceShift,
                ySourceShift,
                exactCoordinatePrefilter,
                coordinateRejectLaneMask,
                initialState
        ), "active-independent-triggers=" + external.length);
    }

    private static CompileResult fail(String reason) {
        return new CompileResult(null, reason);
    }

    public static final class Plan {
        private final CircuitProgramRuntime runtime;
        private final int deviceIndex;
        private final int displayWidth;
        private final int displayHeight;
        private final CircuitSimulator simulator;
        private final TimingSignalDriver clock;
        private final int[] outputSignalIds;
        private final GroupPlan clockGroup;
        private final GroupPlan[] externalGroups;
        private final int[] externalSignalIds;
        private final boolean[] externalLastHigh;
        private final long[][] boundaryScatter;
        private final boolean boundaryIdentity;
        private final boolean boundaryFieldShift;
        private final int colorSourceShift;
        private final int xSourceShift;
        private final int ySourceShift;
        private final boolean exactCoordinatePrefilter;
        private final long coordinateRejectLaneMask;
        private long[] scratch = new long[65_536];
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
                GroupPlan clockGroup,
                GroupPlan[] externalGroups,
                int[] externalSignalIds,
                long[][] boundaryScatter,
                boolean boundaryIdentity,
                boolean boundaryFieldShift,
                int colorSourceShift,
                int xSourceShift,
                int ySourceShift,
                boolean exactCoordinatePrefilter,
                long coordinateRejectLaneMask,
                long initialState
        ) {
            this.runtime = runtime;
            this.deviceIndex = deviceIndex;
            this.displayWidth = displayWidth;
            this.displayHeight = displayHeight;
            this.simulator = simulator;
            this.clock = clock;
            this.outputSignalIds = outputSignalIds;
            this.clockGroup = clockGroup;
            this.externalGroups = externalGroups;
            this.externalSignalIds = externalSignalIds;
            this.externalLastHigh = new boolean[externalSignalIds.length];
            for (int index = 0; index < externalSignalIds.length; index++) {
                externalLastHigh[index] = simulator.isHighFast(externalSignalIds[index]);
            }
            this.boundaryScatter = boundaryScatter;
            this.boundaryIdentity = boundaryIdentity;
            this.boundaryFieldShift = boundaryFieldShift;
            this.colorSourceShift = colorSourceShift;
            this.xSourceShift = xSourceShift;
            this.ySourceShift = ySourceShift;
            this.exactCoordinatePrefilter = exactCoordinatePrefilter;
            this.coordinateRejectLaneMask = coordinateRejectLaneMask;
            this.stateMask = initialState;
        }

        public boolean matches(CircuitProgramRuntime candidate, int candidateDevice, int width, int height) {
            return candidate == runtime
                    && candidateDevice == deviceIndex
                    && width == displayWidth
                    && height == displayHeight;
        }

        public int randomLaneCount() { return outputSignalIds.length; }
        public int clockLaneCount() { return clockGroup.laneCount; }
        public int externalTriggerGroupCount() { return externalGroups.length; }
        public long externalTriggerFireCount() { return externalTriggerFires; }
        public boolean coordinatePrefilterEnabled() { return exactCoordinatePrefilter && coordinateRejectLaneMask != 0L; }
        public int coordinatePrefilterLaneCount() { return Long.bitCount(coordinateRejectLaneMask); }
        public String boundaryPackMode() {
            if (boundaryIdentity) return "identity";
            if (boundaryFieldShift) return "field-shift";
            return "table";
        }

        public long advance(long elapsedNanos, long edgeBudget, CircuitTimingController.LongBatchConsumer sink) {
            if (elapsedNanos < 0L || edgeBudget < 0L) throw new IllegalArgumentException("clock arguments must be >= 0");

            long state = stateMask;
            for (int index = 0; index < externalSignalIds.length; index++) {
                boolean high = simulator.isHighFast(externalSignalIds[index]);
                boolean rising = high && !externalLastHigh[index];
                externalLastHigh[index] = high;
                if (!rising) continue;
                state = externalGroups[index].sampleInto(state);
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
                throw new IllegalStateException("Independent RANDOM display batch is too large: " + risingEdges);
            }

            int cycles = (int) risingEdges;
            ensureScratch(cycles);
            int outputCount = 0;
            GroupPlan hotGroup = clockGroup;

            if (exactCoordinatePrefilter) {
                if (coordinateRejectLaneMask == 0L) {
                    for (int cycle = 0; cycle < cycles; cycle++) {
                        state = hotGroup.sampleInto(state);
                        scratch[outputCount++] = PIXEL_OPCODE | packBoundary(state);
                    }
                } else {
                    for (int cycle = 0; cycle < cycles; cycle++) {
                        state = hotGroup.sampleInto(state);
                        if ((state & coordinateRejectLaneMask) != 0L) continue;
                        scratch[outputCount++] = PIXEL_OPCODE | packBoundary(state);
                    }
                }
            } else {
                for (int cycle = 0; cycle < cycles; cycle++) {
                    state = hotGroup.sampleInto(state);
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

        public void synchronizeFallback() {
            runtime.timing().synchronizeRandomInputs();
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

        private void ensureScratch(int count) {
            if (count <= scratch.length) return;
            int next = scratch.length;
            while (next < count) next = Math.max(next + 1, next << 1);
            scratch = new long[next];
        }
    }

    private static final class GroupPlan {
        private final int laneCount;
        private final long outputMask;
        private final long preserveMask;
        private final long chance25Mask;
        private final long chance50Mask;
        private final long chance75Mask;
        private final long chance100Mask;
        private final long activeCommonMask;
        private final long probabilisticMask;
        private final long[] thresholdBitMasks;
        private final boolean commonChanceFastPath;
        private final boolean needsSecondWord;
        private long rng0;
        private long rng1;

        private GroupPlan(List<LaneSpec> lanes, long seed) {
            this.laneCount = lanes.size();
            long outputs = 0L;
            long chance25 = 0L;
            long chance50 = 0L;
            long chance75 = 0L;
            long chance100 = 0L;
            long probabilistic = 0L;
            long[] thresholdBits = new long[8];
            boolean common = true;

            for (LaneSpec lane : lanes) {
                long laneBit = 1L << lane.globalLane;
                outputs |= laneBit;
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

            this.outputMask = outputs;
            this.preserveMask = ~outputs;
            this.chance25Mask = chance25;
            this.chance50Mask = chance50;
            this.chance75Mask = chance75;
            this.chance100Mask = chance100;
            this.activeCommonMask = chance25 | chance50 | chance75;
            this.probabilisticMask = probabilistic;
            this.thresholdBitMasks = thresholdBits;
            this.commonChanceFastPath = common;
            this.needsSecondWord = (chance25 | chance75) != 0L;
            this.rng0 = mix64(seed);
            this.rng1 = mix64(seed + RNG_SEED_GAMMA);
            if ((rng0 | rng1) == 0L) rng1 = RNG_NONZERO_FALLBACK;
        }

        private long sampleInto(long state) {
            return (state & preserveMask) | sampleGlobal();
        }

        private long sampleGlobal() {
            if (outputMask == 0L) return 0L;
            if (commonChanceFastPath) {
                long result = chance100Mask;
                if (activeCommonMask == 0L) return result;
                long r0 = nextLong();
                result |= r0 & chance50Mask;
                if (needsSecondWord) {
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
    private record ExternalSignalProof(boolean safe, String detail) {}

    private static ExternalSignalProof proveIndependentSignal(CompiledCircuit compiled, CircuitDocument board, Signal signal) {
        if (signal == null) return new ExternalSignalProof(false, "signal-missing");
        int signalId = signal.id();

        for (EditorNode node : board.nodes) {
            if (node == null) continue;
            boolean eligible = node.kind == NodeKind.INPUT
                    || (node.kind == NodeKind.CONSTANT && !node.clockSource && !node.randomSource);
            if (!eligible) continue;
            Signal[] outputs = compiled.outputSignals(CompiledCircuit.ROOT_SCOPE, node.id, 0);
            if (containsSignal(outputs, signalId)) {
                return new ExternalSignalProof(true, node.kind + "-node-" + node.id + ":signal=" + signalId);
            }
        }

        String path = signal.path() == null ? "" : signal.path();
        if (path.contains("/FLOAT")) {
            return new ExternalSignalProof(true, "floating-low:signal=" + signalId + ":path=" + path);
        }
        return new ExternalSignalProof(false, "signal=" + signalId + ":path=" + path);
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

    private static int contiguousFieldShift(int[] boundaryLane, int boundaryOffset) {
        int shift = boundaryLane[boundaryOffset];
        if (shift < 0 || shift > DISPLAY_RANDOM_LANES - 16) return -1;
        for (int bit = 1; bit < 16; bit++) {
            if (boundaryLane[boundaryOffset + bit] != shift + bit) return -1;
        }
        return shift;
    }

    private static boolean exactCoordinateLimit(int limit) {
        return limit > 0 && limit <= 65_536 && (limit & (limit - 1)) == 0;
    }

    private static long coordinateRejectLaneMask(int limit, int boundaryOffset, int[] boundaryLane) {
        if (limit >= 65_536) return 0L;
        int lowBits = Integer.numberOfTrailingZeros(limit);
        long result = 0L;
        for (int bit = lowBits; bit < 16; bit++) {
            result |= 1L << boundaryLane[boundaryOffset + bit];
        }
        return result;
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

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
