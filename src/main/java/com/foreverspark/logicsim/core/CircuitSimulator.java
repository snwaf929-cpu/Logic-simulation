package com.foreverspark.logicsim.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Event-driven NAND simulator with a compiled primitive hot path.
 *
 * Editable/debug circuits can keep Signal objects mirrored for inspection. Programmed physical circuits switch to
 * turbo mode: primitive byte state is authoritative and the MHz worker no longer writes editor Signal objects on
 * every transition. Fanout is stored as flat CSR arrays for better cache locality than one int[] object per signal.
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
    /** Optional 64-bit dirty-watch fanout for compiled boundary ports. */
    private final long[] dirtyWatchBitsBySignal;

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

        this.queued = new boolean[gates.length];
        this.queue = new int[Math.max(1, gates.length)];
    }

    public CircuitSimulator(LogicCircuit circuit) {
        this(circuit, null);
    }

    /**
     * Physical programmed circuits call this after compile/initialization. Primitive state remains authoritative;
     * editor/debug Signal objects are no longer dirtied millions of times per second.
     */
    public void enableTurboMode() {
        if (traceRecorder != null) return; // tracing requires object-level transition metadata
        mirrorSignalObjects = false;
    }

    public boolean turboMode() {
        return !mirrorSignalObjects;
    }

    public void scheduleAll() {
        for (int gateId = 0; gateId < gateInputA.length; gateId++) schedule(gateId);
    }

    public boolean drive(Signal signal, LogicValue value) {
        if (signal == null || value == null) throw new IllegalArgumentException("signal and value are required");
        return updateSignal(requireSignalId(signal), encode(value));
    }

    /** Fast 1-bit source drive used by virtual clocks/random devices. */
    public boolean driveLevel(Signal signal, boolean high) {
        if (signal == null) throw new IllegalArgumentException("signal is required");
        return updateSignal(requireSignalId(signal), high ? HIGH : LOW);
    }

    /** Fast 1-bit source drive when the signal id was cached at compile time. */
    public boolean driveLevel(int signalId, boolean high) {
        requireSignalId(signalId);
        return updateSignal(signalId, high ? HIGH : LOW);
    }

    public boolean isHigh(Signal signal) {
        return values[requireSignalId(signal)] == HIGH;
    }

    public boolean isHigh(int signalId) {
        requireSignalId(signalId);
        return values[signalId] == HIGH;
    }

    public LogicValue read(Signal signal) {
        return decode(values[requireSignalId(signal)]);
    }

    public LogicValue read(int signalId) {
        requireSignalId(signalId);
        return decode(values[signalId]);
    }

    /** Finds a compiled signal by its stable hierarchy path in O(1). */
    public Signal signalByPath(String path) {
        return path == null ? null : signalsByPath.get(path);
    }

    /** Converts stable Signal handles into primitive ids once at compile time. */
    public int[] signalIds(Signal[] bus) {
        if (bus == null) throw new IllegalArgumentException("bus is required");
        int[] ids = new int[bus.length];
        for (int bit = 0; bit < bus.length; bit++) ids[bit] = requireSignalId(bus[bit]);
        return ids;
    }

    /**
     * Marks a compiled bus as one dirty-watch bit. Up to 64 physical output ports can therefore be tracked with one
     * machine word and consumed after each settled edge without re-reading untouched buses.
     */
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

    /** Returns and clears output dirtiness accumulated during the previous propagation. */
    public long consumeDirtyWatchBits() {
        long result = dirtyWatchBits;
        dirtyWatchBits = 0L;
        return result;
    }

    /**
     * Packs an already-compiled bus directly from the simulator's primitive value array.
     * Signal membership checks are appropriate for editor/debug callers that have not cached primitive ids.
     */
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

    /** MHz boundary read: signal ids were validated once during compile, so there are no per-bit object checks. */
    public long readUnsigned(int[] signalIds) {
        if (signalIds == null) throw new IllegalArgumentException("signal ids are required");
        long result = 0L;
        int bits = Math.min(64, signalIds.length);
        for (int bit = 0; bit < bits; bit++) {
            byte value = values[signalIds[bit]];
            if (value == UNKNOWN) throw new IllegalStateException("Port contains UNKNOWN at bit " + bit);
            if (value == HIGH) result |= (1L << bit);
        }
        return result;
    }

    public long runUntilStable(long maxGateEvaluations) {
        if (maxGateEvaluations <= 0) {
            throw new IllegalArgumentException("maxGateEvaluations must be > 0");
        }

        long evaluations = 0L;
        while (queueSize > 0) {
            if (evaluations >= maxGateEvaluations) throw new UnstableCircuitException(maxGateEvaluations);

            int gateId = queue[queueHead];
            queueHead++;
            if (queueHead == queue.length) queueHead = 0;
            queueSize--;
            queued[gateId] = false;
            evaluations++;

            byte a = values[gateInputA[gateId]];
            byte b = values[gateInputB[gateId]];
            byte next = (a == LOW || b == LOW)
                    ? HIGH
                    : (a == HIGH && b == HIGH ? LOW : UNKNOWN);
            updateSignal(gateOutput[gateId], next);
        }
        totalGateEvaluations += evaluations;
        return evaluations;
    }

    public long totalGateEvaluations() {
        return totalGateEvaluations;
    }

    public long transitionSequence() {
        return transitionSequence;
    }

    private boolean updateSignal(int signalId, byte next) {
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

    private void schedule(int gateId) {
        if (queued[gateId]) return;
        queued[gateId] = true;
        queue[queueTail] = gateId;
        queueTail++;
        if (queueTail == queue.length) queueTail = 0;
        queueSize++;
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
