package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.LogicSimulationMod;
import com.foreverspark.logicsim.block.CircuitBlockEntity;
import com.foreverspark.logicsim.block.DisplayBlock;
import com.foreverspark.logicsim.block.DisplayBlockEntity;
import com.foreverspark.logicsim.client.render.DisplayResetEdgeTracker;
import com.foreverspark.logicsim.client.render.RandomDisplayNetworkFastPath;
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
 * Bulk worker for physical DISPLAY boards with a real wired RESET input.
 *
 * <p>RESET is a command strobe, not part of the RANDOM/X/Y/COLOR data dependency graph. The packed RANDOM engine can
 * therefore remain active while RESET is dynamic. This mixin monitors the real compiled RESET signal and publishes one
 * CLEAR command on each LOW->HIGH transition, while the 48-lane RANDOM trigger DAG continues through 64K-edge chunks.
 * Holding RESET high never repeats CLEAR; dropping it low re-arms the next rising edge.</p>
 */
@Mixin(value = CircuitBlockEntity.class, priority = 1300)
public abstract class CircuitBlockStaticResetRandomDisplayMixin {
    @Unique private static final long LOGIC_GENERIC_EDGE_CHUNK = 4_096L;
    @Unique private static final long LOGIC_NETWORK_EDGE_CHUNK = 65_536L;

    @Shadow private volatile CircuitProgramRuntime runtime;

    @Unique private static final Method logic$configureRealtimeWallMethod = logic$findConfigureRealtimeWallMethod();

    @Unique private volatile RandomDisplayNetworkFastPath.Plan logic$resetAwarePlan;
    @Unique private volatile RealtimeDisplaySurface.Surface logic$resetAwareSurface;
    @Unique private volatile DisplayResetEdgeTracker logic$resetTracker;
    @Unique private volatile int logic$resetAwareDeviceIndex = -1;
    @Unique private String logic$lastResetAwareSignature = "";

