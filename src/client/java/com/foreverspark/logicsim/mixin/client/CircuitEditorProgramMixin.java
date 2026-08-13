package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.ClientProgramUploader;
import com.foreverspark.logicsim.client.chip.ClientChipLibrary;
import com.foreverspark.logicsim.client.screen.CircuitEditorScreen;
import com.foreverspark.logicsim.platform.ClientEditorBridge;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;

@Mixin(CircuitEditorScreen.class)
public abstract class CircuitEditorProgramMixin {
    @Shadow @Final private ClientChipLibrary library;
    @Shadow private String currentChipName;
    @Shadow private void setStatus(String value) { throw new AssertionError(); }

    @Inject(method = "applySave", at = @At("RETURN"))
    private void logic$programWorldBlock(CallbackInfo ci) {
        BlockPos target = ClientEditorBridge.activeCircuitPos();
        if (target == null || currentChipName == null || currentChipName.isBlank()) return;
        try {
            ClientProgramUploader.upload(target, currentChipName, library);
            setStatus("Saved " + currentChipName + " and sent it to this Circuit Block");
        } catch (IOException | RuntimeException error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            setStatus("Saved chip, but Circuit Block programming failed: " + message);
        }
    }
}
