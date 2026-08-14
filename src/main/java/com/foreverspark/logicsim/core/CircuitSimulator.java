package com.foreverspark.logicsim.core;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Event-driven NAND simulator with compiled turbo paths.
 *
 * Debug/editor circuits retain exact object-level behavior. Programmed circuits use primitive state. When the NAND
 * graph is acyclic, clock source fanout is compiled once into a topologically ordered cone and evaluated with a tight
 * array loop: no event-queue push/pop, queued flags, fanout scheduling, Signal writes, tracing, or enum conversion.
 * Feedback/latch circuits automatically fall back to the primitive event engine.
 */
public final class CircuitSimulator {
    private static final byte LOW = 0;
    private static final byte HIGH = 1;
    private static final byte UNKNOWN = 2;

    private final LogicCircuit circuit;
    private final TraceRecorder traceRecorder;

    private final Signal[] signals;
    private final byte[] values;
    private final int[] gateInputA;
    private final int[] gateInputB;
    private final int[] gateOutput;
    private final int[] consumerOffsets;
    private final int[] consumerGateIds;
    private final boolean[] queued;
    private final int[] queue;
    private final Map<String, Signal> signalsByPath;
    private final long[] dirtyWatchBitsBySignal;
    /** Null means the gate graph contains feedback/multiple drivers and must use event propagation. */
    private final int[] topologicalGateOrder;

    private int queueHead;
    private int queueTail;
    private int queueSize;
    private long transitionSequence;
    private long totalGateEvaluations;
    private long dirtyWatchBits;
    private boolean dirtyWatchEnabled;
    private boolean mirrorSignalObjects = true;

    public CircuitSimulator(LogicCircuit circuit, TraceRecorder traceRecorder) {
        if (circuit == null) throw new IllegalArgumentException("circuit is required");
        this.circuit = circuit;
        this.traceRecorder = traceRecorder;

        this.signals = circuit.signals().toArray(Signal[]::new);
        NandGate[] gates = circuit.gates().toArray(NandGate[]::new);
        this.values = new byte[signals.length];
        this.dirtyWatchBitsBySignal = new long[signals.length];
        this.signalsByPath = new HashMap<>(Math.max(16, signals.length * 2));

        this.consumerOffsets = new int[signals.length + 1];
        int totalConsumers = 0;
        for (int signalId = 0; signalId < signals.length; signalId++) {
            Signal signal = signals[signalId];
            values[signalId] = encode(signal.value());
            signalsByPath.put(signal.path(), signal);
            consumerOffsets[signalId] = totalConsumers;
            totalConsumers += signal.consumers().size();
        }
        consumerOffsets[signals.length] = totalConsumers;
        this.consumerGateIds = new int[totalConsumers];
        int consumerCursor = 0;
        for (Signal signal : signals) {
            List<NandGate> downstream = signal.consumers();
            for (int index = 0; index < downstream.size(); index++) {
                consumerGateIds[consumerCursor++] = downstream.get(index).id();
            }
        }

        this.gateInputA = new int[gates.length];
        this.gateInputB = new int[gates.length];
        this.gateOutput = new int[gates.length];
        for (int gateId = 0; gateId < gates.length; gateId++) {
            NandGate gate = gates[gateId];
            gateInputA[gateId] = gate.inputA().id();
            gateInputB[gateId] = gate.inputB().id();
            gateOutput[gateId] = gate.output().id();
        }
        this.topologicalGateOrder = compileTopologicalOrder();

        this.queued = new boolean[gates.length];
        this.queue = new int[Math.max(1, gates.length)];
    }

    public CircuitSimulator(LogicCircuit circuit) {
        this(circuit, null);
    }

    public void enableTurboMode() {
        if (traceRecorder != null) return;
        mirrorSignalObjects = false;
    }

    public boolean turboMode() {
        return !mirrorSignalObjects && traceRecorder == null;
    }

    public boolean acyclicTurboAvailable() {
        return topologicalGateOrder != null;
    }

    public void scheduleAll() {
        for (int gateId = 0; gateId < gateInputA.length; gateId++) schedule(gateId);
    }

    public boolean drive(Signal signal, LogicValue value) {
        if (signal == null || value == null) throw new IllegalArgumentException("signal and value are required");
        int signalId = requireSignalId(signal);
        return turboMode() ? updateSignalTurbo(signalId, encode(value)) : updateSignalDetailed(signalId, encode(value));
    }

