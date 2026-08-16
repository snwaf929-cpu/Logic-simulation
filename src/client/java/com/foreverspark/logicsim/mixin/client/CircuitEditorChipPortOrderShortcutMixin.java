package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.CircuitEditorScreen;
import com.foreverspark.logicsim.client.screen.v2.ChipPortOrderAccess;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ctrl+Shift+Up/Down explicitly reorders reusable CHIP INPUT/OUTPUT terminals. */
@Mixin(value = CircuitEditorScreen.class, priority = 2180)
public abstract class CircuitEditorChipPortOrderShortcutMixin {
    @Shadow private CircuitCanvasWidget canvas;

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void logic$chipPortOrderKeys(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (canvas == null || !canvas.active) return;
        boolean ctrl = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean shift = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        boolean alt = (event.modifiers() & GLFW.GLFW_MOD_ALT) != 0;
        if (!ctrl || !shift || alt) return;

        int direction = switch (event.key()) {
            case GLFW.GLFW_KEY_UP -> -1;
            case GLFW.GLFW_KEY_DOWN -> 1;
            default -> 0;
        };
        if (direction == 0) return;
        if (((ChipPortOrderAccess)(Object)canvas).logic$moveSelectedChipPort(direction)) cir.setReturnValue(true);
    }
}
