package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.LogicSimulationMod;
import com.foreverspark.logicsim.block.CircuitBlockEntity;
import com.foreverspark.logicsim.block.DisplayBlockEntity;
import com.foreverspark.logicsim.client.render.RealtimeDisplaySurface;
import com.foreverspark.logicsim.editor.model.PortSpec;
import com.foreverspark.logicsim.interconnect.CircuitProgramRuntime;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * Integrated-client display bridge.
 *
 * CircuitBlockEntity's normal DATA64 buffer is lossless and remains the authoritative path on dedicated servers.
 * In single-player the server, clock worker and renderer are in one JVM, so locally mapped Pixel Display commands can
 * bypass the server-tick framebuffer queue and update RealtimeDisplaySurface directly. Ordinary/remote outputs still
 * invoke the original methods unchanged.
 */
@Mixin(CircuitBlockEntity.class)
public abstract class CircuitBlockRealtimeDisplayMixin {
    @Unique private static final long LOGIC_GENERIC_EDGE_CHUNK = 4_096L;
    @Unique private static final long LOGIC_BULK_EDGE_CHUNK = 65_536L;

    @Shadow private volatile CircuitProgramRuntime runtime;

    @Unique private static final Field logic$displayTargetsField = logic$findDisplayTargetsField();
    @Unique private volatile Map<Object, RealtimeDisplaySurface.Surface> logic$realtimeTargets = Map.of();
    @Unique private volatile RealtimeDisplaySurface.Surface logic$bulkSurface;
    @Unique private volatile int logic$bulkOutputIndex = -1;
    @Unique private int logic$lastLoggedRealtimeTargetCount = -1;
    @Unique private int logic$lastLoggedBulkOutputIndex = Integer.MIN_VALUE;

    @Inject(method = "refreshDisplayStreamPorts", at = @At("TAIL"))
    private void logic$refreshRealtimeDisplayRoutes(CallbackInfo ci) {
        CircuitBlockEntity self = (CircuitBlockEntity) (Object) this;
        RealtimeDisplaySurface.refreshRoutes(self);

        CircuitProgramRuntime current = runtime;
        if (current == null) {
            logic$setRealtimeTargets(self, Map.of());
            logic$setBulkTarget(self, null, -1, null);
            return;
        }

        Object[] targets = logic$displayTargets(self);
        if (targets.length == 0) {
            logic$setRealtimeTargets(self, Map.of());
            logic$setBulkTarget(self, current, -1, null);
            return;
        }

        Map<Object, RealtimeDisplaySurface.Surface> mapped = new HashMap<>();
        int count = Math.min(targets.length, current.outputPortCount());
        for (int index = 0; index < count; index++) {
            Object target = targets[index];
            if (target == null) continue;
            PortSpec port = current.outputPort(index);
            if (port.width() != DisplayBlockEntity.DISPLAY_BUS_WIDTH) continue;
            RealtimeDisplaySurface.Surface surface = RealtimeDisplaySurface.route(self.getBlockPos(), port.name());
            if (surface != null) mapped.put(target, surface);
        }
        Map<Object, RealtimeDisplaySurface.Surface> immutable = mapped.isEmpty() ? Map.of() : Map.copyOf(mapped);
        logic$setRealtimeTargets(self, immutable);

        // The aggressive path is deliberately exact and narrow. If the programmed circuit exposes only one output and
        // that output is the compiled CLOCK -> RANDOM boundary feeding this realtime wall, there is no second external
        // observer whose intermediate state could be lost by batching.
        int bulkIndex = -1;
        RealtimeDisplaySurface.Surface bulkSurface = null;
        if (current.outputPortCount() == 1 && current.directRandomBoundaryBatchEligible(0)) {
            Object target = targets.length > 0 ? targets[0] : null;
            if (target != null) {
                RealtimeDisplaySurface.Surface candidate = immutable.get(target);
                if (candidate != null) {
                    bulkIndex = 0;
                    bulkSurface = candidate;
                }
            }
        }
        logic$setBulkTarget(self, current, bulkIndex, bulkSurface);
    }

    @Unique
    private void logic$setRealtimeTargets(
            CircuitBlockEntity self,
            Map<Object, RealtimeDisplaySurface.Surface> targets
    ) {
        logic$realtimeTargets = targets;
        int mappedCount = targets.size();
        if (mappedCount == logic$lastLoggedRealtimeTargetCount) return;
        logic$lastLoggedRealtimeTargetCount = mappedCount;

        RealtimeDisplaySurface.Surface first = targets.values().stream().findFirst().orElse(null);
        if (first == null) {
            LogicSimulationMod.LOGGER.info(
                    "[DISPLAY REALTIME] circuit={} mappedStreams=0 integratedFastPath=false",
                    self.getBlockPos()
            );
        } else {
            LogicSimulationMod.LOGGER.info(
                    "[DISPLAY REALTIME] circuit={} mappedStreams={} integratedFastPath=true backing={}x{} targetFps={}",
                    self.getBlockPos(), mappedCount, first.backingWidth(), first.backingHeight(),
                    (long) first.backingWidth() * first.backingHeight() >= 2_000_000L ? 30 : 60
            );
        }
    }

