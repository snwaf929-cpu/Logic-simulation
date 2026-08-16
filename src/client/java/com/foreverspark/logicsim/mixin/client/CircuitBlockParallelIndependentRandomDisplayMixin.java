package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.LogicSimulationMod;
import com.foreverspark.logicsim.block.CircuitBlockEntity;
import com.foreverspark.logicsim.block.CircuitSimulationWorker;
import com.foreverspark.logicsim.block.DisplayBlock;
import com.foreverspark.logicsim.block.DisplayBlockEntity;
import com.foreverspark.logicsim.client.render.DisplayResetEdgeTracker;
import com.foreverspark.logicsim.client.render.IndependentRandomDisplayFastPath;
import com.foreverspark.logicsim.client.render.ParallelIndependentRandomDisplayFastPath;
import com.foreverspark.logicsim.client.render.RandomDisplayNetworkResetCompat;
import com.foreverspark.logicsim.client.render.RealtimeDisplaySurface;
import com.foreverspark.logicsim.editor.model.ExternalDeviceDescriptor;
import com.foreverspark.logicsim.editor.model.ExternalDeviceType;
import com.foreverspark.logicsim.interconnect.CircuitProgramRuntime;
import com.foreverspark.logicsim.interconnect.ExternalDeviceDiscovery;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * Multi-worker specialization for the common-probability independent RANDOM display path.
 *
 * <p>Priority 1500 places this below the arbitrary-RGB v11 specialization (1600) and above the original single-worker
 * independent path (1400). If the CLOCK RANDOM group is entirely 0/25/50/75/100%, this wrapper consumes the clock
 * batch in parallel. Otherwise it delegates unchanged to the established lower-priority paths.</p>
 */
@Mixin(value = CircuitBlockEntity.class, priority = 1500)
public abstract class CircuitBlockParallelIndependentRandomDisplayMixin {
    @Unique private static final long LOGIC_GENERIC_EDGE_CHUNK = 4_096L;
    @Unique private static final long LOGIC_PARALLEL_EDGE_CHUNK = 262_144L;

    @Shadow private volatile CircuitProgramRuntime runtime;

    @Unique private static final Method logic$configureRealtimeWallMethod = logic$findConfigureRealtimeWallMethod();

    @Unique private volatile IndependentRandomDisplayFastPath.Plan logic$sourcePlan;
    @Unique private volatile ParallelIndependentRandomDisplayFastPath.Plan logic$parallelPlan;
    @Unique private volatile RealtimeDisplaySurface.Surface logic$surface;
    @Unique private volatile DisplayResetEdgeTracker logic$resetTracker;
    @Unique private volatile int logic$deviceIndex = -1;
    @Unique private String logic$lastSignature = "";

    @Inject(method = "refreshExternalDeviceTargets", at = @At("TAIL"))
    private void logic$refreshParallelIndependentRandomDisplay(CallbackInfo ci) {
        CircuitBlockEntity self = (CircuitBlockEntity) (Object) this;
        CircuitProgramRuntime current = runtime;
        Level level = self.getLevel();
        if (current == null || level == null || level.isClientSide()) {
            logic$clear(true);
            logic$log(self, "inactive:runtime-or-level");
            return;
        }

        String lastReason = "no-display-device";
        for (int index = 0; index < current.externalDeviceCount(); index++) {
            if (current.externalDeviceType(index) != ExternalDeviceType.DISPLAY) continue;

            BlockPos displayPos = logic$discoverDisplay(level, self.getBlockPos(), current.externalDeviceId(index));
            if (displayPos == null) {
                lastReason = "physical-display-disconnected";
                continue;
            }

            RealtimeDisplaySurface.Surface surface = logic$surfaceForDisplay(level, displayPos);
            if (surface == null) {
                lastReason = "realtime-surface-unresolved";
                continue;
            }

            IndependentRandomDisplayFastPath.Plan existing = logic$sourcePlan;
            ParallelIndependentRandomDisplayFastPath.Plan existingParallel = logic$parallelPlan;
            DisplayResetEdgeTracker existingTracker = logic$resetTracker;
            if (existing != null
                    && existingParallel != null
                    && existingTracker != null
                    && existing.matches(current, index, surface.logicalWidth(), surface.logicalHeight())) {
                logic$surface = surface;
                logic$deviceIndex = index;
                logic$log(self, logic$activeSignature(self, index, existing, existingParallel, existingTracker, surface));
                return;
            }

            IndependentRandomDisplayFastPath.CompileResult source = IndependentRandomDisplayFastPath.compile(
                    current, index, surface.logicalWidth(), surface.logicalHeight()
            );
            if (!source.active()) {
                lastReason = "v3:" + source.reason();
                continue;
            }

            ParallelIndependentRandomDisplayFastPath.CompileResult parallel =
                    ParallelIndependentRandomDisplayFastPath.compile(source.plan());
            if (!parallel.active()) {
                lastReason = parallel.reason();
                continue;
            }

            int resetSignalId = RandomDisplayNetworkResetCompat.resetSignalId(current, index);
            if (resetSignalId < 0) {
                lastReason = "display-reset-signal-unresolved";
                continue;
            }

            logic$clear(true);
            logic$sourcePlan = source.plan();
            logic$parallelPlan = parallel.plan();
            logic$surface = surface;
            logic$deviceIndex = index;
            logic$resetTracker = new DisplayResetEdgeTracker(current, resetSignalId);
            logic$log(self, logic$activeSignature(
                    self, index, source.plan(), parallel.plan(), logic$resetTracker, surface
            ));
            return;
        }

        logic$clear(true);
        logic$log(self, "inactive:" + lastReason);
    }

