package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitEditorScreen;
import com.foreverspark.logicsim.client.screen.ComponentLibraryWidget;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Routes Ctrl+F and typed search keys into the Phase 6 library search field without stealing canvas shortcuts otherwise. */
@Mixin(value = CircuitEditorScreen.class, priority = 2160)
public abstract class CircuitEditorPhase6LibraryMixin {
    @Shadow private ComponentLibraryWidget componentLibrary;

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void logic$phase6LibraryKeys(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (componentLibrary == null || !componentLibrary.active) return;
        boolean ctrl = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        if (ctrl && event.key() == GLFW.GLFW_KEY_F) {
            componentLibrary.beginSearch();
            cir.setReturnValue(true);
            return;
        }
        if (componentLibrary.searchFocused()) {
            componentLibrary.keyPressed(event);
            cir.setReturnValue(true);
        }
    }
}
