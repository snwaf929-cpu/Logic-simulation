package com.foreverspark.logicsim.core;

/** Connects one virtual timing domain to one 1-bit simulator signal. */
public final class TimingSignalDriver {
    private final TimingDomain timing;
    private final CircuitSimulator simulator;
    private final Signal signal;
    private final int signalId;
    private final long settleBudget;

    public TimingSignalDriver(long frequencyHz, CircuitSimulator simulator, Signal signal, long settleBudget) {
        if (simulator == null || signal == null) throw new IllegalArgumentException("simulator and signal are required");
        if (settleBudget <= 0L) throw new IllegalArgumentException("settleBudget must be > 0");
        this.timing = new TimingDomain(frequencyHz);
        this.simulator = simulator;
        this.signal = signal;
        this.signalId = signal.id();
        this.settleBudget = settleBudget;
        simulator.driveLevel(signalId, false);
        simulator.runUntilStable(settleBudget);
    }

    public TimingDomain timing() { return timing; }
    public Signal signal() { return signal; }

    public long advanceNanos(long elapsedNanos, long edgeBudget) {
        return advanceNanos(elapsedNanos, edgeBudget, null);
    }

    /**
     * Compiled clock hot path. Physical turbo mode uses only compile-validated primitive ids and the direct turbo
     * NAND settle loop; no per-edge simulator mode dispatch, Signal object write, or tracing branch remains here.
     */
    public long advanceNanos(long elapsedNanos, long edgeBudget, Runnable afterSettledEdge) {
        timing.queueElapsedNanos(elapsedNanos);
        long executable = timing.executableEdges(edgeBudget);
        if (executable <= 0L) return 0L;

        boolean level = timing.high();
        boolean turbo = simulator.turboMode();
        long completed = 0L;
        try {
            if (turbo) {
                while (completed < executable) {
                    level = !level;
                    simulator.driveLevelFast(signalId, level);
                    simulator.runUntilStableFast(settleBudget);
                    completed++;
                    if (afterSettledEdge != null) afterSettledEdge.run();
                }
            } else {
                while (completed < executable) {
                    level = !level;
                    simulator.driveLevel(signalId, level);
                    simulator.runUntilStable(settleBudget);
                    completed++;
                    if (afterSettledEdge != null) afterSettledEdge.run();
                }
            }
        } finally {
            timing.commitExecutedEdges(completed);
        }
        return completed;
    }

    public long stepEdges(long edges) {
        return stepEdges(edges, null);
    }

    public long stepEdges(long edges, Runnable afterSettledEdge) {
        if (edges < 0L) throw new IllegalArgumentException("edges must be >= 0");
        if (edges == 0L) return 0L;

        boolean level = timing.high();
        boolean turbo = simulator.turboMode();
        long completed = 0L;
        try {
            if (turbo) {
                while (completed < edges) {
                    level = !level;
                    simulator.driveLevelFast(signalId, level);
                    simulator.runUntilStableFast(settleBudget);
                    completed++;
                    if (afterSettledEdge != null) afterSettledEdge.run();
                }
            } else {
                while (completed < edges) {
                    level = !level;
                    simulator.driveLevel(signalId, level);
                    simulator.runUntilStable(settleBudget);
                    completed++;
                    if (afterSettledEdge != null) afterSettledEdge.run();
                }
            }
        } finally {
            timing.commitSteppedEdges(completed);
        }
        return completed;
    }
}
