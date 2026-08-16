package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.LogicSimulationMod;
import com.foreverspark.logicsim.block.CircuitBlockEntity;
import com.foreverspark.logicsim.block.DisplayBlock;
import com.foreverspark.logicsim.block.DisplayBlockEntity;
import com.foreverspark.logicsim.client.render.DenseIndependentRandomDisplayFastPath;
import com.foreverspark.logicsim.client.render.DisplayResetEdgeTracker;
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

/** Highest-priority v4 worker: one 64-bit random decision word per CLOCK sample when the v3 proof allows it. */
@Mixin(value = CircuitBlockEntity.class, priority = 1500)
public abstract class CircuitBlockDenseRandomDisplayMixin {
    @Unique private static final long LOGIC_GENERIC_EDGE_CHUNK = 4_096L;
    @Unique private static final long LOGIC_DENSE_EDGE_CHUNK = 262_144L;

    @Shadow private volatile CircuitProgramRuntime runtime;

    @Unique private static final Method logic$configureRealtimeWallMethod = logic$findConfigureRealtimeWallMethod();

    @Unique private volatile DenseIndependentRandomDisplayFastPath.Plan logic$plan;
    @Unique private volatile RealtimeDisplaySurface.Surface logic$surface;
    @Unique private volatile DisplayResetEdgeTracker logic$resetTracker;
    @Unique private volatile int logic$deviceIndex = -1;
    @Unique private String logic$lastSignature = "";

    @Inject(method = "refreshExternalDeviceTargets", at = @At("TAIL"))
    private void logic$refreshDenseRandomDisplay(CallbackInfo ci) {
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

            DenseIndependentRandomDisplayFastPath.Plan existing = logic$plan;
            DisplayResetEdgeTracker existingTracker = logic$resetTracker;
            if (existing != null
                    && existingTracker != null
                    && existing.matches(current, index, surface.logicalWidth(), surface.logicalHeight())) {
                logic$surface = surface;
                logic$deviceIndex = index;
                logic$log(self, logic$activeSignature(index, existing, existingTracker, surface));
                return;
            }

            DenseIndependentRandomDisplayFastPath.CompileResult result = DenseIndependentRandomDisplayFastPath.compile(
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
    private long logic$advanceDenseRandomDisplay(
            CircuitProgramRuntime current,
            long elapsedNanos,
            long edgeBudget,
            Runnable afterSettledEdge,
            Operation<Long> original
    ) {
        DenseIndependentRandomDisplayFastPath.Plan plan = logic$plan;
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
                bulkBudget = Math.min(LOGIC_DENSE_EDGE_CHUNK, edgeBudget << 6);
            }
            return plan.advance(elapsedNanos, bulkBudget, surface::recordBatch);
        }

        if (plan != null) logic$clear(true);
        return original.call(current, elapsedNanos, edgeBudget, afterSettledEdge);
    }

    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void logic$removeDenseRandomDisplay(CallbackInfo ci) {
        logic$clear(false);
        logic$lastSignature = "";
    }

    @Unique
    private void logic$clear(boolean synchronizeFallback) {
        DenseIndependentRandomDisplayFastPath.Plan old = logic$plan;
        logic$plan = null;
        logic$surface = null;
        logic$resetTracker = null;
        logic$deviceIndex = -1;
        if (synchronizeFallback && old != null) old.synchronizeFallback();
    }

    @Unique
    private static String logic$activeSignature(
            int deviceIndex,
            DenseIndependentRandomDisplayFastPath.Plan plan,
            DisplayResetEdgeTracker tracker,
            RealtimeDisplaySurface.Surface surface
    ) {
        return "active:device=" + deviceIndex
                + ":logical=" + surface.logicalWidth() + "x" + surface.logicalHeight()
                + ":clockLanes=" + plan.clockLaneCount()
                + ":external=" + plan.externalTriggerGroupCount()
                + ":bits=" + plan.randomBitsPerCycle()
                + ":prefilter=" + plan.coordinatePrefilterLaneCount()
                + ":pack=" + plan.boundaryPackMode()
                + ":reset=" + tracker.signalId();
    }

    @Unique
    private void logic$log(CircuitBlockEntity self, String signature) {
        if (signature.equals(logic$lastSignature)) return;
        logic$lastSignature = signature;

        if (signature.startsWith("active:")) {
            DenseIndependentRandomDisplayFastPath.Plan plan = logic$plan;
            RealtimeDisplaySurface.Surface surface = logic$surface;
            DisplayResetEdgeTracker tracker = logic$resetTracker;
            LogicSimulationMod.LOGGER.info(
                    "[CLOCK BULK DEVICE DENSE] circuit={} active=true resetSignalId={} deviceIndex={} randomLanes={} clockRandomLanes={} externalTriggerGroups={} mode=dense-one-word-hotloop-v4 maxEdgeChunk={} logical={}x{} backing={}x{} randomBitsPerCycle={} rngWordsPerCycle={} coordinatePrefilter={} coordinateRejectLanes={} boundaryPack={} batchPublication=true",
                    self.getBlockPos(),
                    tracker == null ? -1 : tracker.signalId(),
                    logic$deviceIndex,
                    plan == null ? 0 : plan.randomLaneCount(),
                    plan == null ? 0 : plan.clockLaneCount(),
                    plan == null ? 0 : plan.externalTriggerGroupCount(),
                    LOGIC_DENSE_EDGE_CHUNK,
                    surface == null ? 0 : surface.logicalWidth(),
                    surface == null ? 0 : surface.logicalHeight(),
                    surface == null ? 0 : surface.backingWidth(),
                    surface == null ? 0 : surface.backingHeight(),
                    plan == null ? 0 : plan.randomBitsPerCycle(),
                    plan == null ? 0 : plan.rngWordsPerCycle(),
                    plan != null && plan.coordinatePrefilterEnabled(),
                    plan == null ? 0 : plan.coordinatePrefilterLaneCount(),
                    plan == null ? "none" : plan.boundaryPackMode()
            );
        } else {
            String reason = signature.startsWith("inactive:") ? signature.substring("inactive:".length()) : signature;
            LogicSimulationMod.LOGGER.info(
                    "[CLOCK BULK DEVICE DENSE] circuit={} active=false reason={}",
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
            throw new IllegalStateException("Could not map dense RANDOM DISPLAY to realtime surface", exception);
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
