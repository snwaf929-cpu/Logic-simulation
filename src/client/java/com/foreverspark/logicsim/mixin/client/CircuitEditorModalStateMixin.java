package com.foreverspark.logicsim.mixin.client;

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

    @Inject(method = "init", at = @At("HEAD"))
    private void logic$captureCanvasState(CallbackInfo ci) {
        if (canvas instanceof CanvasConfigAccess access) {
            logic$pendingCanvasState = access.logic$captureSessionState();
        } else {
            logic$pendingCanvasState = null;
        }
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void logic$restoreCanvasState(CallbackInfo ci) {
        if (logic$pendingCanvasState != null && canvas instanceof CanvasConfigAccess access) {
            access.logic$restoreSessionState(logic$pendingCanvasState);
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
        if (target instanceof CanvasConfigAccess access
                && access.logic$editSelectedSources((CircuitEditorScreen)(Object)this)) {
            return true;
        }
        return target.toggleWireEditMode();
    }
}
