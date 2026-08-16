package com.foreverspark.logicsim.block;

import com.foreverspark.logicsim.LogicSimulationMod;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.google.gson.Gson;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Wall-clock simulation scheduler with one global CPU budget and per-Circuit-Block parallelism limits.
 *
 * <p>The compute pool is hard-capped at 25% of JVM-visible logical processors. Circuit Blocks do not reserve threads:
 * a loaded/active block submits work into the shared pool and may borrow up to its configured worker budget. If several
 * computers are active, ForkJoin scheduling redistributes those same fixed workers instead of oversubscribing the CPU.
 * Unloaded blocks unregister in CircuitBlockEntity.setRemoved(). Inactive blocks stop resubmitting busy work and are
 * only probed by their ordinary server-tick registration, so disabled computers consume no continuous simulation core.</p>
 *
 * <p>One coordinator slice per Circuit Block remains serialized through CircuitBlockEntity.runtimeLock. Proven-safe
 * hot paths may call runParallelRanges() from that coordinator; all helpers use this same global pool and therefore
 * stay inside the 25% machine-wide cap.</p>
 */
public final class CircuitSimulationWorker {
    @FunctionalInterface
    public interface IndexedRangeTask {
        void run(int taskIndex, int startInclusive, int endExclusive);
    }

    private static final Gson BOARD_GSON = new Gson();
    private static final int PROCESSORS = Math.max(1, Runtime.getRuntime().availableProcessors());
    private static final int WORKER_COUNT = CircuitWorkerPolicy.systemMaximum(PROCESSORS);
    private static final int WORKER_PRIORITY = PROCESSORS >= 16
            ? Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 1)
            : Thread.NORM_PRIORITY;

    private static final ConcurrentHashMap<CircuitBlockEntity, Entry> CIRCUITS = new ConcurrentHashMap<>();
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final ForkJoinPool COMPUTE_POOL = new ForkJoinPool(
            WORKER_COUNT,
            CircuitSimulationWorker::newWorker,
            (thread, error) -> LogicSimulationMod.LOGGER.error("Uncaught circuit simulation worker failure", error),
            true
    );

    private static final long ACTIVE_DISPATCH_PARK_NANOS = 50_000L;
    private static final long IDLE_DISPATCH_PARK_NANOS = 500_000L;

    private CircuitSimulationWorker() {}

    public static void register(CircuitBlockEntity circuit) {
        if (circuit == null) return;
        Entry entry = CIRCUITS.computeIfAbsent(circuit, Entry::new);
        entry.refreshBudget();
        entry.wake = true;
        ensureStarted();
    }

    public static void unregister(CircuitBlockEntity circuit) {
        if (circuit == null) return;
        CIRCUITS.remove(circuit);
    }

    public static int logicalProcessorCount() { return PROCESSORS; }
    public static int workerCount() { return WORKER_COUNT; }

    /** Persisted request: 0=AUTO, otherwise the explicit user maximum. */
    public static int configuredWorkerBudget(CircuitBlockEntity circuit) {
        Entry entry = circuit == null ? null : CIRCUITS.get(circuit);
        if (entry == null) return CircuitWorkerPolicy.DEFAULT;
        entry.refreshBudget();
        return entry.requestedWorkers;
    }

    /** Hardware-resolved upper bound for this Circuit Block. */
    public static int resolvedWorkerBudget(CircuitBlockEntity circuit) {
        Entry entry = circuit == null ? null : CIRCUITS.get(circuit);
        if (entry == null) return 1;
        entry.refreshBudget();
        return entry.resolvedWorkers;
    }

    /**
     * Execute independent ranges using at most this Circuit Block's configured worker budget and the shared global
     * simulation pool. The caller remains one of the workers and processes range 0 itself; helpers process the rest.
     * This is intended only for compile-proven independent work. Stateful circuit ordering must remain serialized.
     *
     * @return number of ranges actually used
     */
    public static int runParallelRanges(
            CircuitBlockEntity circuit,
            int itemCount,
            int minimumItemsPerWorker,
            IndexedRangeTask task
    ) {
        if (task == null) throw new IllegalArgumentException("parallel range task is required");
        if (itemCount <= 0) return 0;

        int minItems = Math.max(1, minimumItemsPerWorker);
        int budget = resolvedWorkerBudget(circuit);
        int useful = Math.max(1, (itemCount + minItems - 1) / minItems);
        int ranges = Math.max(1, Math.min(Math.min(budget, WORKER_COUNT), useful));
        if (ranges <= 1) {
            task.run(0, 0, itemCount);
            return 1;
        }

        ForkJoinTask<?>[] helpers = new ForkJoinTask<?>[ranges - 1];
        int base = itemCount / ranges;
        int remainder = itemCount % ranges;
        int cursor = 0;

        // Range 0 is deliberately retained for the coordinator worker.
        int firstSize = base + (remainder > 0 ? 1 : 0);
        int firstEnd = firstSize;
        cursor = firstEnd;

        for (int range = 1; range < ranges; range++) {
            int size = base + (range < remainder ? 1 : 0);
            int start = cursor;
            int end = start + size;
            int taskIndex = range;
            ForkJoinTask<?> helper = ForkJoinTask.adapt(() -> task.run(taskIndex, start, end));
            helpers[range - 1] = helper;
            COMPUTE_POOL.execute(helper);
            cursor = end;
        }

        Throwable failure = null;
        try {
            task.run(0, 0, firstEnd);
        } catch (Throwable error) {
            failure = error;
        }

        for (ForkJoinTask<?> helper : helpers) {
            try {
                helper.join();
            } catch (Throwable error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }

        if (failure != null) {
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("Parallel circuit worker failed", failure);
        }
        return ranges;
    }

    private static void ensureStarted() {
        if (!STARTED.compareAndSet(false, true)) return;

        LogicSimulationMod.LOGGER.info(
                "[CLOCK WORKERS] processors={} workers={} cpuShareCap=25% priority={} scheduler=dynamic-forkjoin perCircuit=AUTO-or-1..{} minecraftTickIndependent=true unloadedWorkers=0 inactiveContinuousWorkers=0",
                PROCESSORS,
                WORKER_COUNT,
                WORKER_PRIORITY,
                WORKER_COUNT
        );

        Thread dispatcher = Thread.ofPlatform()
                .daemon(true)
                .name("LogicSimulation-ClockDispatcher")
                .unstarted(CircuitSimulationWorker::dispatchLoop);
        dispatcher.setPriority(Thread.NORM_PRIORITY);
        dispatcher.start();
    }

    private static void dispatchLoop() {
        while (true) {
            boolean active = false;
            boolean submitted = false;

            for (Entry entry : CIRCUITS.values()) {
                if (!entry.wake && !entry.busy) continue;
                active = true;
                if (!entry.inFlight.compareAndSet(false, true)) continue;

                entry.wake = false;
                submitted = true;
                COMPUTE_POOL.execute(() -> runEntry(entry));
            }

            if (!active) LockSupport.parkNanos(IDLE_DISPATCH_PARK_NANOS);
            else if (!submitted) LockSupport.parkNanos(ACTIVE_DISPATCH_PARK_NANOS);
            else Thread.onSpinWait();
        }
    }

    private static void runEntry(Entry entry) {
        boolean busy = false;
        try {
            // A queued task can outlive chunk unload by a few microseconds. Never touch it after unregister wins.
            if (CIRCUITS.get(entry.circuit) != entry) return;
            busy = entry.circuit.runClockWorkerSlice(System.nanoTime());
        } catch (Throwable error) {
            entry.circuit.recordWorkerFailure(error);
        } finally {
            entry.busy = busy;
            entry.inFlight.set(false);
        }
    }

    private static ForkJoinWorkerThread newWorker(ForkJoinPool pool) {
        ForkJoinWorkerThread worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
        worker.setName("LogicSimulation-ClockWorker-" + worker.getPoolIndex());
        worker.setPriority(WORKER_PRIORITY);
        return worker;
    }

    private static final class Entry {
        private final CircuitBlockEntity circuit;
        private final AtomicBoolean inFlight = new AtomicBoolean();
        private volatile boolean wake = true;
        private volatile boolean busy;
        private volatile String boardReference;
        private volatile int requestedWorkers = CircuitWorkerPolicy.DEFAULT;
        private volatile int resolvedWorkers = 1;

        private Entry(CircuitBlockEntity circuit) {
            this.circuit = circuit;
            refreshBudget();
        }

        private void refreshBudget() {
            String json = circuit.boardJson();
            // CircuitBlockEntity replaces the canonical board String object on every save. Identity comparison avoids
            // hashing/parsing potentially multi-megabyte boards on every server tick.
            if (json == boardReference) return;
            boardReference = json;

            int requested = CircuitWorkerPolicy.DEFAULT;
            if (json != null && !json.isBlank()) {
                try {
                    CircuitDocument document = BOARD_GSON.fromJson(json, CircuitDocument.class);
                    if (document != null) requested = document.simulationWorkers;
                } catch (RuntimeException ignored) {
                    // Invalid board persistence is handled by CircuitBlockEntity itself; keep the safe one-worker mode.
                }
            }
            requestedWorkers = CircuitWorkerPolicy.normalizePersisted(requested);
            resolvedWorkers = CircuitWorkerPolicy.resolve(requestedWorkers, WORKER_COUNT);
        }
    }
}
