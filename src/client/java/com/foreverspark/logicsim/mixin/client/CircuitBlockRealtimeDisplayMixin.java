package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.LogicSimulationMod;
import com.foreverspark.logicsim.block.CircuitBlockEntity;
import com.foreverspark.logicsim.block.DisplayBlock;
import com.foreverspark.logicsim.block.DisplayBlockEntity;
import com.foreverspark.logicsim.client.render.RealtimeDisplaySurface;
import com.foreverspark.logicsim.editor.model.PortSpec;
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
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Integrated-client display bridge.
 *
 * CircuitBlockEntity's normal DATA64 buffer is lossless and remains the authoritative path on dedicated servers.
 * In single-player the server, clock worker and renderer are in one JVM, so locally mapped Pixel Display commands can
 * bypass the server-tick framebuffer queue and update RealtimeDisplaySurface directly. Ordinary/remote outputs still
 * invoke the original methods unchanged.
 *
 * V2.1A also exposes physical DISPLAY devices as X[16], Y[16], COLOR[16], WRITE[1], RESET[1]. Those pins are packed
 * into the same internal display command only at the physical boundary. They must feed the same realtime surface as
 * legacy DATA64 or the integrated renderer can legitimately prefer a black realtime surface over a newer server-side
 * framebuffer. The two maps below therefore keep legacy output-stream targets and explicit DEVICE targets separate.
 */
@Mixin(CircuitBlockEntity.class)
public abstract class CircuitBlockRealtimeDisplayMixin {
    @Unique private static final long LOGIC_GENERIC_EDGE_CHUNK = 4_096L;
    @Unique private static final long LOGIC_BULK_EDGE_CHUNK = 65_536L;

    @Shadow private volatile CircuitProgramRuntime runtime;

    @Unique private static final Field logic$displayTargetsField = logic$findField("displayStreamTargets");
    @Unique private static final Field logic$externalDeviceTargetsField = logic$findField("externalDeviceTargets");
    @Unique private static final Method logic$configureRealtimeWallMethod = logic$findConfigureRealtimeWallMethod();

    @Unique private volatile Map<Object, RealtimeDisplaySurface.Surface> logic$realtimeTargets = Map.of();
    @Unique private volatile Map<Object, RealtimeDisplaySurface.Surface> logic$realtimeDeviceTargets = Map.of();
    @Unique private volatile RealtimeDisplaySurface.Surface logic$bulkSurface;
    @Unique private volatile int logic$bulkOutputIndex = -1;
    @Unique private int logic$lastLoggedRealtimeTargetCount = -1;
    @Unique private int logic$lastLoggedRealtimeDeviceTargetCount = -1;
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

        Object[] targets = logic$fieldArray(logic$displayTargetsField, self);
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

    /** Map explicit V2.1A physical DISPLAY bindings onto the integrated-client realtime wall. */
    @Inject(method = "refreshExternalDeviceTargets", at = @At("TAIL"))
    private void logic$refreshRealtimeDeviceDisplayRoutes(CallbackInfo ci) {
        CircuitBlockEntity self = (CircuitBlockEntity) (Object) this;
        CircuitProgramRuntime current = runtime;
        if (current == null) {
            logic$setRealtimeDeviceTargets(self, Map.of());
            return;
        }

        Object[] targets = logic$fieldArray(logic$externalDeviceTargetsField, self);
        if (targets.length == 0) {
            logic$setRealtimeDeviceTargets(self, Map.of());
            return;
        }

        Map<Object, RealtimeDisplaySurface.Surface> mapped = new HashMap<>();
        int count = Math.min(targets.length, current.externalDeviceCount());
        for (int index = 0; index < count; index++) {
            if (current.externalDeviceType(index) != com.foreverspark.logicsim.editor.model.ExternalDeviceType.DISPLAY) continue;
            Object externalTarget = targets[index];
            if (externalTarget == null) continue;

            Object displayTarget = logic$recordAccessor(externalTarget, "displayTarget");
            Object devicePosValue = logic$recordAccessor(externalTarget, "devicePos");
            if (displayTarget == null || !(devicePosValue instanceof BlockPos devicePos)) continue;

            RealtimeDisplaySurface.Surface surface = logic$surfaceForDisplay(self, devicePos);
            if (surface != null) mapped.put(displayTarget, surface);
        }
        logic$setRealtimeDeviceTargets(self, mapped.isEmpty() ? Map.of() : Map.copyOf(mapped));
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
    private void logic$setRealtimeDeviceTargets(
            CircuitBlockEntity self,
            Map<Object, RealtimeDisplaySurface.Surface> targets
    ) {
        logic$realtimeDeviceTargets = targets;
        int mappedCount = targets.size();
        if (mappedCount == logic$lastLoggedRealtimeDeviceTargetCount) return;
        logic$lastLoggedRealtimeDeviceTargetCount = mappedCount;

        RealtimeDisplaySurface.Surface first = targets.values().stream().findFirst().orElse(null);
        if (first == null) {
            LogicSimulationMod.LOGGER.info(
                    "[DISPLAY DEVICE REALTIME] circuit={} mappedDevices=0 integratedFastPath=false",
                    self.getBlockPos()
            );
        } else {
            LogicSimulationMod.LOGGER.info(
                    "[DISPLAY DEVICE REALTIME] circuit={} mappedDevices={} integratedFastPath=true logical={}x{} backing={}x{}",
                    self.getBlockPos(), mappedCount, first.logicalWidth(), first.logicalHeight(),
                    first.backingWidth(), first.backingHeight()
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

    @WrapOperation(
            method = "captureExternalDeviceInputsLocked",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/foreverspark/logicsim/block/CircuitBlockEntity$DisplayCommandBuffer;recordPixel(JIILcom/foreverspark/logicsim/block/CircuitBlockEntity$DisplayStreamTarget;)V"
            )
    )
    private void logic$routeDevicePixelRealtime(
            @Coerce Object buffer,
            long raw,
            int x,
            int y,
            @Coerce Object target,
            Operation<Void> original
    ) {
        RealtimeDisplaySurface.Surface surface = logic$realtimeDeviceTargets.get(target);
        if (surface != null) {
            surface.record(raw);
            return;
        }
        original.call(buffer, raw, x, y, target);
    }

    @WrapOperation(
            method = "captureExternalDeviceInputsLocked",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/foreverspark/logicsim/block/CircuitBlockEntity$DisplayCommandBuffer;recordClear(JLcom/foreverspark/logicsim/block/CircuitBlockEntity$DisplayStreamTarget;)V"
            )
    )
    private void logic$routeDeviceClearRealtime(
            @Coerce Object buffer,
            long raw,
            @Coerce Object target,
            Operation<Void> original
    ) {
        RealtimeDisplaySurface.Surface surface = logic$realtimeDeviceTargets.get(target);
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
        logic$realtimeDeviceTargets = Map.of();
        logic$bulkSurface = null;
        logic$bulkOutputIndex = -1;
        logic$lastLoggedRealtimeTargetCount = -1;
        logic$lastLoggedRealtimeDeviceTargetCount = -1;
        logic$lastLoggedBulkOutputIndex = Integer.MIN_VALUE;
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
            throw new IllegalStateException("Could not read CircuitBlockEntity target array", exception);
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
            throw new IllegalStateException("Could not map V2.1A DISPLAY to realtime surface", exception);
        }
    }
}