    @WrapOperation(
            method = "runClockWorkerSlice",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/foreverspark/logicsim/interconnect/CircuitProgramRuntime;advanceClocksNanos(JJLjava/lang/Runnable;)J"
            )
    )
    private long logic$advanceParallelIndependentRandomDisplay(
            CircuitProgramRuntime current,
            long elapsedNanos,
            long edgeBudget,
            Runnable afterSettledEdge,
            Operation<Long> original
    ) {
        CircuitBlockEntity self = (CircuitBlockEntity) (Object) this;
        IndependentRandomDisplayFastPath.Plan source = logic$sourcePlan;
        ParallelIndependentRandomDisplayFastPath.Plan parallel = logic$parallelPlan;
        RealtimeDisplaySurface.Surface surface = logic$surface;
        DisplayResetEdgeTracker tracker = logic$resetTracker;
        int deviceIndex = logic$deviceIndex;

        if (source != null
                && parallel != null
                && surface != null
                && tracker != null
                && deviceIndex >= 0
                && current == runtime
                && CircuitSimulationWorker.resolvedWorkerBudget(self) > 1
                && source.matches(current, deviceIndex, surface.logicalWidth(), surface.logicalHeight())) {
            long resetCommand = tracker.pollCommand();
            if (resetCommand != 0L) surface.record(resetCommand);

            long bulkBudget = edgeBudget;
            if (edgeBudget >= LOGIC_GENERIC_EDGE_CHUNK) {
                bulkBudget = Math.min(LOGIC_PARALLEL_EDGE_CHUNK, edgeBudget << 6);
            }
            return parallel.advance(self, elapsedNanos, bulkBudget, surface::recordBatch);
        }

        return original.call(current, elapsedNanos, edgeBudget, afterSettledEdge);
    }

    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void logic$removeParallelIndependentRandomDisplay(CallbackInfo ci) {
        logic$clear(false);
        logic$lastSignature = "";
    }

    @Unique
    private void logic$clear(boolean synchronizeFallback) {
        IndependentRandomDisplayFastPath.Plan old = logic$sourcePlan;
        logic$sourcePlan = null;
        logic$parallelPlan = null;
        logic$surface = null;
        logic$resetTracker = null;
        logic$deviceIndex = -1;
        if (synchronizeFallback && old != null) old.synchronizeFallback();
    }

    @Unique
    private static String logic$activeSignature(
            CircuitBlockEntity self,
            int deviceIndex,
            IndependentRandomDisplayFastPath.Plan source,
            ParallelIndependentRandomDisplayFastPath.Plan parallel,
            DisplayResetEdgeTracker tracker,
            RealtimeDisplaySurface.Surface surface
    ) {
        return "active:device=" + deviceIndex
                + ":logical=" + surface.logicalWidth() + "x" + surface.logicalHeight()
                + ":clockLanes=" + source.clockLaneCount()
                + ":external=" + source.externalTriggerGroupCount()
                + ":prefilter=" + source.coordinatePrefilterLaneCount()
                + ":pack=" + source.boundaryPackMode()
                + ":reset=" + tracker.signalId()
                + ":configured=" + CircuitSimulationWorker.configuredWorkerBudget(self)
                + ":resolved=" + CircuitSimulationWorker.resolvedWorkerBudget(self)
                + ":minCycles=" + parallel.minimumCyclesPerWorker();
    }

    @Unique
    private void logic$log(CircuitBlockEntity self, String signature) {
        if (signature.equals(logic$lastSignature)) return;
        logic$lastSignature = signature;

        if (signature.startsWith("active:")) {
            IndependentRandomDisplayFastPath.Plan source = logic$sourcePlan;
            ParallelIndependentRandomDisplayFastPath.Plan parallel = logic$parallelPlan;
            RealtimeDisplaySurface.Surface surface = logic$surface;
            LogicSimulationMod.LOGGER.info(
                    "[CLOCK PARALLEL INDEPENDENT] circuit={} active=true mode=counter-common-ranges-v12 configuredWorkers={} resolvedWorkers={} globalWorkers={} minCyclesPerWorker={} maxEdgeChunk={} clockRandomLanes={} externalTriggerGroups={} logical={}x{} backing={}x{} coordinateRejectLanes={} boundaryPack={} publication=ordered-range-batches",
                    self.getBlockPos(),
                    CircuitSimulationWorker.configuredWorkerBudget(self),
                    CircuitSimulationWorker.resolvedWorkerBudget(self),
                    CircuitSimulationWorker.workerCount(),
                    parallel == null ? 0 : parallel.minimumCyclesPerWorker(),
                    LOGIC_PARALLEL_EDGE_CHUNK,
                    source == null ? 0 : source.clockLaneCount(),
                    source == null ? 0 : source.externalTriggerGroupCount(),
                    surface == null ? 0 : surface.logicalWidth(),
                    surface == null ? 0 : surface.logicalHeight(),
                    surface == null ? 0 : surface.backingWidth(),
                    surface == null ? 0 : surface.backingHeight(),
                    source == null ? 0 : source.coordinatePrefilterLaneCount(),
                    source == null ? "none" : source.boundaryPackMode()
            );
        } else {
            String reason = signature.startsWith("inactive:") ? signature.substring("inactive:".length()) : signature;
            LogicSimulationMod.LOGGER.info(
                    "[CLOCK PARALLEL INDEPENDENT] circuit={} active=false reason={}",
                    self.getBlockPos(),
                    reason
            );
        }
    }

    @Unique
    private static BlockPos logic$discoverDisplay(Level level, BlockPos circuitPos, String id) {
        for (ExternalDeviceDescriptor descriptor : ExternalDeviceDiscovery.discover(level, circuitPos)) {
            if (descriptor == null || descriptor.type() != ExternalDeviceType.DISPLAY) continue;
            if (!id.equals(descriptor.deviceId())) continue;
            return new BlockPos(descriptor.x(), descriptor.y(), descriptor.z());
        }
        return null;
    }

    @Unique
    private static RealtimeDisplaySurface.Surface logic$surfaceForDisplay(Level level, BlockPos displayPos) {
        if (level == null || level.isClientSide() || displayPos == null) return null;
        BlockState state = level.getBlockState(displayPos);
        if (!(state.getBlock() instanceof DisplayBlock)) return null;
        DisplayBlockEntity.WallInfo info = DisplayBlockEntity.wallInfo(level, displayPos, state);
        if (info == null || info.pixelWidth() <= 0 || info.pixelHeight() <= 0) return null;
        try {
            Object value = logic$configureRealtimeWallMethod.invoke(null, level, displayPos, state, info.pixelsPerTile());
            return value instanceof RealtimeDisplaySurface.Surface surface ? surface : null;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not map parallel independent RANDOM DISPLAY to realtime surface", exception);
        }
    }

    @Unique
    private static Method logic$findConfigureRealtimeWallMethod() {
        try {
            Method method = RealtimeDisplaySurface.class.getDeclaredMethod(
                    "configureWall", Level.class, BlockPos.class, BlockState.class, int.class
            );
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not access RealtimeDisplaySurface wall mapper", exception);
        }
    }
}
