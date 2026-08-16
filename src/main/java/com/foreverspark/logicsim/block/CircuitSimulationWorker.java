package com.foreverspark.logicsim.block;

import com.foreverspark.logicsim.LogicSimulationMod;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.google.gson.Gson;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.RecursiveAction;
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

    /**
     * AUTO worker selection uses an EMA of useful range demand and coarse worker tiers. The two midpoint thresholds
     * intentionally have a gap so a batch hovering around a boundary cannot flip worker count every invocation.
     */
    private static final int AUTO_EMA_SHIFT = 3; // 1/8 new sample
    private static final int AUTO_WARMUP_SAMPLES = 4;
    private static final int AUTO_HYSTERESIS_Q8 = 64; // 0.25 worker
    private static final int Q8_ONE = 256;

    private static final long PARALLEL_STATS_WINDOW_NANOS = 1_000_000_000L;

    /**
     * Helper tasks are reused by the coordinator thread after every join. A circuit never has overlapping coordinator
     * slices, so one ThreadLocal array per pool worker removes the per-batch helper-array and ForkJoinTask allocations.
     */
    private static final ThreadLocal<ReusableRangeAction[]> RANGE_ACTION_CACHE = ThreadLocal.withInitial(() -> {
        ReusableRangeAction[] actions = new ReusableRangeAction[Math.max(0, WORKER_COUNT - 1)];
        for (int index = 0; index < actions.length; index++) actions[index] = new ReusableRangeAction();
        return actions;
    });

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

    /** Persisted request: 0=AUTO, otherwise an explicit fixed worker count. */
    public static int configuredWorkerBudget(CircuitBlockEntity circuit) {
        Entry entry = circuit == null ? null : CIRCUITS.get(circuit);
        if (entry == null) return CircuitWorkerPolicy.DEFAULT;
        entry.refreshBudget();
        return entry.requestedWorkers;
    }

    /** Hardware-resolved upper bound for this Circuit Block. AUTO may currently use fewer workers. */
    public static int resolvedWorkerBudget(CircuitBlockEntity circuit) {
        Entry entry = circuit == null ? null : CIRCUITS.get(circuit);
        if (entry == null) return 1;
        entry.refreshBudget();
        return entry.resolvedWorkers;
    }

    /** Current stable worker count: fixed manual value, or the hysteretic AUTO tier. */
    public static int effectiveWorkerBudget(CircuitBlockEntity circuit) {
        Entry entry = circuit == null ? null : CIRCUITS.get(circuit);
        if (entry == null) return 1;
        entry.refreshBudget();
        return entry.effectiveWorkers();
    }

    /**
     * Execute independent ranges inside the shared global simulation pool.
     *
     * <p>Manual worker settings are fixed: selecting 4 means four ranges whenever there are at least four items.
     * AUTO alone is allowed to scale. Coarse/bulk work updates AUTO demand; fine second-stage work (for example
     * framebuffer owners with minimumItemsPerWorker=1) consumes the already-selected AUTO tier without feeding a
     * different workload shape back into the controller.</p>
     *
     * <p>The caller remains one of the workers and processes range 0 itself. Helper task objects are reused after their
     * joins to avoid allocation pressure in the MHz hot loop.</p>
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
        Entry entry = circuit == null ? null : CIRCUITS.get(circuit);
        if (entry != null) entry.refreshBudget();

        int resolved = entry == null ? 1 : Math.max(1, Math.min(WORKER_COUNT, entry.resolvedWorkers));
        int useful = Math.max(1, Math.min(resolved, (int) (((long) itemCount + minItems - 1L) / minItems)));

        int ranges;
        boolean bulkStage = minItems > 1;
        if (entry == null) {
            ranges = 1;
        } else if (entry.requestedWorkers != CircuitWorkerPolicy.AUTO) {
            // Explicit values are fixed worker counts, not ceilings. Avoid empty ranges only when itemCount is smaller.
            ranges = Math.max(1, Math.min(Math.min(resolved, WORKER_COUNT), itemCount));
        } else if (bulkStage) {
            ranges = Math.max(1, Math.min(entry.selectAutoWorkers(useful), itemCount));
        } else {
            // Do not let tiny framebuffer-owner batches perturb AUTO's bulk-work EMA.
            ranges = Math.max(1, Math.min(Math.min(entry.effectiveWorkers(), useful), itemCount));
        }

        long startedNanos = System.nanoTime();
        executeRanges(itemCount, ranges, task);
        long elapsedNanos = Math.max(0L, System.nanoTime() - startedNanos);

        if (entry != null) {
            entry.recordParallelExecution(bulkStage, itemCount, ranges, elapsedNanos);
        }
        return ranges;
    }

    private static void executeRanges(int itemCount, int ranges, IndexedRangeTask task) {
        if (ranges <= 1) {
            task.run(0, 0, itemCount);
            return;
        }

        ReusableRangeAction[] helpers = RANGE_ACTION_CACHE.get();
        int base = itemCount / ranges;
        int remainder = itemCount % ranges;
        int cursor = 0;

        int firstSize = base + (remainder > 0 ? 1 : 0);
        int firstEnd = firstSize;
        cursor = firstEnd;

        for (int range = 1; range < ranges; range++) {
            int size = base + (range < remainder ? 1 : 0);
            int start = cursor;
            int end = start + size;
            ReusableRangeAction helper = helpers[range - 1];
            helper.configure(task, range, start, end);
            COMPUTE_POOL.execute(helper);
            cursor = end;
        }

        Throwable failure = null;
        try {
            task.run(0, 0, firstEnd);
        } catch (Throwable error) {
            failure = error;
        }

        for (int index = 0; index < ranges - 1; index++) {
            ReusableRangeAction helper = helpers[index];
            try {
                helper.join();
            } catch (Throwable error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            } finally {
                helper.clear();
            }
        }

        if (failure != null) {
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("Parallel circuit worker failed", failure);
        }
    }

    private static void ensureStarted() {
        if (!STARTED.compareAndSet(false, true)) return;

        LogicSimulationMod.LOGGER.info(
                "[CLOCK WORKERS] processors={} workers={} cpuShareCap=25% priority={} scheduler=stable-forkjoin-cache-hot perCircuit=AUTO-hysteretic-or-fixed-1..{} minecraftTickIndependent=true unloadedWorkers=0 inactiveContinuousWorkers=0",
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

            // A continuously busy computer should stay hot without waiting for the dispatcher between 2.5ms slices.
            // Forking the successor from the current ForkJoin worker preferentially keeps it on the same local deque;
            // another worker may still steal it when several computers need the shared pool.
            if (busy
                    && CIRCUITS.get(entry.circuit) == entry
                    && entry.inFlight.compareAndSet(false, true)) {
                ForkJoinTask.adapt(() -> runEntry(entry)).fork();
            }
        }
    }

    private static ForkJoinWorkerThread newWorker(ForkJoinPool pool) {
        ForkJoinWorkerThread worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
        worker.setName("LogicSimulation-ClockWorker-" + worker.getPoolIndex());
        worker.setPriority(WORKER_PRIORITY);
        return worker;
    }

    private static final class ReusableRangeAction extends RecursiveAction {
        private IndexedRangeTask task;
        private int taskIndex;
        private int start;
        private int end;

        private void configure(IndexedRangeTask task, int taskIndex, int start, int end) {
            reinitialize();
            this.task = task;
            this.taskIndex = taskIndex;
            this.start = start;
            this.end = end;
        }

        @Override
        protected void compute() {
            task.run(taskIndex, start, end);
        }

        private void clear() {
            task = null;
        }
    }

    private static final class Entry {
        private final CircuitBlockEntity circuit;
        private final AtomicBoolean inFlight = new AtomicBoolean();
        private volatile boolean wake = true;
        private volatile boolean busy;
        private volatile String boardReference;
        private volatile int requestedWorkers = CircuitWorkerPolicy.DEFAULT;
        private volatile int resolvedWorkers = 1;

        // AUTO controller state. Only the serialized coordinator mutates these during parallel work.
        private int autoWorkers = 1;
        private int autoDemandQ8;
        private int autoDemandSamples;

        // Allocation-free, once-per-second parallel telemetry.
        private long statsWindowStartNanos;
        private long statsGenerationBatches;
        private long statsGenerationItems;
        private long statsGenerationWorkerSum;
        private long statsGenerationWallNanos;
        private int statsGenerationMinWorkers = Integer.MAX_VALUE;
        private int statsGenerationMaxWorkers;
        private long statsFramebufferBatches;
        private long statsFramebufferItems;
        private long statsFramebufferWorkerSum;
        private long statsFramebufferWallNanos;
        private int statsFramebufferMinWorkers = Integer.MAX_VALUE;
        private int statsFramebufferMaxWorkers;

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

            int normalized = CircuitWorkerPolicy.normalizePersisted(requested);
            int resolved = CircuitWorkerPolicy.resolve(normalized, WORKER_COUNT);
            boolean policyChanged = normalized != requestedWorkers || resolved != resolvedWorkers;
            requestedWorkers = normalized;
            resolvedWorkers = resolved;

            if (policyChanged) {
                autoWorkers = resolved;
                autoDemandQ8 = 0;
                autoDemandSamples = 0;
                resetParallelStats();
            }
        }

        private int effectiveWorkers() {
            int max = Math.max(1, Math.min(WORKER_COUNT, resolvedWorkers));
            if (requestedWorkers != CircuitWorkerPolicy.AUTO) return max;
            return Math.max(1, Math.min(max, autoWorkers));
        }

        private int selectAutoWorkers(int usefulWorkers) {
            int max = Math.max(1, Math.min(WORKER_COUNT, resolvedWorkers));
            int sample = Math.max(1, Math.min(max, usefulWorkers));
            if (autoWorkers < 1 || autoWorkers > max) autoWorkers = max;

            int sampleQ8 = sample * Q8_ONE;
            if (autoDemandSamples == 0) {
                autoDemandQ8 = sampleQ8;
            } else {
                autoDemandQ8 += (sampleQ8 - autoDemandQ8) >> AUTO_EMA_SHIFT;
            }
            autoDemandSamples++;

            if (autoDemandSamples < AUTO_WARMUP_SAMPLES || max <= 1) return effectiveWorkers();

            int current = effectiveWorkers();
            int next = nextWorkerTier(current, max);
            if (next > current) {
                int upThresholdQ8 = ((current + next) * Q8_ONE) / 2 + AUTO_HYSTERESIS_Q8;
                if (autoDemandQ8 >= upThresholdQ8) {
                    autoWorkers = next;
                    return autoWorkers;
                }
            }

            int previous = previousWorkerTier(current);
            if (previous < current) {
                int downThresholdQ8 = ((previous + current) * Q8_ONE) / 2 - AUTO_HYSTERESIS_Q8;
                if (autoDemandQ8 <= downThresholdQ8) {
                    autoWorkers = previous;
                }
            }
            return effectiveWorkers();
        }

        private void recordParallelExecution(boolean generationStage, int itemCount, int workers, long wallNanos) {
            if (generationStage) {
                statsGenerationBatches++;
                statsGenerationItems += itemCount;
                statsGenerationWorkerSum += workers;
                statsGenerationWallNanos += wallNanos;
                statsGenerationMinWorkers = Math.min(statsGenerationMinWorkers, workers);
                statsGenerationMaxWorkers = Math.max(statsGenerationMaxWorkers, workers);
            } else {
                statsFramebufferBatches++;
                statsFramebufferItems += itemCount;
                statsFramebufferWorkerSum += workers;
                statsFramebufferWallNanos += wallNanos;
                statsFramebufferMinWorkers = Math.min(statsFramebufferMinWorkers, workers);
                statsFramebufferMaxWorkers = Math.max(statsFramebufferMaxWorkers, workers);
            }

            long now = System.nanoTime();
            if (statsWindowStartNanos == 0L) {
                statsWindowStartNanos = now;
                return;
            }
            if (now - statsWindowStartNanos < PARALLEL_STATS_WINDOW_NANOS) return;

            long genAvgWorkersX100 = statsGenerationBatches == 0L
                    ? 0L
                    : (statsGenerationWorkerSum * 100L) / statsGenerationBatches;
            long fbAvgWorkersX100 = statsFramebufferBatches == 0L
                    ? 0L
                    : (statsFramebufferWorkerSum * 100L) / statsFramebufferBatches;

            LogicSimulationMod.LOGGER.info(
                    "[CLOCK PARALLEL STATS] circuit={} configuredWorkers={} resolvedWorkers={} effectiveWorkers={} genBatches={} genItems={} genWorkersAvgX100={} genWorkersMin={} genWorkersMax={} genWallMicros={} fbBatches={} fbItems={} fbWorkersAvgX100={} fbWorkersMin={} fbWorkersMax={} fbWallMicros={}",
                    circuit.getBlockPos(),
                    requestedWorkers,
                    resolvedWorkers,
                    effectiveWorkers(),
                    statsGenerationBatches,
                    statsGenerationItems,
                    genAvgWorkersX100,
                    statsGenerationBatches == 0L ? 0 : statsGenerationMinWorkers,
                    statsGenerationMaxWorkers,
                    statsGenerationWallNanos / 1_000L,
                    statsFramebufferBatches,
                    statsFramebufferItems,
                    fbAvgWorkersX100,
                    statsFramebufferBatches == 0L ? 0 : statsFramebufferMinWorkers,
                    statsFramebufferMaxWorkers,
                    statsFramebufferWallNanos / 1_000L
            );
            resetParallelStats();
            statsWindowStartNanos = now;
        }

        private void resetParallelStats() {
            statsWindowStartNanos = 0L;
            statsGenerationBatches = 0L;
            statsGenerationItems = 0L;
            statsGenerationWorkerSum = 0L;
            statsGenerationWallNanos = 0L;
            statsGenerationMinWorkers = Integer.MAX_VALUE;
            statsGenerationMaxWorkers = 0;
            statsFramebufferBatches = 0L;
            statsFramebufferItems = 0L;
            statsFramebufferWorkerSum = 0L;
            statsFramebufferWallNanos = 0L;
            statsFramebufferMinWorkers = Integer.MAX_VALUE;
            statsFramebufferMaxWorkers = 0;
        }
    }

    private static int nextWorkerTier(int current, int max) {
        if (current >= max) return max;
        int next;
        if (current < 2) next = 2;
        else if (current < 4) next = 4;
        else next = current << 1;
        return Math.min(max, next);
    }

    private static int previousWorkerTier(int current) {
        if (current <= 1) return 1;
        if (current <= 2) return 1;
        return Math.max(1, Integer.highestOneBit(current - 1));
    }
}
