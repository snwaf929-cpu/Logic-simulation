package com.foreverspark.logicsim.core;

public final class TimingDomain {
    public static final long MIN_FREQUENCY_HZ = 1L;
    public static final long MAX_FREQUENCY_HZ = 1_000_000_000L;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    @FunctionalInterface
    public interface EdgeSink {
        void onLevel(boolean high);
    }

    private long frequencyHz;
    private long fractionalEdgeNumerator;
    private long pendingEdges;
    private boolean high;
    private boolean running = true;
    private long totalEdges;

    public TimingDomain(long frequencyHz) {
        setFrequencyHz(frequencyHz);
    }

    public long frequencyHz() { return frequencyHz; }
    public boolean high() { return high; }
    public boolean running() { return running; }
    public long pendingEdges() { return pendingEdges; }
    public long totalEdges() { return totalEdges; }

    public void setFrequencyHz(long frequencyHz) {
        if (frequencyHz < MIN_FREQUENCY_HZ || frequencyHz > MAX_FREQUENCY_HZ) {
            throw new IllegalArgumentException("Frequency must be between 1 Hz and 1 GHz");
        }
        this.frequencyHz = frequencyHz;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public void reset(boolean high) {
        this.high = high;
        fractionalEdgeNumerator = 0L;
        pendingEdges = 0L;
        totalEdges = 0L;
    }

    public long advanceNanos(long elapsedNanos, long edgeBudget, EdgeSink sink) {
        if (elapsedNanos < 0L) throw new IllegalArgumentException("elapsedNanos must be >= 0");
        if (edgeBudget < 0L) throw new IllegalArgumentException("edgeBudget must be >= 0");
        if (sink == null) throw new IllegalArgumentException("edge sink is required");
        if (running && elapsedNanos > 0L) queueElapsedTime(elapsedNanos);
        long emitted = Math.min(pendingEdges, edgeBudget);
        for (long i = 0; i < emitted; i++) emitEdge(sink);
        pendingEdges -= emitted;
        return emitted;
    }

    public long stepEdges(long edges, EdgeSink sink) {
        if (edges < 0L) throw new IllegalArgumentException("edges must be >= 0");
        if (sink == null) throw new IllegalArgumentException("edge sink is required");
        for (long i = 0; i < edges; i++) emitEdge(sink);
        return edges;
    }

    private void emitEdge(EdgeSink sink) {
        high = !high;
        totalEdges++;
        sink.onLevel(high);
    }

    private void queueElapsedTime(long elapsedNanos) {
    }
}