    public boolean driveLevel(Signal signal, boolean high) {
        if (signal == null) throw new IllegalArgumentException("signal is required");
        int signalId = requireSignalId(signal);
        return turboMode() ? updateSignalTurbo(signalId, high ? HIGH : LOW) : updateSignalDetailed(signalId, high ? HIGH : LOW);
    }

    public boolean driveLevel(int signalId, boolean high) {
        requireSignalId(signalId);
        return turboMode() ? updateSignalTurbo(signalId, high ? HIGH : LOW) : updateSignalDetailed(signalId, high ? HIGH : LOW);
    }

    public boolean driveLevelFast(int signalId, boolean high) {
        return updateSignalTurbo(signalId, high ? HIGH : LOW);
    }

    /**
     * Drives up to 64 compile-validated one-bit lanes from one packed mask. This is intentionally validation-free:
     * RANDOM/GPU/device compilers construct the id arrays once, then MHz execution stays in one primitive loop.
     *
     * The queue-free topology path is deliberately inlined here. RANDOM-heavy display workloads call this millions
     * of times per second; avoiding one helper call and one dirty-watch OR per lane materially reduces CPU work.
     */
    public boolean driveBitVectorFast(int[] signalIds, int offset, int count, long highMask) {
        boolean changed = false;
        int end = offset + count;
        if (topologicalGateOrder != null) {
            boolean watchDirty = dirtyWatchEnabled;
            long dirty = 0L;
            for (int index = offset, lane = 0; index < end; index++, lane++) {
                int signalId = signalIds[index];
                byte next = (byte) ((highMask >>> lane) & 1L);
                if (values[signalId] == next) continue;
                values[signalId] = next;
                changed = true;
                if (watchDirty) dirty |= dirtyWatchBitsBySignal[signalId];
            }
            if (watchDirty) dirtyWatchBits |= dirty;
        } else {
            for (int index = offset, lane = 0; index < end; index++, lane++) {
                changed |= updateSignalTurbo(signalIds[index], ((highMask >>> lane) & 1L) != 0L ? HIGH : LOW);
            }
        }
        return changed;
    }

    public boolean isHigh(Signal signal) {
        return values[requireSignalId(signal)] == HIGH;
    }

    public boolean isHigh(int signalId) {
        requireSignalId(signalId);
        return values[signalId] == HIGH;
    }

    public boolean isHighFast(int signalId) {
        return values[signalId] == HIGH;
    }

    public boolean dirtyWatchEnabledFast() {
        return dirtyWatchEnabled;
    }

    public boolean hasDirtyWatchBitsFast() {
        return dirtyWatchBits != 0L;
    }

    public boolean isDirtyWatchedSignalFast(int signalId) {
        return dirtyWatchBitsBySignal[signalId] != 0L;
    }

    public LogicValue read(Signal signal) {
        return decode(values[requireSignalId(signal)]);
    }

    public LogicValue read(int signalId) {
        requireSignalId(signalId);
        return decode(values[signalId]);
    }

    public Signal signalByPath(String path) {
        return path == null ? null : signalsByPath.get(path);
    }

    public int[] signalIds(Signal[] bus) {
        if (bus == null) throw new IllegalArgumentException("bus is required");
        int[] ids = new int[bus.length];
        for (int bit = 0; bit < bus.length; bit++) ids[bit] = requireSignalId(bus[bit]);
        return ids;
    }

    /**
     * Compile the exact downstream gate cone of a source signal. Null means feedback exists, so callers must use
     * event propagation. An empty array is a valid acyclic source with no NAND consumers.
     */
    public int[] compileAcyclicCone(int sourceSignalId) {
        requireSignalId(sourceSignalId);
        if (topologicalGateOrder == null) return null;
        if (gateInputA.length == 0) return new int[0];

        boolean[] reachable = new boolean[gateInputA.length];
        int[] work = new int[gateInputA.length];
        int head = 0;
        int tail = 0;
        int reachableCount = 0;

        int start = consumerOffsets[sourceSignalId];
        int end = consumerOffsets[sourceSignalId + 1];
        for (int index = start; index < end; index++) {
            int gateId = consumerGateIds[index];
            if (!reachable[gateId]) {
                reachable[gateId] = true;
                work[tail++] = gateId;
                reachableCount++;
            }
        }

        while (head < tail) {
            int gateId = work[head++];
            int outputSignalId = gateOutput[gateId];
            start = consumerOffsets[outputSignalId];
            end = consumerOffsets[outputSignalId + 1];
            for (int index = start; index < end; index++) {
                int consumer = consumerGateIds[index];
                if (!reachable[consumer]) {
                    reachable[consumer] = true;
                    work[tail++] = consumer;
                    reachableCount++;
                }
            }
        }

        int[] cone = new int[reachableCount];
        int out = 0;
        for (int gateId : topologicalGateOrder) {
            if (reachable[gateId]) cone[out++] = gateId;
        }
        return cone;
    }

