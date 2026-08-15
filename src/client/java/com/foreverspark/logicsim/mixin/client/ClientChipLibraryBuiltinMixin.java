package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.chip.ClientChipLibrary;
import com.foreverspark.logicsim.client.device.BuiltinDevices;
import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.ChipVisualSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes built-in device definitions behave like read-only reusable chips without writing them to disk. */
@Mixin(value = ClientChipLibrary.class, priority = 1300)
public abstract class ClientChipLibraryBuiltinMixin {
    @Inject(method = "find", at = @At("HEAD"), cancellable = true)
    private void logic$findBuiltin(String name, CallbackInfoReturnable<ChipDefinition> cir) {
        ChipDefinition definition = BuiltinDevices.find(name);
        if (definition != null) cir.setReturnValue(definition);
    }

    @Inject(method = "load", at = @At("HEAD"), cancellable = true)
    private void logic$loadBuiltin(String name, CallbackInfoReturnable<ChipDefinition> cir) {
        ChipDefinition definition = BuiltinDevices.copy(name);
        if (definition != null) cir.setReturnValue(definition);
    }

    @Inject(method = "exists", at = @At("HEAD"), cancellable = true)
    private void logic$builtinExists(String name, CallbackInfoReturnable<Boolean> cir) {
        if (BuiltinDevices.isBuiltin(name)) cir.setReturnValue(true);
    }

    @Inject(method = "chipColor", at = @At("HEAD"), cancellable = true)
    private void logic$builtinColor(String name, CallbackInfoReturnable<Integer> cir) {
        if (BuiltinDevices.isBuiltin(name)) cir.setReturnValue(BuiltinDevices.color(name));
    }

    @Inject(method = "chipVisual", at = @At("HEAD"), cancellable = true)
    private void logic$builtinVisual(String name, CallbackInfoReturnable<ChipVisualSettings> cir) {
        if (BuiltinDevices.isBuiltin(name)) cir.setReturnValue(BuiltinDevices.visual(name));
    }
}
