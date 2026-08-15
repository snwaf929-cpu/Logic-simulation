package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.CircuitEditorScreen;
import com.foreverspark.logicsim.client.screen.v2.CanvasPhase2ConfigAccess;
import com.foreverspark.logicsim.client.screen.v2.EditorPinSelectionAccess;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Phase 2 editor shortcuts: Enter batch-connects selected pins; W opens exact width/value configuration. */
@Mixin(value = CircuitEditorScreen.class, priority = 2050)
public abstract class CircuitEditorPhase2ShortcutsMixin {
    @Shadow private CircuitCanvasWidget canvas;

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void logic$phase2Shortcuts(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (canvas == null || !canvas.active) return;
        boolean ctrl = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean alt = (event.modifiers() & GLFW.GLFW_MOD_ALT) != 0;

        if (!ctrl && !alt && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
            EditorPinSelectionAccess pins = (EditorPinSelectionAccess)(Object)canvas;
            if (pins.logic$hasPinSelection()) {
                pins.logic$batchConnectSelectedPins();
                cir.setReturnValue(true);
                return;
            }
        }

        if (!ctrl && !alt && event.key() == GLFW.GLFW_KEY_W) {
            CanvasPhase2ConfigAccess config = (CanvasPhase2ConfigAccess)(Object)canvas;
            if (config.logic$configureSelected((CircuitEditorScreen)(Object)this)) {
                cir.setReturnValue(true);
            }
        }
    }
}
