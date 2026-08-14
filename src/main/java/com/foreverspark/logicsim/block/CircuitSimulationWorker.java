package com.foreverspark.logicsim.block;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Dedicated wall-clock simulation worker for programmed circuit blocks.
 *
 * Minecraft ticks are deliberately NOT the clock source.  The worker repeatedly asks every
 * registered CircuitBlockEntity to advance from System.nanoTime().  Minecraft's server thread is
 * used only later for safe physical world I/O (cables, displays, block entities).
 */
public final class CircuitSimulationWorker {
    private static final Set<CircuitBlockEntity> CIRCUITS = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final long IDLE_PARK_NANOS = 250_000L;
    private static final long BUSY_PARK_NANOS = 25_000L;

    private CircuitSimulationWorker() {}

    public static void register(CircuitBlockEntity circuit) {
        if (circuit == null) return;
        CIRCUITS.add(circuit);
        ensureStarted();
    }

    public static void unregister(CircuitBlockEntity circuit) {
        if (circuit != null) CIRCUITS.remove(circuit);
    }

    private static void ensureStarted() {
        if (!STARTED.compareAndSet(false, true)) return;
        Thread worker = Thread.ofPlatform()
                .daemon(true)
                .name("LogicSimulation-ClockWorker")
                .unstarted(CircuitSimulationWorker::runLoop);
        worker.start();
    }

    private static void runLoop() {
        while (true) {
            boolean didWork = false;
            long now = System.nanoTime();
            for (CircuitBlockEntity circuit : CIRCUITS) {
                try {
                    didWork |= circuit.runClockWorkerSlice(now);
                } catch (Throwable error) {
                    circuit.recordWorkerFailure(error);
                }
            }
            LockSupport.parkNanos(didWork ? BUSY_PARK_NANOS : IDLE_PARK_NANOS);
        }
    }
}
