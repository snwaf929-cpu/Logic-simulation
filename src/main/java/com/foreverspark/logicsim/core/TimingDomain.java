package com.foreverspark.logicsim.core;

public final class TimingDomain {
    public static final long MIN_FREQUENCY_HZ = 1L;
    public static final long MAX_FREQUENCY_HZ = 1_000_000_000L;

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
}
