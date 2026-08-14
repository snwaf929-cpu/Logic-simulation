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

    /** Queue wall time without emitting callbacks. Used by the compiled TimingSignalDriver fast path. */
    public void queueElapsedNanos(long elapsedNanos) {
        if (elapsedNanos < 0L) throw new IllegalArgumentException("elapsedNanos must be >= 0");
        if (running && elapsedNanos > 0L) queueElapsedTime(elapsedNanos);
    }

    /** Number of queued edges that may be executed now; does not mutate state until commitExecutedEdges(). */
    public long executableEdges(long edgeBudget) {
        if (edgeBudget < 0L) throw new IllegalArgumentException("edgeBudget must be >= 0");
        return Math.min(pendingEdges, edgeBudget);
    }

    /** Commit a successfully executed prefix of pending edges in O(1). */
    public void commitExecutedEdges(long edges) {
        if (edges < 0L || edges > pendingEdges) throw new IllegalArgumentException("Invalid executed edge count");
        pendingEdges -= edges;
        commitLevelAndCount(edges);
    }

    /** Commit manually stepped edges; they are not taken from pending wall-time work. */
    public void commitSteppedEdges(long edges) {
        if (edges < 0L) throw new IllegalArgumentException("edges must be >= 0");
        commitLevelAndCount(edges);
    }

    public long advanceNanos(long elapsedNanos, long edgeBudget, EdgeSink sink) {
        if (elapsedNanos < 0L) throw new IllegalArgumentException("elapsedNanos must be >= 0");
        if (edgeBudget < 0L) throw new IllegalArgumentException("edgeBudget must be >= 0");
        if (sink == null) throw new IllegalArgumentException("edge sink is required");
        queueElapsedNanos(elapsedNanos);
        long emitted = executableEdges(edgeBudget);
        boolean level = high;
        for (long i = 0; i < emitted; i++) {
            level = !level;
            sink.onLevel(level);
        }
        commitExecutedEdges(emitted);
        return emitted;
    }

    public long stepEdges(long edges, EdgeSink sink) {
        if (edges < 0L) throw new IllegalArgumentException("edges must be >= 0");
        if (sink == null) throw new IllegalArgumentException("edge sink is required");
        boolean level = high;
        for (long i = 0; i < edges; i++) {
            level = !level;
            sink.onLevel(level);
        }
        commitSteppedEdges(edges);
        return edges;
    }

    private void commitLevelAndCount(long edges) {
        if ((edges & 1L) != 0L) high = !high;
        totalEdges = saturatingAdd(totalEdges, edges);
    }

    private void queueElapsedTime(long elapsedNanos) {
        long edgesPerSecond = frequencyHz * 2L;
        long wholeSeconds = elapsedNanos / NANOS_PER_SECOND;
        long remainingNanos = elapsedNanos % NANOS_PER_SECOND;
        pendingEdges = saturatingAdd(pendingEdges, saturatingMultiply(wholeSeconds, edgesPerSecond));
        long numerator = fractionalEdgeNumerator + remainingNanos * edgesPerSecond;
        pendingEdges = saturatingAdd(pendingEdges, numerator / NANOS_PER_SECOND);
        fractionalEdgeNumerator = numerator % NANOS_PER_SECOND;
    }

    private static long saturatingAdd(long a, long b) {
        if (b > 0L && a > Long.MAX_VALUE - b) return Long.MAX_VALUE;
        return a + b;
    }

    private static long saturatingMultiply(long a, long b) {
        if (a == 0L || b == 0L) return 0L;
        if (a > Long.MAX_VALUE / b) return Long.MAX_VALUE;
        return a * b;
    }
}
