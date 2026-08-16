package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.CircuitEditorScreen;
import com.foreverspark.logicsim.client.screen.v2.CustomChipReplacementAccess;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ctrl+R safely replaces the selected reusable CHIP with the CHIP currently chosen in the library. */
@Mixin(value = CircuitEditorScreen.class, priority = 2190)
public abstract class CircuitEditorCustomChipReplacementShortcutMixin {
    @Shadow private CircuitCanvasWidget canvas;

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void logic$replaceChipShortcut(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (canvas == null || !canvas.active) return;
        boolean ctrl = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean shift = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        boolean alt = (event.modifiers() & GLFW.GLFW_MOD_ALT) != 0;
        if (ctrl && !shift && !alt && event.key() == GLFW.GLFW_KEY_R) {
            if (((CustomChipReplacementAccess)(Object)canvas).logic$replaceSelectedWithPlacementChip()) cir.setReturnValue(true);
        }
    }
}
