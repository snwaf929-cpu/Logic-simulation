package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.LogicSimulationMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime proof/diagnostic companion for the v10 parallel RGB path.
 *
 * <p>The old activeWorkers value represented scheduled ranges, not distinct Java threads. This mixin records the
 * thread that actually enters each runRange task and logs each simulation worker thread only once. It also keeps
 * zero-cycle slices from toggling lastParallelWorkers back to one, which previously caused tens of thousands of
 * [CLOCK PARALLEL EXEC] log lines during a short benchmark and polluted the performance measurement.</p>
 */
@Mixin(
        targets = "com.foreverspark.logicsim.client.render.ParallelDeferredColorDisplayFastPath$Plan",
        remap = false
)
public abstract class ParallelDisplayWorkerProofMixin {
    @Unique private static final Set<Long> LOGIC_SEEN_THREAD_IDS = ConcurrentHashMap.newKeySet();

    @Shadow private long lastClockCycles;
    @Shadow private int lastParallelWorkers;

    @Unique private int logic$lastNonZeroParallelWorkers = 1;

    @Inject(method = "runRange", at = @At("HEAD"))
    private void logic$proveActualWorkerThread(
            int taskIndex,
            int start,
            int end,
            long counterBase,
            long preserved,
            boolean fixedCoordinateReject,
            boolean produceCommands,
            CallbackInfo ci
    ) {
        Thread thread = Thread.currentThread();
        long threadId = thread.threadId();
        if (!LOGIC_SEEN_THREAD_IDS.add(threadId)) return;

        LogicSimulationMod.LOGGER.info(
                "[CLOCK PARALLEL THREAD] name={} id={} taskIndex={} rangeCycles={} proof=actual-runRange-execution",
                thread.getName(),
                threadId,
                taskIndex,
                Math.max(0, end - start)
        );
    }

    /**
     * @author ForeverSpArK / OpenAI
     * @reason Zero-cycle scheduler probes are not worker executions. Preserve the most recent real batch width so
     * the existing execution diagnostic does not alternate 1/N and flood the log on every worker slice.
     */
    @Overwrite
    public int lastParallelWorkers() {
        if (lastClockCycles > 0L && lastParallelWorkers > 0) {
            logic$lastNonZeroParallelWorkers = lastParallelWorkers;
        }
        return logic$lastNonZeroParallelWorkers;
    }
}