    /**
     * Fastest source-edge path: set one compile-validated source and evaluate only its acyclic downstream cone once.
     * This is the preferred programmed-clock path and bypasses the event queue completely.
     */
    public long driveAndSettleAcyclicFast(int sourceSignalId, boolean high, int[] cone, long maxGateEvaluations) {
        if (cone == null) throw new IllegalArgumentException("acyclic cone is required");
        if (cone.length > maxGateEvaluations) throw new UnstableCircuitException(maxGateEvaluations);

        setSignalTurboNoSchedule(sourceSignalId, high ? HIGH : LOW);
        for (int index = 0; index < cone.length; index++) {
            int gateId = cone[index];
            byte a = values[gateInputA[gateId]];
            byte b = values[gateInputB[gateId]];
            byte next = (a == LOW || b == LOW) ? HIGH : (a == HIGH && b == HIGH ? LOW : UNKNOWN);
            setSignalTurboNoSchedule(gateOutput[gateId], next);
        }
        totalGateEvaluations += cone.length;
        return cone.length;
    }

    public void watchDirtyBit(int bitIndex, int[] signalIds) {
        if (bitIndex < 0 || bitIndex >= 64) throw new IllegalArgumentException("dirty bit must be 0..63");
        if (signalIds == null) throw new IllegalArgumentException("signal ids are required");
        long bit = 1L << bitIndex;
        for (int signalId : signalIds) {
            requireSignalId(signalId);
            dirtyWatchBitsBySignal[signalId] |= bit;
        }
        dirtyWatchEnabled = true;
    }

    public long consumeDirtyWatchBits() {
        long result = dirtyWatchBits;
        dirtyWatchBits = 0L;
        return result;
    }

    public long readUnsigned(Signal[] bus) {
        if (bus == null) throw new IllegalArgumentException("bus is required");
        long result = 0L;
        int bits = Math.min(64, bus.length);
        for (int bit = 0; bit < bits; bit++) {
            int signalId = requireSignalId(bus[bit]);
            byte value = values[signalId];
            if (value == UNKNOWN) throw new IllegalStateException("Port contains UNKNOWN at bit " + bit);
            if (value == HIGH) result |= (1L << bit);
        }
        return result;
    }

    public long readUnsigned(int[] signalIds) {
        if (signalIds == null) throw new IllegalArgumentException("signal ids are required");
        return readUnsignedFast(signalIds);
    }

    /**
     * MHz boundary reads are usually 64-bit buses. Process eight lanes per branch so valid LOW/HIGH data avoids the
     * old per-bit UNKNOWN/HIGH branch pair. The rare UNKNOWN slow path still reports the exact offending bit.
     */
    public long readUnsignedFast(int[] signalIds) {
        long result = 0L;
        int bits = Math.min(64, signalIds.length);
        int bit = 0;

        for (; bit + 8 <= bits; bit += 8) {
            int v0 = values[signalIds[bit]];
            int v1 = values[signalIds[bit + 1]];
            int v2 = values[signalIds[bit + 2]];
            int v3 = values[signalIds[bit + 3]];
            int v4 = values[signalIds[bit + 4]];
            int v5 = values[signalIds[bit + 5]];
            int v6 = values[signalIds[bit + 6]];
            int v7 = values[signalIds[bit + 7]];

            if (((v0 | v1 | v2 | v3 | v4 | v5 | v6 | v7) & UNKNOWN) != 0) {
                for (int lane = 0; lane < 8; lane++) {
                    if (values[signalIds[bit + lane]] == UNKNOWN) {
                        throw new IllegalStateException("Port contains UNKNOWN at bit " + (bit + lane));
                    }
                }
            }

            result |= (long) (v0 & 1) << bit;
            result |= (long) (v1 & 1) << (bit + 1);
            result |= (long) (v2 & 1) << (bit + 2);
            result |= (long) (v3 & 1) << (bit + 3);
            result |= (long) (v4 & 1) << (bit + 4);
            result |= (long) (v5 & 1) << (bit + 5);
            result |= (long) (v6 & 1) << (bit + 6);
            result |= (long) (v7 & 1) << (bit + 7);
        }

        for (; bit < bits; bit++) {
            byte value = values[signalIds[bit]];
            if (value == UNKNOWN) throw new IllegalStateException("Port contains UNKNOWN at bit " + bit);
            result |= (long) (value & 1) << bit;
        }
        return result;
    }

