package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.LogicSimulationMod;
import com.foreverspark.logicsim.block.CircuitBlockEntity;
import com.foreverspark.logicsim.block.DisplayBlock;
import com.foreverspark.logicsim.block.DisplayBlockEntity;
import com.foreverspark.logicsim.client.render.RandomDisplayNetworkFastPath;
import com.foreverspark.logicsim.client.render.RealtimeDisplaySurface;
import com.foreverspark.logicsim.editor.model.ExternalDeviceType;
import com.foreverspark.logicsim.interconnect.CircuitProgramRuntime;
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Physical DISPLAY extension for multi-trigger, zero-NAND RANDOM networks.
 *
 * <p>The original realtime mixin remains the preferred one-group path. This mixin activates only when that direct
 * plan is unavailable and RandomDisplayNetworkFastPath can prove the wider RANDOM trigger graph is closed and safe.
 * It wraps the same clock-worker invocation, consumes much larger edge chunks, and publishes packed pixel batches
 * directly to the integrated-client surface.</p>
 */
@Mixin(value = CircuitBlockEntity.class, priority = 1200)
public abstract class CircuitBlockRandomDisplayNetworkMixin {
    @Unique private static final long LOGIC_GENERIC_EDGE_CHUNK = 4_096L;
    @Unique private static final long LOGIC_NETWORK_EDGE_CHUNK = 65_536L;

    @Shadow private volatile CircuitProgramRuntime runtime;

    @Unique private static final Field logic$externalDeviceTargetsField = logic$findField("externalDeviceTargets");
    @Unique private static final Method logic$configureRealtimeWallMethod = logic$findConfigureRealtimeWallMethod();

    @Unique private volatile RandomDisplayNetworkFastPath.Plan logic$networkPlan;
    @Unique private volatile RealtimeDisplaySurface.Surface logic$networkSurface;
    @Unique private volatile int logic$networkDeviceIndex = -1;
    @Unique private String logic$lastNetworkSignature = "";

    @Inject(method = "refreshExternalDeviceTargets", at = @At("TAIL"))
    private void logic$refreshRandomDisplayNetwork(CallbackInfo ci) {
        CircuitBlockEntity self = (CircuitBlockEntity) (Object) this;
        CircuitProgramRuntime current = runtime;
        if (current == null) {
            logic$clearNetworkPlan(true);
            logic$logNetwork(self, "inactive:runtime-null");
            return;
        }

        // Keep the older one-trigger packed engine as the fastest possible path.
        for (int index = 0; index < current.externalDeviceCount(); index++) {
            if (current.directRandomDeviceDisplayBatchEligible(index)) {
                logic$clearNetworkPlan(true);
                logic$logNetwork(self, "inactive:direct-device-fast-path");
                return;
            }
        }

        Object[] targets = logic$fieldArray(logic$externalDeviceTargetsField, self);
        int count = Math.min(targets.length, current.externalDeviceCount());
        String lastReason = "display-target-unresolved";
        for (int index = 0; index < count; index++) {
            if (current.externalDeviceType(index) != ExternalDeviceType.DISPLAY) continue;
            Object externalTarget = targets[index];
            if (externalTarget == null) {
                lastReason = "physical-display-disconnected";
                continue;
            }

            Object displayTarget = logic$recordAccessor(externalTarget, "displayTarget");
            Object devicePosValue = logic$recordAccessor(externalTarget, "devicePos");
            if (displayTarget == null || !(devicePosValue instanceof BlockPos devicePos)) {
                lastReason = "display-target-invalid";
                continue;
            }

            RealtimeDisplaySurface.Surface surface = logic$surfaceForDisplay(self, devicePos);
            if (surface == null) {
                lastReason = "realtime-surface-unresolved";
                continue;
            }

            RandomDisplayNetworkFastPath.Plan existing = logic$networkPlan;
            if (existing != null && existing.matches(current, index, surface.logicalWidth(), surface.logicalHeight())) {
                logic$networkSurface = surface;
                logic$networkDeviceIndex = index;
                logic$logNetwork(self,
                        "active:device=" + index
                                + ":groups=" + existing.triggerGroupCount()
                                + ":lanes=" + existing.randomLaneCount()
                                + ":size=" + surface.logicalWidth() + "x" + surface.logicalHeight());
                return;
            }

            RandomDisplayNetworkFastPath.CompileResult result = RandomDisplayNetworkFastPath.compile(
                    current, index, surface.logicalWidth(), surface.logicalHeight()
            );
            if (!result.active()) {
                lastReason = result.reason();
                continue;
            }

            logic$clearNetworkPlan(true);
            logic$networkPlan = result.plan();
            logic$networkSurface = surface;
            logic$networkDeviceIndex = index;
            logic$logNetwork(self,
                    "active:device=" + index
                            + ":groups=" + result.plan().triggerGroupCount()
                            + ":lanes=" + result.plan().randomLaneCount()
                            + ":size=" + surface.logicalWidth() + "x" + surface.logicalHeight());
            return;
        }

        logic$clearNetworkPlan(true);
        logic$logNetwork(self, "inactive:" + lastReason);
    }

