package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.ClientBoardNetworking;
import com.foreverspark.logicsim.client.ClientProgramUploader;
import com.foreverspark.logicsim.client.chip.ClientChipLibrary;
import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.CircuitEditorScreen;
import com.foreverspark.logicsim.client.screen.EditorClockRuntime;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.platform.ClientEditorBridge;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives every physical Circuit Block its own persistent editable/running board.
 * The saved object is the root board even while the user is drilled into a nested CPU/ALU/etc chip.
 */
@Mixin(value = CircuitEditorScreen.class, priority = 1400)
public abstract class CircuitEditorBoardPersistenceMixin {
    @Shadow private CircuitCanvasWidget canvas;
    @Shadow @Final private ClientChipLibrary library;
    @Shadow private void setStatus(String status) { throw new AssertionError(); }

    @Unique private CircuitDocument logic$worldBoardRoot;
    @Unique private BlockPos logic$worldBoardPos;

    @Inject(method = "init", at = @At("RETURN"))
    private void logic$restoreWorldBoard(CallbackInfo ci) {
        if (logic$worldBoardRoot != null || canvas == null) return;
        BlockPos pos = ClientEditorBridge.activeCircuitPos();
        CircuitDocument restored = ClientBoardNetworking.consumePendingBoard(pos);
        if (restored == null) return;

        canvas.setDocument(restored, null);
        logic$worldBoardRoot = restored;
        logic$worldBoardPos = pos == null ? null : pos.immutable();
        if (restored.nodes.isEmpty()) {
            setStatus("BOARD is empty — closing the editor saves AND runs this board in the Circuit Block. Ctrl+S saves reusable modules.");
        } else {
            setStatus("BOARD restored: " + restored.nodes.size() + " components, " + restored.wires.size()
                    + " wires. Closing the editor saves it and installs it as the running hardware.");
        }
    }

    /** New circuit explicitly means replace this physical block's board with a fresh board. */
    @Inject(method = "newCircuit", at = @At("RETURN"))
    private void logic$newWorldBoard(CallbackInfo ci) {
        if (logic$worldBoardPos == null || canvas == null) return;
        logic$worldBoardRoot = ((CanvasAccess)(Object)canvas).logic$getRuntimeRootDocument();
    }

    /** Ctrl+S saves a reusable chip/module and checkpoints/runs the owning physical board. */
    @Inject(method = "applySave", at = @At("RETURN"))
    private void logic$checkpointBoardAfterModuleSave(CallbackInfo ci) {
        logic$saveAndProgramWorldBoard(true);
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void logic$autosaveWorldBoardOnClose(CallbackInfo ci) {
        // Editor timing is preview-only. Never let old canvases keep simulating on the render thread after close.
        EditorClockRuntime.clearAll();
        logic$saveAndProgramWorldBoard(false);
    }

    @Unique
    private void logic$saveAndProgramWorldBoard(boolean showStatus) {
        if (logic$worldBoardPos == null || logic$worldBoardRoot == null) return;
        ClientBoardNetworking.save(logic$worldBoardPos, logic$worldBoardRoot);
        try {
            ClientProgramUploader.uploadBoard(logic$worldBoardPos, logic$worldBoardRoot, library);
            if (showStatus) setStatus("Saved reusable module; BOARD also saved and installed into this Circuit Block.");
        } catch (java.io.IOException | RuntimeException error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            if (showStatus) setStatus("BOARD saved, but cannot run yet: " + message);
        }
    }
}