    public long runUntilStable(long maxGateEvaluations) {
        if (maxGateEvaluations <= 0L) throw new IllegalArgumentException("maxGateEvaluations must be > 0");
        return turboMode() ? runUntilStableFast(maxGateEvaluations) : runUntilStableDetailed(maxGateEvaluations);
    }

    public long runUntilStableFast(long maxGateEvaluations) {
        if (maxGateEvaluations <= 0L) throw new IllegalArgumentException("maxGateEvaluations must be > 0");
        if (topologicalGateOrder != null) {
            // Zero-gate device/RANDOM topologies are common in display stress tests. Nothing can be scheduled here.
            if (topologicalGateOrder.length == 0) return 0L;
            return runTopologicalFullPass(maxGateEvaluations);
        }
        return runEventTurbo(maxGateEvaluations);
    }

    private long runTopologicalFullPass(long maxGateEvaluations) {
        if (topologicalGateOrder.length > maxGateEvaluations) throw new UnstableCircuitException(maxGateEvaluations);
        clearScheduledQueue();
        for (int index = 0; index < topologicalGateOrder.length; index++) {
            int gateId = topologicalGateOrder[index];
            byte a = values[gateInputA[gateId]];
            byte b = values[gateInputB[gateId]];
            byte next = (a == LOW || b == LOW) ? HIGH : (a == HIGH && b == HIGH ? LOW : UNKNOWN);
            setSignalTurboNoSchedule(gateOutput[gateId], next);
        }
        totalGateEvaluations += topologicalGateOrder.length;
        return topologicalGateOrder.length;
    }

    private long runEventTurbo(long maxGateEvaluations) {
        long evaluations = 0L;
        while (queueSize > 0) {
            if (evaluations >= maxGateEvaluations) throw new UnstableCircuitException(maxGateEvaluations);

            int gateId = queue[queueHead];
            if (++queueHead == queue.length) queueHead = 0;
            queueSize--;
            queued[gateId] = false;
            evaluations++;

            byte a = values[gateInputA[gateId]];
            byte b = values[gateInputB[gateId]];
            byte next = (a == LOW || b == LOW) ? HIGH : (a == HIGH && b == HIGH ? LOW : UNKNOWN);
            int outputId = gateOutput[gateId];
            if (values[outputId] != next) updateSignalTurbo(outputId, next);
        }
        totalGateEvaluations += evaluations;
        return evaluations;
    }

    private long runUntilStableDetailed(long maxGateEvaluations) {
        long evaluations = 0L;
        while (queueSize > 0) {
            if (evaluations >= maxGateEvaluations) throw new UnstableCircuitException(maxGateEvaluations);

            int gateId = queue[queueHead];
            if (++queueHead == queue.length) queueHead = 0;
            queueSize--;
            queued[gateId] = false;
            evaluations++;

            byte a = values[gateInputA[gateId]];
            byte b = values[gateInputB[gateId]];
            byte next = (a == LOW || b == LOW) ? HIGH : (a == HIGH && b == HIGH ? LOW : UNKNOWN);
            updateSignalDetailed(gateOutput[gateId], next);
        }
        totalGateEvaluations += evaluations;
        return evaluations;
    }

    public long totalGateEvaluations() { return totalGateEvaluations; }
    public long transitionSequence() { return transitionSequence; }

    private boolean updateSignalTurbo(int signalId, byte next) {
        if (topologicalGateOrder != null) return setSignalTurboNoSchedule(signalId, next);
        if (values[signalId] == next) return false;
        values[signalId] = next;
        if (dirtyWatchEnabled) dirtyWatchBits |= dirtyWatchBitsBySignal[signalId];

        int start = consumerOffsets[signalId];
        int end = consumerOffsets[signalId + 1];
        for (int index = start; index < end; index++) schedule(consumerGateIds[index]);
        return true;
    }

