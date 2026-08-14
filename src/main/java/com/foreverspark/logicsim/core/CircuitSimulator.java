package com.foreverspark.logicsim.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Event-driven NAND simulator with a compiled primitive hot path.
 *
 * The editable circuit remains object based, but runtime propagation is flattened once into primitive arrays:
 * gate inputs/outputs, signal values, downstream consumer ids, queue ids, and queued flags. This avoids the
 * ArrayDeque + IdentityHashMap + iterator/wrapper overhead that becomes dominant at MHz clock rates.
 */
public final class CircuitSimulator {
    private static final byte LOW = 0;
    private static final byte HIGH = 1;
    private static final byte UNKNOWN = 2;

    private final LogicCircuit circuit;
    private final TraceRecorder traceRecorder;

    private final Signal[] signals;
    private final NandGate[] gates;
    private final byte[] values;
    private final int[] gateInputA;
    private final int[] gateInputB;
    private final int[] gateOutput;
    private final int[][] consumers;
    private final boolean[] queued;
    private final int[] queue;
    private final Map<String, Signal> signalsByPath;

    private int queueHead;
    private int queueTail;
    private int queueSize;
    private long transitionSequence;
    private long totalGateEvaluations;

    public CircuitSimulator(LogicCircuit circuit, TraceRecorder traceRecorder) {
        if (circuit == null) throw new IllegalArgumentException("circuit is required");
        this.circuit = circuit;
        this.traceRecorder = traceRecorder;

        this.signals = circuit.signals().toArray(Signal[]::new);
        this.gates = circuit.gates().toArray(NandGate[]::new);
        this.values = new byte[signals.length];
        this.consumers = new int[signals.length][];
        this.signalsByPath = new HashMap<>(Math.max(16, signals.length * 2));

        for (int signalId = 0; signalId < signals.length; signalId++) {
            Signal signal = signals[signalId];
            values[signalId] = encode(signal.value());
            signalsByPath.put(signal.path(), signal);
            List<NandGate> downstream = signal.consumers();
            int[] ids = new int[downstream.size()];
            for (int index = 0; index < ids.length; index++) ids[index] = downstream.get(index).id();
            consumers[signalId] = ids;
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

    public void scheduleAll() {
        for (int gateId = 0; gateId < gates.length; gateId++) schedule(gateId);
    }

    public boolean drive(Signal signal, LogicValue value) {
        if (signal == null || value == null) throw new IllegalArgumentException("signal and value are required");
        int signalId = signal.id();
        if (signalId < 0 || signalId >= signals.length || signals[signalId] != signal) {
            throw new IllegalArgumentException("Signal does not belong to this simulator");
        }
        return updateSignal(signalId, encode(value));
    }

    /** Finds a compiled signal by its stable hierarchy path in O(1). */
    public Signal signalByPath(String path) {
        return path == null ? null : signalsByPath.get(path);
    }

    public long runUntilStable(long maxGateEvaluations) {
        if (maxGateEvaluations <= 0) {
            throw new IllegalArgumentException("maxGateEvaluations must be > 0");
        }

        long evaluations = 0L;
        while (queueSize > 0) {
            if (evaluations >= maxGateEvaluations) throw new UnstableCircuitException(maxGateEvaluations);

            int gateId = pollGate();
            queued[gateId] = false;
            evaluations++;
            totalGateEvaluations++;

            byte a = values[gateInputA[gateId]];
            byte b = values[gateInputB[gateId]];
            byte next = nand(a, b);
            updateSignal(gateOutput[gateId], next);
        }
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
        Signal signal = signals[signalId];
        signal.setValue(decode(next));
        transitionSequence++;

        if (traceRecorder != null) {
            traceRecorder.record(new TraceEvent(
                    transitionSequence,
                    signal.id(),
                    signal.path(),
                    decode(previous),
                    decode(next)
            ));
        }

        int[] downstream = consumers[signalId];
        for (int index = 0; index < downstream.length; index++) schedule(downstream[index]);
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

    private int pollGate() {
        int gateId = queue[queueHead];
        queueHead++;
        if (queueHead == queue.length) queueHead = 0;
        queueSize--;
        return gateId;
    }

    private static byte nand(byte a, byte b) {
        if (a == LOW || b == LOW) return HIGH;
        if (a == HIGH && b == HIGH) return LOW;
        return UNKNOWN;
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
