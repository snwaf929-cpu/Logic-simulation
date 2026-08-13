package com.foreverspark.logicsim.core;

/** Connects one virtual timing domain to one 1-bit simulator signal. */
public final class TimingSignalDriver {
    private final TimingDomain timing;
    private final CircuitSimulator simulator;
    private final Signal signal;
    private final long settleBudget;

    public TimingSignalDriver(long frequencyHz, CircuitSimulator simulator, Signal signal, long settleBudget) {
        if (simulator == null || signal == null) throw new IllegalArgumentException("simulator and signal are required");
        if (settleBudget <= 0L) throw new IllegalArgumentException("settleBudget must be > 0");
        this.timing = new TimingDomain(frequencyHz);
        this.simulator = simulator;
        this.signal = signal;
        this.settleBudget = settleBudget;
        simulator.drive(signal, LogicValue.LOW);
        simulator.runUntilStable(settleBudget);
    }

    public TimingDomain timing() { return timing; }
    public Signal signal() { return signal; }

    public long advanceNanos(long elapsedNanos, long edgeBudget) {
        return advanceNanos(elapsedNanos, edgeBudget, () -> {});
    }

    public long advanceNanos(long elapsedNanos, long edgeBudget, Runnable afterSettledEdge) {
        Runnable callback = afterSettledEdge == null ? () -> {} : afterSettledEdge;
        return timing.advanceNanos(elapsedNanos, edgeBudget, high -> {
            driveLevel(high);
            callback.run();
        });
    }

    public long stepEdges(long edges) {
        return stepEdges(edges, () -> {});
    }

    public long stepEdges(long edges, Runnable afterSettledEdge) {
        Runnable callback = afterSettledEdge == null ? () -> {} : afterSettledEdge;
        return timing.stepEdges(edges, high -> {
            driveLevel(high);
            callback.run();
        });
    }

    private void driveLevel(boolean high) {
        simulator.drive(signal, LogicValue.fromBoolean(high));
        simulator.runUntilStable(settleBudget);
    }
}
