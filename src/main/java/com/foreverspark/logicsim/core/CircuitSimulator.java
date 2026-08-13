package com.foreverspark.logicsim.core;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Event-driven accurate simulator. Only gates downstream of changed signals are evaluated.
 * This implementation favors correctness and clarity; Turbo mode will use a compiled layout.
 */
public final class CircuitSimulator {
    private final LogicCircuit circuit;
    private final TraceRecorder traceRecorder;
    private final ArrayDeque<NandGate> queue = new ArrayDeque<>();
    private final Set<NandGate> queued = Collections.newSetFromMap(new IdentityHashMap<>());

    private long transitionSequence;
    private long totalGateEvaluations;

    public CircuitSimulator(LogicCircuit circuit, TraceRecorder traceRecorder) {
        this.circuit = circuit;
        this.traceRecorder = traceRecorder;
    }

    public CircuitSimulator(LogicCircuit circuit) {
        this(circuit, null);
    }

    public void scheduleAll() {
        for (NandGate gate : circuit.gates()) {
            schedule(gate);
        }
    }

    public boolean drive(Signal signal, LogicValue value) {
        return updateSignal(signal, value);
    }

    /** Finds a compiled signal by its stable hierarchy path. */
    public Signal signalByPath(String path) {
        if (path == null) return null;
        for (Signal signal : circuit.signals()) {
            if (path.equals(signal.path())) return signal;
        }
        return null;
    }

    public long runUntilStable(long maxGateEvaluations) {
        if (maxGateEvaluations <= 0) {
            throw new IllegalArgumentException("maxGateEvaluations must be > 0");
        }

        long evaluations = 0;
        while (!queue.isEmpty()) {
            if (evaluations >= maxGateEvaluations) {
                throw new UnstableCircuitException(maxGateEvaluations);
            }

            NandGate gate = queue.removeFirst();
            queued.remove(gate);
            evaluations++;
            totalGateEvaluations++;

            LogicValue next = gate.evaluate();
            updateSignal(gate.output(), next);
        }
        return evaluations;
    }

    public long totalGateEvaluations() {
        return totalGateEvaluations;
    }

    public long transitionSequence() {
        return transitionSequence;
    }

    private boolean updateSignal(Signal signal, LogicValue next) {
        LogicValue previous = signal.value();
        if (previous == next) {
            return false;
        }

        signal.setValue(next);
        transitionSequence++;
        if (traceRecorder != null) {
            traceRecorder.record(new TraceEvent(
                    transitionSequence,
                    signal.id(),
                    signal.path(),
                    previous,
                    next
            ));
        }

        for (NandGate consumer : signal.consumers()) {
            schedule(consumer);
        }
        return true;
    }

    private void schedule(NandGate gate) {
        if (queued.add(gate)) {
            queue.addLast(gate);
        }
    }
}
