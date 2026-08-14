package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CanvasConfigAccess;
import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.CircuitEditorScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft re-runs Screen.init() after returning from a child configuration screen. Preserve the
 * live editor session rather than silently resetting pan/zoom/selection, and let E edit selected
 * CLOCK/RANDOM sources before falling back to wire-route editing.
 */
@Mixin(CircuitEditorScreen.class)
public abstract class CircuitEditorModalStateMixin {
    @Shadow private CircuitCanvasWidget canvas;

    @Unique
    private CanvasConfigAccess.CanvasSessionState logic$pendingCanvasState;

    @Unique
    private static CanvasConfigAccess logic$access(CircuitCanvasWidget canvas) {
        // CircuitCanvasSourceConfigMixin adds CanvasConfigAccess at runtime. The (Object) hop is
        // required because CircuitCanvasWidget is final, so javac otherwise rejects the cast.
        return (CanvasConfigAccess)(Object)canvas;
    }

    @Inject(method = "init", at = @At("HEAD"))
    private void logic$captureCanvasState(CallbackInfo ci) {
        logic$pendingCanvasState = canvas == null ? null : logic$access(canvas).logic$captureSessionState();
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void logic$restoreCanvasState(CallbackInfo ci) {
        if (logic$pendingCanvasState != null && canvas != null) {
            logic$access(canvas).logic$restoreSessionState(logic$pendingCanvasState);
        }
        logic$pendingCanvasState = null;
    }

    @Redirect(
            method = "keyPressed",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/foreverspark/logicsim/client/screen/CircuitCanvasWidget;toggleWireEditMode()Z"
            )
    )
    private boolean logic$editSelectedSourcesBeforeWireMode(CircuitCanvasWidget target) {
        if (logic$access(target).logic$editSelectedSources((CircuitEditorScreen)(Object)this)) {
            return true;
        }
        return target.toggleWireEditMode();
    }
}
