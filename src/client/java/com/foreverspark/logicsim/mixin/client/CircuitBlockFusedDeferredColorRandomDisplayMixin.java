package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.LogicSimulationMod;
import com.foreverspark.logicsim.block.CircuitBlockEntity;
import com.foreverspark.logicsim.block.DisplayBlock;
import com.foreverspark.logicsim.block.DisplayBlockEntity;
import com.foreverspark.logicsim.client.render.DeferredColorRandomDisplayFastPath;
import com.foreverspark.logicsim.client.render.DisplayResetEdgeTracker;
import com.foreverspark.logicsim.client.render.FusedDeferredColorDisplayFastPath;
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
 * v8 owner for arbitrary-probability RGB565 DISPLAY execution.
 *
 * <p>It retains the proven v5 path as the fallback, but full-density field-shift displays use v8's fused sampler +
 * framebuffer loop. Only one of these paths owns the v5 sampler state; the old v5 worker mixin is intentionally not
 * loaded alongside this class.</p>
 */
@Mixin(value = CircuitBlockEntity.class, priority = 1600)
public abstract class CircuitBlockFusedDeferredColorRandomDisplayMixin {
    @Unique private static final long LOGIC_GENERIC_EDGE_CHUNK = 4_096L;
    @Unique private static final long LOGIC_RGB_EDGE_CHUNK = 262_144L;

    @Shadow private volatile CircuitProgramRuntime runtime;

    @Unique private static final Method logic$configureRealtimeWallMethod = logic$findConfigureRealtimeWallMethod();

    @Unique private volatile DeferredColorRandomDisplayFastPath.Plan logic$plan;
    @Unique private volatile FusedDeferredColorDisplayFastPath.Plan logic$fusedPlan;
    @Unique private volatile RealtimeDisplaySurface.Surface logic$surface;
    @Unique private volatile DisplayResetEdgeTracker logic$resetTracker;
    @Unique private volatile int logic$deviceIndex = -1;
    @Unique private String logic$fusedReason = "not-compiled";
    @Unique private String logic$lastSignature = "";

