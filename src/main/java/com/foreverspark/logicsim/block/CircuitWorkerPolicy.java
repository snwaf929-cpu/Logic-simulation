package com.foreverspark.logicsim.block;

/** Shared policy for configurable per-Circuit-Block simulation parallelism. */
public final class CircuitWorkerPolicy {
    /** AUTO consumes as much of the global simulation pool as is currently available. */
    public static final int AUTO = 0;
    /** Existing/small boards stay conservative until the player opts into more parallelism. */
    public static final int DEFAULT = 1;
    /** Persistence guard only; the live hardware-specific ceiling is normally much smaller. */
    public static final int PERSISTED_MAX = 64;

    private CircuitWorkerPolicy() {}

    /** Simulation compute threads are capped at 25% of the JVM-visible logical processors. */
    public static int systemMaximum(int logicalProcessors) {
        return Math.max(1, Math.max(1, logicalProcessors) / 4);
    }

    /** Store AUTO as 0; explicit values are bounded independently of the current machine. */
    public static int normalizePersisted(int requested) {
        if (requested <= AUTO) return AUTO;
        return Math.max(1, Math.min(PERSISTED_MAX, requested));
    }

    /** Resolve a persisted request against the actual simulation pool on this machine. */
    public static int resolve(int requested, int systemMaximum) {
        int max = Math.max(1, systemMaximum);
        int normalized = normalizePersisted(requested);
        return normalized == AUTO ? max : Math.max(1, Math.min(max, normalized));
    }

    public static String label(int requested, int systemMaximum) {
        int normalized = normalizePersisted(requested);
        return normalized == AUTO ? "AUTO (up to " + Math.max(1, systemMaximum) + ")" : Integer.toString(resolve(normalized, systemMaximum));
    }
}
