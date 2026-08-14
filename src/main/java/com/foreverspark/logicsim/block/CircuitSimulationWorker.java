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
    /**
     * Thread.yield() alone is only a scheduler hint and on Windows the same hot worker can immediately run again.
     * Every few busy slices perform a real park/handoff. A 1 ns request is intentional: we want to leave the CPU,
     * not sleep for a simulated-clock interval. The OS may round it upward, so do this infrequently.
     */
    private static final int HARD_HANDOFF_EVERY_BUSY_SLICES = 8;

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
        // Lowest Java priority is deliberate: spare CPU belongs to the virtual computer, but Minecraft's server,
        // render, networking and chunk work must pre-empt it whenever they need time.
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    private static void runLoop() {
        int consecutiveBusySlices = 0;
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
                consecutiveBusySlices++;
                if (consecutiveBusySlices >= HARD_HANDOFF_EVERY_BUSY_SLICES) {
                    consecutiveBusySlices = 0;
                    // Force an actual scheduler handoff so a saturated simulator cannot starve Minecraft for seconds.
                    LockSupport.parkNanos(1L);
                } else {
                    Thread.yield();
                }
            } else {
                consecutiveBusySlices = 0;
                LockSupport.parkNanos(IDLE_PARK_NANOS);
            }
        }
    }
}
