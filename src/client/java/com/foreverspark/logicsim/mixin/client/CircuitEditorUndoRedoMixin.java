package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.CircuitEditorScreen;
import com.foreverspark.logicsim.client.screen.v2.EditorHistoryAccess;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ctrl+Z / Ctrl+Y / Ctrl+Shift+Z without interfering with modal text editing. */
@Mixin(value = CircuitEditorScreen.class, priority = 2000)
public abstract class CircuitEditorUndoRedoMixin {
    @Shadow private CircuitCanvasWidget canvas;

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void logic$historyShortcuts(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (canvas == null || !canvas.active) return;
        boolean ctrl = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean shift = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        if (!ctrl) return;

        EditorHistoryAccess history = (EditorHistoryAccess)(Object)canvas;
        if (event.key() == GLFW.GLFW_KEY_Z && shift) {
            history.logic$redo();
            cir.setReturnValue(true);
            return;
        }
        if (event.key() == GLFW.GLFW_KEY_Z) {
            history.logic$undo();
            cir.setReturnValue(true);
            return;
        }
        if (event.key() == GLFW.GLFW_KEY_Y) {
            history.logic$redo();
            cir.setReturnValue(true);
        }
    }
}
