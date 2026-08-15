package com.foreverspark.logicsim.client.screen;

import net.minecraft.core.BlockPos;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Integrated-world CLOCK telemetry shared between the authoritative Circuit Block worker and the editor UI.
 *
 * <p>This intentionally does not use {@link EditorClockRuntime}: the editor preview is budget-capped and therefore
 * cannot answer whether the physical Circuit Block is sustaining its configured MHz. In single-player the integrated
 * server and client live in the same JVM, so immutable snapshots can be published without packets or touching the
 * MHz hot loop. Dedicated-server clients simply have no snapshot and the tooltip reports telemetry unavailable.</p>
 */
public final class ClockRuntimeTelemetry {
    private static final long STALE_NANOS = 3_000_000_000L;
    private static final ConcurrentHashMap<Long, Snapshot> BY_BLOCK = new ConcurrentHashMap<>();

    private ClockRuntimeTelemetry() {}

    public static void publish(
            BlockPos pos,
            long targetHz,
            long actualHz,
            long pendingEdges,
            long workerBusyNanos,
            long sampleWindowNanos
    ) {
        if (pos == null || sampleWindowNanos <= 0L) return;
        double busy = workerBusyNanos <= 0L
                ? 0.0
                : Math.max(0.0, Math.min(1.0, workerBusyNanos / (double) sampleWindowNanos));
        BY_BLOCK.put(pos.asLong(), new Snapshot(
                Math.max(0L, targetHz),
                Math.max(0L, actualHz),
                Math.max(0L, pendingEdges),
                busy,
                System.nanoTime()
        ));
    }

    public static Snapshot snapshot(BlockPos pos) {
        return pos == null ? null : BY_BLOCK.get(pos.asLong());
    }

    public static void remove(BlockPos pos) {
        if (pos != null) BY_BLOCK.remove(pos.asLong());
    }

    public static void clear() {
        BY_BLOCK.clear();
    }

    public record Snapshot(
            long targetHz,
            long actualHz,
            long pendingEdges,
            double workerBusyFraction,
            long sampledAtNanos
    ) {
        public boolean stale() {
            return System.nanoTime() - sampledAtNanos > STALE_NANOS;
        }

        public double accuracyPercent() {
            if (targetHz <= 0L) return 0.0;
            return actualHz * 100.0 / targetHz;
        }

        /** Backlog expressed as virtual clock time rather than opaque edge count. */
        public double debtMillis() {
            if (targetHz <= 0L || pendingEdges <= 0L) return 0.0;
            return pendingEdges * 500.0 / targetHz; // edges / (2*Hz) * 1000 ms
        }

        public double workerBusyPercent() {
            return workerBusyFraction * 100.0;
        }

        public double workerTimeHeadroomPercent() {
            return Math.max(0.0, (1.0 - workerBusyFraction) * 100.0);
        }

        public Health health() {
            if (stale()) return Health.STALE;
            if (targetHz <= 0L) return Health.STOPPED;

            double accuracy = accuracyPercent();
            double debtMs = debtMillis();
            double busy = workerBusyPercent();

            if (accuracy > 102.0 && debtMs > 0.25) return Health.CATCHING_UP;
            if (accuracy < 95.0 || debtMs >= 20.0) return Health.LAGGING;
            if (accuracy < 99.0 || debtMs >= 2.0 || busy >= 92.0) return Health.NEAR_LIMIT;
            if (busy < 80.0 && debtMs < 0.25) return Health.STABLE_HEADROOM;
            return Health.STABLE;
        }
    }

    public enum Health {
        STABLE_HEADROOM("STABLE — HEADROOM", 0xFF69D98A),
        STABLE("STABLE", 0xFF69D98A),
        NEAR_LIMIT("NEAR LIMIT", 0xFFFFC45C),
        CATCHING_UP("CATCHING UP", 0xFFFFC45C),
        LAGGING("LAGGING", 0xFFFF6B6B),
        STOPPED("STOPPED", 0xFF9AA6B2),
        STALE("STALE", 0xFF9AA6B2);

        private final String label;
        private final int color;

        Health(String label, int color) {
            this.label = label;
            this.color = color;
        }

        public String label() { return label; }
        public int color() { return color; }
    }
}
