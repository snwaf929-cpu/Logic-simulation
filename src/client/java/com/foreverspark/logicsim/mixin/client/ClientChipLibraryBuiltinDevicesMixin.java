package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.chip.ClientChipLibrary;
import com.foreverspark.logicsim.client.device.BuiltinDevices;
import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.ChipVisualSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(ClientChipLibrary.class)
public abstract class ClientChipLibraryBuiltinDevicesMixin {
    @Inject(method = "find", at = @At("HEAD"), cancellable = true)
    private void logic$findBuiltin(String name, CallbackInfoReturnable<ChipDefinition> cir) {
        ChipDefinition definition = BuiltinDevices.find(name);
        if (definition != null) cir.setReturnValue(definition);
    }

    @Inject(method = "load", at = @At("HEAD"), cancellable = true)
    private void logic$loadBuiltin(String name, CallbackInfoReturnable<ChipDefinition> cir) {
        ChipDefinition definition = BuiltinDevices.find(name);
        if (definition != null) cir.setReturnValue(definition);
    }

    @Inject(method = "exists", at = @At("HEAD"), cancellable = true)
    private void logic$existsBuiltin(String name, CallbackInfoReturnable<Boolean> cir) {
        if (BuiltinDevices.find(name) != null) cir.setReturnValue(true);
    }

    @Inject(method = "names", at = @At("RETURN"), cancellable = true)
    private void logic$appendBuiltins(CallbackInfoReturnable<List<String>> cir) {
        List<String> result = new ArrayList<>(cir.getReturnValue());
        if (!result.contains(BuiltinDevices.DISPLAY)) result.add(0, BuiltinDevices.DISPLAY);
        cir.setReturnValue(List.copyOf(result));
    }

    @Inject(method = "chipColor", at = @At("HEAD"), cancellable = true)
    private void logic$builtinColor(String name, CallbackInfoReturnable<Integer> cir) {
        if (BuiltinDevices.find(name) != null) cir.setReturnValue(0xFF34495E);
    }

    @Inject(method = "chipVisual", at = @At("HEAD"), cancellable = true)
    private void logic$builtinVisual(String name, CallbackInfoReturnable<ChipVisualSettings> cir) {
        ChipDefinition definition = BuiltinDevices.find(name);
        if (definition != null) cir.setReturnValue(definition.visual);
    }
}
