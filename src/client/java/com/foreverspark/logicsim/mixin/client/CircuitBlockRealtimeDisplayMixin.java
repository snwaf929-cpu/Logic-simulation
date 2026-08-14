package com.foreverspark.logicsim.mixin.client;

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
    @Shadow private volatile CircuitProgramRuntime runtime;

    @Unique private static final Field logic$displayTargetsField = logic$findDisplayTargetsField();
    @Unique private volatile Map<Object, RealtimeDisplaySurface.Surface> logic$realtimeTargets = Map.of();

    @Inject(method = "refreshDisplayStreamPorts", at = @At("TAIL"))
    private void logic$refreshRealtimeDisplayRoutes(CallbackInfo ci) {
        CircuitBlockEntity self = (CircuitBlockEntity) (Object) this;
        RealtimeDisplaySurface.refreshRoutes(self);

        CircuitProgramRuntime current = runtime;
        if (current == null) {
            logic$realtimeTargets = Map.of();
            return;
        }

        Object[] targets = logic$displayTargets(self);
        if (targets.length == 0) {
            logic$realtimeTargets = Map.of();
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
        logic$realtimeTargets = mapped.isEmpty() ? Map.of() : Map.copyOf(mapped);
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
