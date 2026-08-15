package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.LogicSimulationMod;
import com.foreverspark.logicsim.block.CircuitBlockEntity;
import com.foreverspark.logicsim.block.DisplayBlock;
import com.foreverspark.logicsim.block.DisplayBlockEntity;
import com.foreverspark.logicsim.client.render.DisplayResetEdgeTracker;
import com.foreverspark.logicsim.client.render.IndependentRandomDisplayFastPath;
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
 * Highest-priority physical DISPLAY worker specialization for one CLOCK-triggered RANDOM group plus independent
 * external RANDOM trigger groups. It removes the generic RANDOM-DAG event queue from the MHz loop entirely.
 */
@Mixin(value = CircuitBlockEntity.class, priority = 1400)
public abstract class CircuitBlockIndependentRandomDisplayMixin {
    @Unique private static final long LOGIC_GENERIC_EDGE_CHUNK = 4_096L;
    /** 131K edges = 65K rising samples; large enough to amortize worker bookkeeping without long monitor stalls. */
    @Unique private static final long LOGIC_INDEPENDENT_EDGE_CHUNK = 131_072L;

    @Shadow private volatile CircuitProgramRuntime runtime;

    @Unique private static final Method logic$configureRealtimeWallMethod = logic$findConfigureRealtimeWallMethod();

    @Unique private volatile IndependentRandomDisplayFastPath.Plan logic$plan;
    @Unique private volatile RealtimeDisplaySurface.Surface logic$surface;
    @Unique private volatile DisplayResetEdgeTracker logic$resetTracker;
    @Unique private volatile int logic$deviceIndex = -1;
    @Unique private String logic$lastSignature = "";

    @Inject(method = "refreshExternalDeviceTargets", at = @At("TAIL"))
    private void logic$refreshIndependentRandomDisplay(CallbackInfo ci) {
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

            // Always rebuild/validate the wall geometry from the physical block entities. Never trust a cached TileView
            // here: density can change 32 -> 64 while the circuit remains programmed.
            RealtimeDisplaySurface.Surface surface = logic$surfaceForDisplay(level, displayPos);
            if (surface == null) {
                lastReason = "realtime-surface-unresolved";
                continue;
            }

            IndependentRandomDisplayFastPath.Plan existing = logic$plan;
            DisplayResetEdgeTracker existingTracker = logic$resetTracker;
            if (existing != null
                    && existingTracker != null
                    && existing.matches(current, index, surface.logicalWidth(), surface.logicalHeight())) {
                logic$surface = surface;
                logic$deviceIndex = index;
                logic$log(self, logic$activeSignature(existing, existingTracker, surface));
                return;
            }

            IndependentRandomDisplayFastPath.CompileResult result = IndependentRandomDisplayFastPath.compile(
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
            logic$log(self, logic$activeSignature(result.plan(), logic$resetTracker, surface));
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
    private long logic$advanceIndependentRandomDisplay(
            CircuitProgramRuntime current,
            long elapsedNanos,
            long edgeBudget,
            Runnable afterSettledEdge,
            Operation<Long> original
    ) {
        IndependentRandomDisplayFastPath.Plan plan = logic$plan;
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
                bulkBudget = Math.min(LOGIC_INDEPENDENT_EDGE_CHUNK, edgeBudget << 5);
            }
            return plan.advance(elapsedNanos, bulkBudget, surface::recordBatch);
        }

        if (plan != null) logic$clear(true);
        return original.call(current, elapsedNanos, edgeBudget, afterSettledEdge);
    }

    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void logic$removeIndependentRandomDisplay(CallbackInfo ci) {
        logic$clear(false);
        logic$lastSignature = "";
    }

    @Unique
    private void logic$clear(boolean synchronizeFallback) {
        IndependentRandomDisplayFastPath.Plan old = logic$plan;
        logic$plan = null;
        logic$surface = null;
        logic$resetTracker = null;
        logic$deviceIndex = -1;
        if (synchronizeFallback && old != null) old.synchronizeFallback();
    }

    @Unique
    private static String logic$activeSignature(
            IndependentRandomDisplayFastPath.Plan plan,
            DisplayResetEdgeTracker tracker,
            RealtimeDisplaySurface.Surface surface
    ) {
        return "active:device=" + plan.deviceIndex()
                + ":logical=" + surface.logicalWidth() + "x" + surface.logicalHeight()
                + ":clockLanes=" + plan.clockLaneCount()
                + ":external=" + plan.externalTriggerGroupCount()
                + ":prefilter=" + plan.coordinatePrefilterLaneCount()
                + ":pack=" + plan.boundaryPackMode()
                + ":reset=" + tracker.signalId();
    }

    @Unique
    private void logic$log(CircuitBlockEntity self, String signature) {
        if (signature.equals(logic$lastSignature)) return;
        logic$lastSignature = signature;

        if (signature.startsWith("active:")) {
            IndependentRandomDisplayFastPath.Plan plan = logic$plan;
            RealtimeDisplaySurface.Surface surface = logic$surface;
            DisplayResetEdgeTracker tracker = logic$resetTracker;
            LogicSimulationMod.LOGGER.info(
                    "[CLOCK BULK DEVICE INDEPENDENT] circuit={} active=true resetSignalId={} deviceIndex={} randomLanes={} clockRandomLanes={} externalTriggerGroups={} mode=independent-trigger-hotloop-v3 maxEdgeChunk={} logical={}x{} backing={}x{} coordinatePrefilter={} coordinateRejectLanes={} boundaryPack={} batchPublication=true",
                    self.getBlockPos(),
                    tracker == null ? -1 : tracker.signalId(),
                    logic$deviceIndex,
                    plan == null ? 0 : plan.randomLaneCount(),
                    plan == null ? 0 : plan.clockLaneCount(),
                    plan == null ? 0 : plan.externalTriggerGroupCount(),
                    LOGIC_INDEPENDENT_EDGE_CHUNK,
                    surface == null ? 0 : surface.logicalWidth(),
                    surface == null ? 0 : surface.logicalHeight(),
                    surface == null ? 0 : surface.backingWidth(),
                    surface == null ? 0 : surface.backingHeight(),
                    plan != null && plan.coordinatePrefilterEnabled(),
                    plan == null ? 0 : plan.coordinatePrefilterLaneCount(),
                    plan == null ? "none" : plan.boundaryPackMode()
            );
        } else {
            String reason = signature.startsWith("inactive:") ? signature.substring("inactive:".length()) : signature;
            LogicSimulationMod.LOGGER.info(
                    "[CLOCK BULK DEVICE INDEPENDENT] circuit={} active=false reason={}",
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
            throw new IllegalStateException("Could not map independent RANDOM DISPLAY to realtime surface", exception);
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