    @Inject(method = "refreshExternalDeviceTargets", at = @At("TAIL"))
    private void logic$refreshResetAwareNetwork(CallbackInfo ci) {
        CircuitBlockEntity self = (CircuitBlockEntity) (Object) this;
        CircuitProgramRuntime current = runtime;
        Level level = self.getLevel();
        if (current == null || level == null || level.isClientSide()) {
            logic$clearResetAwarePlan(true);
            logic$log(self, "inactive:runtime-or-level");
            return;
        }

        // The older one-group specialization remains preferred when it can prove the whole boundary itself.
        for (int index = 0; index < current.externalDeviceCount(); index++) {
            if (current.directRandomDeviceDisplayBatchEligible(index)) {
                logic$clearResetAwarePlan(true);
                logic$log(self, "inactive:direct-device-fast-path");
                return;
            }
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

            RandomDisplayNetworkFastPath.Plan existing = logic$resetAwarePlan;
            DisplayResetEdgeTracker existingTracker = logic$resetTracker;
            if (existing != null
                    && existingTracker != null
                    && existing.matches(current, index, surface.logicalWidth(), surface.logicalHeight())) {
                logic$resetAwareSurface = surface;
                logic$resetAwareDeviceIndex = index;
                logic$log(self,
                        "active:device=" + index
                                + ":groups=" + existing.triggerGroupCount()
                                + ":lanes=" + existing.randomLaneCount()
                                + ":resetSignal=" + existingTracker.signalId());
                return;
            }

            RandomDisplayNetworkFastPath.CompileResult result = RandomDisplayNetworkResetCompat.compile(
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

            logic$clearResetAwarePlan(true);
            logic$resetAwarePlan = result.plan();
            logic$resetAwareSurface = surface;
            logic$resetAwareDeviceIndex = index;
            logic$resetTracker = new DisplayResetEdgeTracker(current, resetSignalId);
            logic$log(self,
                    "active:device=" + index
                            + ":groups=" + result.plan().triggerGroupCount()
                            + ":lanes=" + result.plan().randomLaneCount()
                            + ":resetSignal=" + resetSignalId);
            return;
        }

        logic$clearResetAwarePlan(true);
        logic$log(self, "inactive:" + lastReason);
    }

    @WrapOperation(
            method = "runClockWorkerSlice",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/foreverspark/logicsim/interconnect/CircuitProgramRuntime;advanceClocksNanos(JJLjava/lang/Runnable;)J"
            )
    )
    private long logic$advanceResetAwareNetwork(
            CircuitProgramRuntime current,
            long elapsedNanos,
            long edgeBudget,
            Runnable afterSettledEdge,
            Operation<Long> original
    ) {
        RandomDisplayNetworkFastPath.Plan plan = logic$resetAwarePlan;
        RealtimeDisplaySurface.Surface surface = logic$resetAwareSurface;
        DisplayResetEdgeTracker tracker = logic$resetTracker;
        int deviceIndex = logic$resetAwareDeviceIndex;

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
                bulkBudget = Math.min(LOGIC_NETWORK_EDGE_CHUNK, edgeBudget << 4);
            }
            return plan.advance(elapsedNanos, bulkBudget, surface::recordBatch);
        }

        if (plan != null) logic$clearResetAwarePlan(true);
        return original.call(current, elapsedNanos, edgeBudget, afterSettledEdge);
    }

    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void logic$removeResetAwareNetwork(CallbackInfo ci) {
        logic$clearResetAwarePlan(false);
        logic$lastResetAwareSignature = "";
    }

    @Unique
    private void logic$clearResetAwarePlan(boolean synchronizeFallback) {
        RandomDisplayNetworkFastPath.Plan old = logic$resetAwarePlan;
        logic$resetAwarePlan = null;
        logic$resetAwareSurface = null;
        logic$resetTracker = null;
        logic$resetAwareDeviceIndex = -1;
        if (synchronizeFallback && old != null) old.synchronizeFallback();
    }

    @Unique
    private void logic$log(CircuitBlockEntity self, String signature) {
        if (signature.equals(logic$lastResetAwareSignature)) return;
        logic$lastResetAwareSignature = signature;
        if (signature.startsWith("active:")) {
            RandomDisplayNetworkFastPath.Plan plan = logic$resetAwarePlan;
            RealtimeDisplaySurface.Surface surface = logic$resetAwareSurface;
            DisplayResetEdgeTracker tracker = logic$resetTracker;
            LogicSimulationMod.LOGGER.info(
                    "[CLOCK BULK DEVICE NETWORK RESET] circuit={} active=true reset=dynamic-edge resetSignalId={} deviceIndex={} triggerGroups={} randomLanes={} mode=compiled-random-trigger-dag maxEdgeChunk={} logical={}x{} batchPublication=true",
                    self.getBlockPos(),
                    tracker == null ? -1 : tracker.signalId(),
                    logic$resetAwareDeviceIndex,
                    plan == null ? 0 : plan.triggerGroupCount(),
                    plan == null ? 0 : plan.randomLaneCount(),
                    LOGIC_NETWORK_EDGE_CHUNK,
                    surface == null ? 0 : surface.logicalWidth(),
                    surface == null ? 0 : surface.logicalHeight()
            );
        } else {
            String reason = signature.startsWith("inactive:") ? signature.substring("inactive:".length()) : signature;
            LogicSimulationMod.LOGGER.info(
                    "[CLOCK BULK DEVICE NETWORK RESET] circuit={} active=false reason={}",
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
        RealtimeDisplaySurface.TileView existing = RealtimeDisplaySurface.tileView(displayPos);
        if (existing != null) return existing.surface();

        BlockState state = level.getBlockState(displayPos);
        if (!(state.getBlock() instanceof DisplayBlock)) return null;
        DisplayBlockEntity.WallInfo info = DisplayBlockEntity.wallInfo(level, displayPos, state);
        if (info == null || info.pixelWidth() <= 0 || info.pixelHeight() <= 0) return null;
        try {
            Object value = logic$configureRealtimeWallMethod.invoke(null, level, displayPos, state, info.pixelsPerTile());
            return value instanceof RealtimeDisplaySurface.Surface surface ? surface : null;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not map reset-aware RANDOM DISPLAY to realtime surface", exception);
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
