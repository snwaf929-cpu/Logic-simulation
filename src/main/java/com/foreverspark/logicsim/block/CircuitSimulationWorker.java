package com.foreverspark.logicsim.block;

import com.foreverspark.logicsim.LogicSimulationMod;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Dedicated wall-clock simulation workers for programmed circuit blocks.
 *
 * Minecraft ticks are deliberately NOT the clock source. Each circuit is permanently assigned to one worker shard,
 * so a single circuit remains deterministic/single-threaded while independent Circuit Blocks can execute on separate
 * CPU cores. Minecraft's server/render threads still rely on the OS scheduler for pre-emption; the simulator no longer
 * voluntarily yields after every hot slice because that destroyed cache locality and heavily penalized Windows/hybrid
 * CPUs at MHz rates.
 */
public final class CircuitSimulationWorker {
    private static final int PROCESSORS = Math.max(1, Runtime.getRuntime().availableProcessors());
    private static final int WORKER_COUNT = chooseWorkerCount(PROCESSORS);
    private static final int WORKER_PRIORITY = chooseWorkerPriority(PROCESSORS);

    private static final List<Set<CircuitBlockEntity>> SHARDS = createShards();
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    /** Idle workers may sleep; an active MHz worker should stay hot on its CPU/cache. */
    private static final long IDLE_PARK_NANOS = 500_000L;
    /**
     * Java/Windows Thread.yield() can migrate the simulation thread and throw away its hot primitive-array cache.
     * The OS already pre-empts normal-priority threads. Keep a very occasional real handoff only as a safety valve.
     */
    private static final int HARD_HANDOFF_EVERY_BUSY_SLICES = 64;

    private CircuitSimulationWorker() {}

    public static void register(CircuitBlockEntity circuit) {
        if (circuit == null) return;
        SHARDS.get(shardIndex(circuit)).add(circuit);
        ensureStarted();
    }

    public static void unregister(CircuitBlockEntity circuit) {
        if (circuit == null) return;
        SHARDS.get(shardIndex(circuit)).remove(circuit);
    }

    private static void ensureStarted() {
        if (!STARTED.compareAndSet(false, true)) return;

        LogicSimulationMod.LOGGER.info(
                "[CLOCK WORKERS] processors={} workers={} priority={} pacing=cache-hot-sharded minecraftTickIndependent=true",
                PROCESSORS,
                WORKER_COUNT,
                WORKER_PRIORITY
        );

        for (int shard = 0; shard < WORKER_COUNT; shard++) {
            final int workerShard = shard;
            Thread worker = Thread.ofPlatform()
                    .daemon(true)
                    .name("LogicSimulation-ClockWorker-" + workerShard)
                    .unstarted(() -> runLoop(workerShard));
            worker.setPriority(WORKER_PRIORITY);
            worker.start();
        }
    }

    private static void runLoop(int shardIndex) {
        Set<CircuitBlockEntity> circuits = SHARDS.get(shardIndex);
        int consecutiveBusySlices = 0;

        while (true) {
            boolean didWork = false;
            long now = System.nanoTime();
            for (CircuitBlockEntity circuit : circuits) {
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
                    // Rare scheduler handoff. Normal OS pre-emption provides the actual Minecraft fairness.
                    LockSupport.parkNanos(1L);
                } else {
                    // CPU hint only: unlike Thread.yield(), this does not voluntarily surrender the time slice.
                    Thread.onSpinWait();
                }
            } else {
                consecutiveBusySlices = 0;
                LockSupport.parkNanos(IDLE_PARK_NANOS);
            }
        }
    }

    private static List<Set<CircuitBlockEntity>> createShards() {
        List<Set<CircuitBlockEntity>> shards = new ArrayList<>(WORKER_COUNT);
        for (int index = 0; index < WORKER_COUNT; index++) {
            shards.add(ConcurrentHashMap.newKeySet());
        }
        return List.copyOf(shards);
    }

    private static int shardIndex(CircuitBlockEntity circuit) {
        long position = circuit.getBlockPos().asLong();
        int mixed = Long.hashCode(position);
        return (mixed & 0x7FFFFFFF) % WORKER_COUNT;
    }

    private static int chooseWorkerCount(int processors) {
        if (processors <= 4) return 1;
        // Leave at least two logical CPUs for Minecraft/OS work and avoid spawning an excessive idle thread fleet.
        return Math.max(2, Math.min(8, processors - 2));
    }

    private static int chooseWorkerPriority(int processors) {
        // MIN_PRIORITY was a major throughput limiter on Windows hybrid CPUs. One notch above normal is reserved for
        // machines with enough cores; smaller systems stay at normal priority so the game remains responsive.
        return processors >= 8
                ? Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 1)
                : Thread.NORM_PRIORITY;
    }
}