    @Inject(method = "refreshExternalDeviceTargets", at = @At("TAIL"))
    private void logic$refreshDeferredColorRandomDisplay(CallbackInfo ci) {
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

            DeferredColorRandomDisplayFastPath.Plan existing = logic$plan;
            DisplayResetEdgeTracker existingTracker = logic$resetTracker;
            if (existing != null
                    && existingTracker != null
                    && existing.matches(current, index, surface.logicalWidth(), surface.logicalHeight())) {
                logic$surface = surface;
                logic$deviceIndex = index;
                logic$compileFused(existing, surface);
                logic$log(self, logic$activeSignature(index, existing, existingTracker, surface));
                return;
            }

            DeferredColorRandomDisplayFastPath.CompileResult result = DeferredColorRandomDisplayFastPath.compile(
                    current, index, surface.logicalWidth(), surface.logicalHeight()
            );
            if (!result.active()) {
                lastReason = result.reason();
                continue;
            }

            int resetSignalId = RandomDisplayNetworkResetCompat.resetSignalId(current, index);
            if (resetSignalId < 0) {
                lastReason = "display-reset-signal-unresolved";
                continue;
            }

            logic$clear(true);
            logic$plan = result.plan();
            logic$surface = surface;
            logic$deviceIndex = index;
            logic$resetTracker = new DisplayResetEdgeTracker(current, resetSignalId);
            logic$compileFused(result.plan(), surface);
            logic$log(self, logic$activeSignature(index, result.plan(), logic$resetTracker, surface));
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
    private long logic$advanceDeferredColorRandomDisplay(
            CircuitProgramRuntime current,
            long elapsedNanos,
            long edgeBudget,
            Runnable afterSettledEdge,
            Operation<Long> original
    ) {
        DeferredColorRandomDisplayFastPath.Plan plan = logic$plan;
        FusedDeferredColorDisplayFastPath.Plan fusedPlan = logic$fusedPlan;
        RealtimeDisplaySurface.Surface surface = logic$surface;
        DisplayResetEdgeTracker tracker = logic$resetTracker;
        int deviceIndex = logic$deviceIndex;

        if (plan != null
                && surface != null
                && tracker != null
                && deviceIndex >= 0
                && current == runtime
                && plan.matches(current, deviceIndex, surface.logicalWidth(), surface.logicalHeight())) {
            long resetCommand = tracker.pollCommand();
            if (resetCommand != 0L) surface.record(resetCommand);

            long bulkBudget = edgeBudget;
            if (edgeBudget >= LOGIC_GENERIC_EDGE_CHUNK) {
                bulkBudget = Math.min(LOGIC_RGB_EDGE_CHUNK, edgeBudget << 6);
            }

            if (fusedPlan != null && fusedPlan.matches(current, deviceIndex, surface)) {
                return fusedPlan.advance(elapsedNanos, bulkBudget);
            }
            return plan.advance(elapsedNanos, bulkBudget, surface::recordBatch);
        }

        if (plan != null) logic$clear(true);
        return original.call(current, elapsedNanos, edgeBudget, afterSettledEdge);
    }

    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void logic$removeDeferredColorRandomDisplay(CallbackInfo ci) {
        logic$clear(false);
        logic$lastSignature = "";
    }

    @Unique
    private void logic$compileFused(
            DeferredColorRandomDisplayFastPath.Plan plan,
            RealtimeDisplaySurface.Surface surface
    ) {
        FusedDeferredColorDisplayFastPath.CompileResult fused =
                FusedDeferredColorDisplayFastPath.compile(plan, surface);
        logic$fusedPlan = fused.plan();
        logic$fusedReason = fused.reason();
    }

    @Unique
    private void logic$clear(boolean synchronizeFallback) {
        DeferredColorRandomDisplayFastPath.Plan old = logic$plan;
        logic$plan = null;
        logic$fusedPlan = null;
        logic$surface = null;
        logic$resetTracker = null;
        logic$deviceIndex = -1;
        logic$fusedReason = "not-compiled";
        if (synchronizeFallback && old != null) old.synchronizeFallback();
    }

    @Unique
    private String logic$activeSignature(
            int deviceIndex,
            DeferredColorRandomDisplayFastPath.Plan plan,
            DisplayResetEdgeTracker tracker,
            RealtimeDisplaySurface.Surface surface
    ) {
        FusedDeferredColorDisplayFastPath.Plan fused = logic$fusedPlan;
        return "active:device=" + deviceIndex
                + ":logical=" + surface.logicalWidth() + "x" + surface.logicalHeight()
                + ":clockLanes=" + plan.clockLaneCount()
                + ":hotNonColor=" + plan.hotNonColorLaneCount()
                + ":color=" + plan.deferredColorLaneCount()
                + ":arbitraryColor=" + plan.arbitraryColorLaneCount()
                + ":colorChunks=" + plan.arbitraryColorChunkCount()
                + ":external=" + plan.externalTriggerGroupCount()
                + ":prefilter=" + plan.coordinatePrefilterLaneCount()
                + ":pack=" + plan.boundaryPackMode()
                + ":reset=" + tracker.signalId()
                + ":fused=" + (fused != null)
                + ":rejectFree=" + (fused != null && fused.rejectFreeClockLoop())
                + ":fusedReason=" + logic$fusedReason;
    }

    @Unique
    private void logic$log(CircuitBlockEntity self, String signature) {
        if (signature.equals(logic$lastSignature)) return;
        logic$lastSignature = signature;

        if (signature.startsWith("active:")) {
            DeferredColorRandomDisplayFastPath.Plan plan = logic$plan;
            FusedDeferredColorDisplayFastPath.Plan fused = logic$fusedPlan;
            RealtimeDisplaySurface.Surface surface = logic$surface;
            DisplayResetEdgeTracker tracker = logic$resetTracker;
            LogicSimulationMod.LOGGER.info(
                    "[CLOCK BULK DEVICE RGB] circuit={} active=true resetSignalId={} deviceIndex={} randomLanes={} clockRandomLanes={} hotNonColorLanes={} deferredColorLanes={} arbitraryColorLanes={} arbitraryColorChunks={} externalTriggerGroups={} mode={} maxEdgeChunk={} logical={}x{} backing={}x{} coordinateRejectLanes={} boundaryPack={} batchPublication=true",
                    self.getBlockPos(),
                    tracker == null ? -1 : tracker.signalId(),
                    logic$deviceIndex,
                    plan == null ? 0 : plan.randomLaneCount(),
                    plan == null ? 0 : plan.clockLaneCount(),
                    plan == null ? 0 : plan.hotNonColorLaneCount(),
                    plan == null ? 0 : plan.deferredColorLaneCount(),
                    plan == null ? 0 : plan.arbitraryColorLaneCount(),
                    plan == null ? 0 : plan.arbitraryColorChunkCount(),
                    plan == null ? 0 : plan.externalTriggerGroupCount(),
                    fused == null ? "deferred-packed-rgb-hotloop-v5" : "fused-direct-native64-rgb-v8",
                    LOGIC_RGB_EDGE_CHUNK,
                    surface == null ? 0 : surface.logicalWidth(),
                    surface == null ? 0 : surface.logicalHeight(),
                    surface == null ? 0 : surface.backingWidth(),
                    surface == null ? 0 : surface.backingHeight(),
                    plan == null ? 0 : plan.coordinatePrefilterLaneCount(),
                    plan == null ? "none" : plan.boundaryPackMode()
            );

            if (fused != null) {
                LogicSimulationMod.LOGGER.info(
                        "[CLOCK BULK DEVICE RGB FUSED] circuit={} active=true mode=fused-direct-native64-rgb-v8 scratchCommands=0 secondDecodePass=false coordinateRejectBranch={} maxEdgeChunk={} framebuffer=native-rgb565-direct metadataCommit=whole-wall",
                        self.getBlockPos(),
                        fused.rejectFreeClockLoop() ? "elided" : "required",
                        LOGIC_RGB_EDGE_CHUNK
                );
            } else {
                LogicSimulationMod.LOGGER.info(
                        "[CLOCK BULK DEVICE RGB FUSED] circuit={} active=false reason={}",
                        self.getBlockPos(),
                        logic$fusedReason
                );
            }
        } else {
            String reason = signature.startsWith("inactive:") ? signature.substring("inactive:".length()) : signature;
            LogicSimulationMod.LOGGER.info(
                    "[CLOCK BULK DEVICE RGB] circuit={} active=false reason={}",
                    self.getBlockPos(), reason
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
            throw new IllegalStateException("Could not map deferred RGB RANDOM DISPLAY to realtime surface", exception);
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
