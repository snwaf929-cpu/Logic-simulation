package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.ClientBoardNetworking;
import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.CircuitEditorScreen;
import com.foreverspark.logicsim.client.screen.v2.ExternalDeviceSync;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.platform.ClientEditorBridge;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies server-side cable discovery after the physical BOARD has been restored. */
@Mixin(value = CircuitEditorScreen.class, priority = 1200)
public abstract class CircuitEditorPhase5DevicesMixin {
    @Shadow private CircuitCanvasWidget canvas;
    @Shadow private void setStatus(String status) { throw new AssertionError(); }

    @Inject(method = "init", at = @At("RETURN"))
    private void logic$syncPhysicalDevices(CallbackInfo ci) {
        if (canvas == null) return;
        BlockPos pos = ClientEditorBridge.activeCircuitPos();
        if (!ClientBoardNetworking.hasPendingDeviceSnapshot(pos)) return;
        CircuitDocument board = canvas.document();
        ExternalDeviceSync.Result result = ExternalDeviceSync.reconcile(board, ClientBoardNetworking.consumePendingDevices(pos));
        canvas.setDocument(board, null);
        setStatus("Physical devices: " + result.connected() + " connected"
                + (result.created() > 0 ? ", " + result.created() + " discovered" : "")
                + (result.unknown() > 0 ? ", " + result.unknown() + " UNKNOWN (schematic retained)" : ""));
    }
}
