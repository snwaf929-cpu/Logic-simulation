package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitEditorScreen;
import com.foreverspark.logicsim.platform.ClientEditorBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ctrl+S is now strictly the reusable-module save action.
 * A physical Circuit Block runs its persistent BOARD, which is installed by
 * CircuitEditorBoardPersistenceMixin, so we must not race it by uploading only the named sub-chip.
 */
@Mixin(CircuitEditorScreen.class)
public abstract class CircuitEditorProgramMixin {
    @Shadow private void setStatus(String value) { throw new AssertionError(); }

    @Inject(method = "applySave", at = @At("RETURN"))
    private void logic$explainBoardProgramming(CallbackInfo ci) {
        if (ClientEditorBridge.activeCircuitPos() != null) {
            setStatus("Reusable module saved. This Circuit Block runs the complete BOARD; the board is being checkpointed now.");
        }
    }
}
