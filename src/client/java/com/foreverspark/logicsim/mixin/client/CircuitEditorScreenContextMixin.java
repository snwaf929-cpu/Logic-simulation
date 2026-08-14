package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitEditorScreen;
import com.foreverspark.logicsim.client.screen.EditorScreenContext;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CircuitEditorScreen.class)
public abstract class CircuitEditorScreenContextMixin {
    @Inject(method = "added", at = @At("HEAD"))
    private void logic$rememberEditorScreen(CallbackInfo ci) {
        EditorScreenContext.set((Screen)(Object)this);
    }
}