    @WrapOperation(
            method = "runClockWorkerSlice",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/foreverspark/logicsim/interconnect/CircuitProgramRuntime;advanceClocksNanos(JJLjava/lang/Runnable;)J"
            )
    )
    private long logic$advanceRandomDisplayNetwork(
            CircuitProgramRuntime current,
            long elapsedNanos,
            long edgeBudget,
            Runnable afterSettledEdge,
            Operation<Long> original
    ) {
        RandomDisplayNetworkFastPath.Plan plan = logic$networkPlan;
        RealtimeDisplaySurface.Surface surface = logic$networkSurface;
        int deviceIndex = logic$networkDeviceIndex;

        if (plan != null
                && surface != null
                && deviceIndex >= 0
                && current == runtime
                && plan.matches(current, deviceIndex, surface.logicalWidth(), surface.logicalHeight())) {
            long bulkBudget = edgeBudget;
            if (edgeBudget >= LOGIC_GENERIC_EDGE_CHUNK) {
                bulkBudget = Math.min(LOGIC_NETWORK_EDGE_CHUNK, edgeBudget << 4);
            }
            return plan.advance(elapsedNanos, bulkBudget, surface::recordBatch);
        }

        if (plan != null) logic$clearNetworkPlan(true);
        return original.call(current, elapsedNanos, edgeBudget, afterSettledEdge);
    }

    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void logic$removeRandomDisplayNetwork(CallbackInfo ci) {
        logic$clearNetworkPlan(false);
        logic$lastNetworkSignature = "";
    }

    @Unique
    private void logic$clearNetworkPlan(boolean synchronizeFallback) {
        RandomDisplayNetworkFastPath.Plan old = logic$networkPlan;
        logic$networkPlan = null;
        logic$networkSurface = null;
        logic$networkDeviceIndex = -1;
        if (synchronizeFallback && old != null) old.synchronizeFallback();
    }

    @Unique
    private void logic$logNetwork(CircuitBlockEntity self, String signature) {
        if (signature.equals(logic$lastNetworkSignature)) return;
        logic$lastNetworkSignature = signature;
        if (signature.startsWith("active:")) {
            RandomDisplayNetworkFastPath.Plan plan = logic$networkPlan;
            RealtimeDisplaySurface.Surface surface = logic$networkSurface;
            LogicSimulationMod.LOGGER.info(
                    "[CLOCK BULK DEVICE NETWORK] circuit={} active=true deviceIndex={} triggerGroups={} randomLanes={} mode=compiled-random-trigger-dag maxEdgeChunk={} logical={}x{} batchPublication=true",
                    self.getBlockPos(),
                    logic$networkDeviceIndex,
                    plan == null ? 0 : plan.triggerGroupCount(),
                    plan == null ? 0 : plan.randomLaneCount(),
                    LOGIC_NETWORK_EDGE_CHUNK,
                    surface == null ? 0 : surface.logicalWidth(),
                    surface == null ? 0 : surface.logicalHeight()
            );
        } else {
            LogicSimulationMod.LOGGER.info(
                    "[CLOCK BULK DEVICE NETWORK] circuit={} active=false mode=normal-edge-engine reason={}",
                    self.getBlockPos(), signature.substring("inactive:".length())
            );
        }
    }

    @Unique
    private static Field logic$findField(String name) {
        try {
            Field field = CircuitBlockEntity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not access CircuitBlockEntity " + name, exception);
        }
    }

    @Unique
    private static Object[] logic$fieldArray(Field field, CircuitBlockEntity self) {
        try {
            Object value = field.get(self);
            return value instanceof Object[] array ? array : new Object[0];
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not read CircuitBlockEntity external device targets", exception);
        }
    }

    @Unique
    private static Object logic$recordAccessor(Object target, String accessor) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getDeclaredMethod(accessor);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not read physical display target " + accessor, exception);
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

    @Unique
    private static RealtimeDisplaySurface.Surface logic$surfaceForDisplay(CircuitBlockEntity circuit, BlockPos displayPos) {
        Level level = circuit.getLevel();
        if (level == null || level.isClientSide() || displayPos == null) return null;
        BlockState state = level.getBlockState(displayPos);
        if (!(state.getBlock() instanceof DisplayBlock)) return null;
        DisplayBlockEntity.WallInfo info = DisplayBlockEntity.wallInfo(level, displayPos, state);
        if (info == null || info.pixelWidth() <= 0 || info.pixelHeight() <= 0) return null;
        try {
            Object value = logic$configureRealtimeWallMethod.invoke(null, level, displayPos, state, info.pixelsPerTile());
            return value instanceof RealtimeDisplaySurface.Surface surface ? surface : null;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not map RANDOM-network DISPLAY to realtime surface", exception);
        }
    }
}
