package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.LinkedHashSet;
import java.util.function.Consumer;

/** A DEVICE represents one physical identity, so copy/duplicate must not manufacture another instance. */
@Mixin(value = CircuitCanvasWidget.class, priority = 2800)
public abstract class CircuitCanvasPhase5DeviceGuardMixin {
    @Shadow private CircuitDocument document;
    @Shadow @Final private LinkedHashSet<Integer> selectedNodeIds;
    @Shadow @Final private Consumer<String> status;

    @Inject(method = "copySelection", at = @At("HEAD"), cancellable = true)
    private void logic$blockDeviceCopy(CallbackInfoReturnable<Boolean> cir) {
        if (!logic$containsDevice()) return;
        status.accept("Physical DEVICE nodes cannot be copied — reconnect/discover the real world device instead");
        cir.setReturnValue(false);
    }

    @Inject(method = "duplicateSelection", at = @At("HEAD"), cancellable = true)
    private void logic$blockDeviceDuplicate(CallbackInfoReturnable<Boolean> cir) {
        if (!logic$containsDevice()) return;
        status.accept("Physical DEVICE nodes cannot be duplicated — each node owns one stable world identity");
        cir.setReturnValue(false);
    }

    private boolean logic$containsDevice() {
        for (Integer id : selectedNodeIds) {
            if (id == null) continue;
            try {
                EditorNode node = document.node(id);
                if (node.isExternalDevice()) return true;
            } catch (RuntimeException ignored) {}
        }
        return false;
    }
}
