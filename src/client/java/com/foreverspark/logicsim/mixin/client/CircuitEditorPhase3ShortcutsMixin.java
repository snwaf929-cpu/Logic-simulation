package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.CircuitEditorScreen;
import com.foreverspark.logicsim.client.screen.v2.PcbLayerAccess;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Phase 3 PCB shortcuts: B flips side, L assigns a trace to the visible layer, V toggles a via. */
@Mixin(value = CircuitEditorScreen.class, priority = 2060)
public abstract class CircuitEditorPhase3ShortcutsMixin {
    @Shadow private CircuitCanvasWidget canvas;

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void logic$phase3Shortcuts(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (canvas == null || !canvas.active) return;
        boolean ctrl = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean alt = (event.modifiers() & GLFW.GLFW_MOD_ALT) != 0;
        if (ctrl || alt) return;

        PcbLayerAccess pcb = (PcbLayerAccess)(Object)canvas;
        if (event.key() == GLFW.GLFW_KEY_B) {
            pcb.logic$flipPcbBoardSide();
            cir.setReturnValue(true);
            return;
        }
        if (event.key() == GLFW.GLFW_KEY_L) {
            if (pcb.logic$assignSelectedWireToCurrentLayer()) cir.setReturnValue(true);
            return;
        }
        if (event.key() == GLFW.GLFW_KEY_V) {
            if (pcb.logic$toggleViaOnSelectedWire()) cir.setReturnValue(true);
        }
    }
}