    private boolean setSignalTurboNoSchedule(int signalId, byte next) {
        if (values[signalId] == next) return false;
        values[signalId] = next;
        if (dirtyWatchEnabled) dirtyWatchBits |= dirtyWatchBitsBySignal[signalId];
        return true;
    }

    private boolean updateSignalDetailed(int signalId, byte next) {
        byte previous = values[signalId];
        if (previous == next) return false;

        values[signalId] = next;
        transitionSequence++;
        if (dirtyWatchEnabled) dirtyWatchBits |= dirtyWatchBitsBySignal[signalId];
        if (mirrorSignalObjects) signals[signalId].setValue(decode(next));

        if (traceRecorder != null) {
            Signal signal = signals[signalId];
            traceRecorder.record(new TraceEvent(
                    transitionSequence,
                    signal.id(),
                    signal.path(),
                    decode(previous),
                    decode(next)
            ));
        }

        int start = consumerOffsets[signalId];
        int end = consumerOffsets[signalId + 1];
        for (int index = start; index < end; index++) schedule(consumerGateIds[index]);
        return true;
    }

    private int[] compileTopologicalOrder() {
        int gateCount = gateInputA.length;
        if (gateCount == 0) return new int[0];

        int[] producerBySignal = new int[signals.length];
        Arrays.fill(producerBySignal, -1);
        for (int gateId = 0; gateId < gateCount; gateId++) {
            int outputSignal = gateOutput[gateId];
            if (producerBySignal[outputSignal] != -1) return null;
            producerBySignal[outputSignal] = gateId;
        }

        int[] indegree = new int[gateCount];
        for (int gateId = 0; gateId < gateCount; gateId++) {
            int producerA = producerBySignal[gateInputA[gateId]];
            int producerB = producerBySignal[gateInputB[gateId]];
            if (producerA >= 0) indegree[gateId]++;
            if (producerB >= 0 && producerB != producerA) indegree[gateId]++;
        }

        int[] ready = new int[gateCount];
        int readyHead = 0;
        int readyTail = 0;
        for (int gateId = 0; gateId < gateCount; gateId++) {
            if (indegree[gateId] == 0) ready[readyTail++] = gateId;
        }

        int[] order = new int[gateCount];
        int orderSize = 0;
        while (readyHead < readyTail) {
            int gateId = ready[readyHead++];
            order[orderSize++] = gateId;
            int outputSignal = gateOutput[gateId];
            int start = consumerOffsets[outputSignal];
            int end = consumerOffsets[outputSignal + 1];
            for (int index = start; index < end; index++) {
                int consumer = consumerGateIds[index];
                if (--indegree[consumer] == 0) ready[readyTail++] = consumer;
            }
        }

        return orderSize == gateCount ? order : null;
    }

    private void schedule(int gateId) {
        if (queued[gateId]) return;
        queued[gateId] = true;
        queue[queueTail] = gateId;
        if (++queueTail == queue.length) queueTail = 0;
        queueSize++;
    }

    private void clearScheduledQueue() {
        while (queueSize > 0) {
            int gateId = queue[queueHead];
            if (++queueHead == queue.length) queueHead = 0;
            queueSize--;
            queued[gateId] = false;
        }
        queueHead = 0;
        queueTail = 0;
    }

    private int requireSignalId(Signal signal) {
        int signalId = signal.id();
        if (signalId < 0 || signalId >= signals.length || signals[signalId] != signal) {
            throw new IllegalArgumentException("Signal does not belong to this simulator");
        }
        return signalId;
    }

    private void requireSignalId(int signalId) {
        if (signalId < 0 || signalId >= signals.length) {
            throw new IllegalArgumentException("Signal id does not belong to this simulator: " + signalId);
        }
    }

    private static byte encode(LogicValue value) {
        return switch (value) {
            case LOW -> LOW;
            case HIGH -> HIGH;
            case UNKNOWN -> UNKNOWN;
        };
    }

    private static LogicValue decode(byte value) {
        return switch (value) {
            case LOW -> LogicValue.LOW;
            case HIGH -> LogicValue.HIGH;
            default -> LogicValue.UNKNOWN;
        };
    }
}
