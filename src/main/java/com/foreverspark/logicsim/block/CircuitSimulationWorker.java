package com.foreverspark.logicsim.block;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Dedicated wall-clock simulation worker for programmed circuit blocks.
 *
 * Minecraft ticks are deliberately NOT the clock source. The worker repeatedly asks every registered
 * CircuitBlockEntity to advance from System.nanoTime(). Minecraft's server/render threads must always win
 * scheduling contention: simulated MHz is allowed to run slower, but it is never allowed to freeze the game.
 */
public final class CircuitSimulationWorker {
    private static final Set<CircuitBlockEntity> CIRCUITS = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final long IDLE_PARK_NANOS = 250_000L;

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
        // Keep the simulation below normal-priority Minecraft server/render work. On a many-core PC it can still
        // consume otherwise-idle CPU, but it should not win when the game itself needs a core.
        worker.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
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

            if (didWork) {
                /*
                 * A pure spin loop let the worker immediately reacquire CircuitBlockEntity's runtime monitor after
                 * every slice. At overloaded MHz rates that starved the server thread for seconds (program installs
                 * measured 3+ seconds and Minecraft reported hundreds of ticks behind). yield() releases our time
                 * slice without the inaccurate millisecond-scale oversleep of Windows micro-sleeps.
                 */
                Thread.yield();
            } else {
                LockSupport.parkNanos(IDLE_PARK_NANOS);
            }
        }
    }
}