    @Unique
    private void logic$setBulkTarget(
            CircuitBlockEntity self,
            CircuitProgramRuntime current,
            int outputIndex,
            RealtimeDisplaySurface.Surface surface
    ) {
        logic$bulkOutputIndex = outputIndex;
        logic$bulkSurface = surface;
        if (outputIndex == logic$lastLoggedBulkOutputIndex) return;
        logic$lastLoggedBulkOutputIndex = outputIndex;

        if (outputIndex < 0 || surface == null || current == null) {
            LogicSimulationMod.LOGGER.info(
                    "[CLOCK BULK] circuit={} active=false mode=normal-edge-engine",
                    self.getBlockPos()
            );
            return;
        }

        LogicSimulationMod.LOGGER.info(
                "[CLOCK BULK] circuit={} active=true outputIndex={} randomLanes={} mode=packed-direct-display maxEdgeChunk={} displayPreFilter=true logical={}x{} batchPublication=true",
                self.getBlockPos(), outputIndex, current.directRandomBoundaryRandomLanes(outputIndex), LOGIC_BULK_EDGE_CHUNK,
                surface.logicalWidth(), surface.logicalHeight()
        );
    }

    /**
     * Both clock-worker calls (queue elapsed time with budget 0, then consume fixed chunks) pass through here. The
     * compiled direct plan keeps exact virtual edge accounting but emits only DATA64 commands that can affect the local
     * display. Generic circuits keep their 4K fairness chunk; this single-clock zero-gate path can safely amortize the
     * same bookkeeping over a much larger 64K chunk.
     */
    @WrapOperation(
            method = "runClockWorkerSlice",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/foreverspark/logicsim/interconnect/CircuitProgramRuntime;advanceClocksNanos(JJLjava/lang/Runnable;)J"
            )
    )
    private long logic$advancePackedRealtimeClock(
            CircuitProgramRuntime current,
            long elapsedNanos,
            long edgeBudget,
            Runnable afterSettledEdge,
            Operation<Long> original
    ) {
        int outputIndex = logic$bulkOutputIndex;
        RealtimeDisplaySurface.Surface surface = logic$bulkSurface;
        if (surface != null
                && outputIndex >= 0
                && current == runtime
                && current.outputPortCount() == 1
                && current.directRandomBoundaryBatchEligible(outputIndex)) {
            long bulkBudget = edgeBudget;
            if (edgeBudget >= LOGIC_GENERIC_EDGE_CHUNK) {
                bulkBudget = Math.min(LOGIC_BULK_EDGE_CHUNK, edgeBudget << 4);
            }
            long emitted = current.advanceDirectRandomDisplayBoundaryNanos(
                    elapsedNanos,
                    bulkBudget,
                    outputIndex,
                    surface.logicalWidth(),
                    surface.logicalHeight(),
                    surface::recordBatch
            );
            if (emitted >= 0L) return emitted;
        }
        return original.call(current, elapsedNanos, edgeBudget, afterSettledEdge);
    }

    @WrapOperation(
            method = "captureOutputChangesLocked",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/foreverspark/logicsim/block/CircuitBlockEntity$DisplayCommandBuffer;recordPixel(JIILcom/foreverspark/logicsim/block/CircuitBlockEntity$DisplayStreamTarget;)V"
            )
    )
    private void logic$routePixelRealtime(
            @Coerce Object buffer,
            long raw,
            int x,
            int y,
            @Coerce Object target,
            Operation<Void> original
    ) {
        RealtimeDisplaySurface.Surface surface = logic$realtimeTargets.get(target);
        if (surface != null) {
            surface.record(raw);
            return;
        }
        original.call(buffer, raw, x, y, target);
    }

    @WrapOperation(
            method = "captureOutputChangesLocked",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/foreverspark/logicsim/block/CircuitBlockEntity$DisplayCommandBuffer;recordClear(JLcom/foreverspark/logicsim/block/CircuitBlockEntity$DisplayStreamTarget;)V"
            )
    )
    private void logic$routeClearRealtime(
            @Coerce Object buffer,
            long raw,
            @Coerce Object target,
            Operation<Void> original
    ) {
        RealtimeDisplaySurface.Surface surface = logic$realtimeTargets.get(target);
        if (surface != null) {
            surface.record(raw);
            return;
        }
        original.call(buffer, raw, target);
    }

    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void logic$removeRealtimeDisplayRoutes(CallbackInfo ci) {
        RealtimeDisplaySurface.removeRoutes((CircuitBlockEntity) (Object) this);
        logic$realtimeTargets = Map.of();
        logic$bulkSurface = null;
        logic$bulkOutputIndex = -1;
        logic$lastLoggedRealtimeTargetCount = -1;
        logic$lastLoggedBulkOutputIndex = Integer.MIN_VALUE;
    }

    @Unique
    private static Field logic$findDisplayTargetsField() {
        try {
            Field field = CircuitBlockEntity.class.getDeclaredField("displayStreamTargets");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not access CircuitBlockEntity display stream targets", exception);
        }
    }

    @Unique
    private static Object[] logic$displayTargets(CircuitBlockEntity self) {
        try {
            Object value = logic$displayTargetsField.get(self);
            return value instanceof Object[] array ? array : new Object[0];
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not read CircuitBlockEntity display stream targets", exception);
        }
    }
}