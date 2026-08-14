package com.foreverspark.logicsim.core;

/** Connects one virtual timing domain to one 1-bit simulator signal. */
public final class TimingSignalDriver {
    private final TimingDomain timing;
    private final CircuitSimulator simulator;
    private final Signal signal;
    private final int signalId;
    private final int[] acyclicCone;
    private final long settleBudget;
    private long lastPulseRisingEdges;

    public TimingSignalDriver(long frequencyHz, CircuitSimulator simulator, Signal signal, long settleBudget) {
        if (simulator == null || signal == null) throw new IllegalArgumentException("simulator and signal are required");
        if (settleBudget <= 0L) throw new IllegalArgumentException("settleBudget must be > 0");
        this.timing = new TimingDomain(frequencyHz);
        this.simulator = simulator;
        this.signal = signal;
        this.signalId = signal.id();
        this.acyclicCone = simulator.compileAcyclicCone(signalId);
        this.settleBudget = settleBudget;
        simulator.driveLevel(signalId, false);
        simulator.runUntilStable(settleBudget);
    }

    public TimingDomain timing() { return timing; }
    public Signal signal() { return signal; }
    public int signalId() { return signalId; }
    public boolean queueFreeTurboAvailable() { return acyclicCone != null; }
    public int compiledConeGateCount() { return acyclicCone == null ? -1 : acyclicCone.length; }
    public long lastPulseRisingEdges() { return lastPulseRisingEdges; }

    public long advanceNanos(long elapsedNanos, long edgeBudget) {
        return advanceNanos(elapsedNanos, edgeBudget, null);
    }

    /**
     * Physical acyclic circuits take the queue-free compiled cone path. Feedback circuits retain the primitive event
     * engine. The choice is made once per edge batch, not rediscovered in the NAND inner loop.
     */
    public long advanceNanos(long elapsedNanos, long edgeBudget, Runnable afterSettledEdge) {
        timing.queueElapsedNanos(elapsedNanos);
        long executable = timing.executableEdges(edgeBudget);
        if (executable <= 0L) return 0L;

        boolean level = timing.high();
        boolean turbo = simulator.turboMode();
        long completed = 0L;
        try {
            if (turbo && acyclicCone != null) {
                while (completed < executable) {
                    level = !level;
                    simulator.driveAndSettleAcyclicFast(signalId, level, acyclicCone, settleBudget);
                    completed++;
                    if (afterSettledEdge != null) afterSettledEdge.run();
                }
            } else if (turbo) {
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

    /**
     * Ultra-fast clock accounting for a source whose falling level is unobservable by NANDs/external outputs.
     * Instead of iterating H/L for every edge, consume the whole edge batch arithmetically and report how many
     * LOW->HIGH transitions occurred. The caller executes only those useful rising-edge device operations.
     *
     * This path is deliberately narrow: CircuitTimingController enables it only for a zero-gate clock cone with no
     * clock ENABLE input and no boundary dirty-watch on the clock signal. Arbitrary circuits keep exact edge stepping.
     */
    public long advanceNanosPulseBatch(long elapsedNanos, long edgeBudget) {
        timing.queueElapsedNanos(elapsedNanos);
        long executable = timing.executableEdges(edgeBudget);
        if (executable <= 0L) {
            lastPulseRisingEdges = 0L;
            return 0L;
        }

        boolean startedHigh = timing.high();
        // Starting LOW: H,L,H,L... => ceil(E/2) rising edges. Starting HIGH: L,H,L,H... => floor(E/2).
        long rising = startedHigh ? (executable >>> 1) : ((executable + 1L) >>> 1);
        boolean finalHigh = ((executable & 1L) == 0L) ? startedHigh : !startedHigh;

        // The source has no NAND/boundary observer on this specialized path, so intermediate H/L writes are useless.
        // Keep the primitive signal coherent with the final timing-domain level using at most one write per batch.
        simulator.driveLevelFast(signalId, finalHigh);
        timing.commitExecutedEdges(executable);
        lastPulseRisingEdges = rising;
        return executable;
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
            if (turbo && acyclicCone != null) {
                while (completed < edges) {
                    level = !level;
                    simulator.driveAndSettleAcyclicFast(signalId, level, acyclicCone, settleBudget);
                    completed++;
                    if (afterSettledEdge != null) afterSettledEdge.run();
                }
            } else if (turbo) {
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
