package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.ClientBoardNetworking;
import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.CircuitEditorScreen;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.platform.ClientEditorBridge;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives every physical Circuit Block its own persistent editable board.
 * The saved object is the root board even while the user is drilled into a nested CPU/ALU/etc chip.
 */
@Mixin(value = CircuitEditorScreen.class, priority = 1400)
public abstract class CircuitEditorBoardPersistenceMixin {
    @Shadow private CircuitCanvasWidget canvas;
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
            setStatus("BOARD is empty — edits autosave to this Circuit Block when you close it. Ctrl+S saves reusable CPU/GPU/etc modules.");
        } else {
            setStatus("BOARD restored: " + restored.nodes.size() + " components, " + restored.wires.size()
                    + " wires. Closing the editor autosaves this board.");
        }
    }

    /** New circuit explicitly means replace this physical block's board with a fresh board. */
    @Inject(method = "newCircuit", at = @At("RETURN"))
    private void logic$newWorldBoard(CallbackInfo ci) {
        if (logic$worldBoardPos == null || canvas == null) return;
        logic$worldBoardRoot = ((CanvasAccess)(Object)canvas).logic$getRuntimeRootDocument();
    }

    /** Ctrl+S still saves a reusable chip/module, but also checkpoints the physical board. */
    @Inject(method = "applySave", at = @At("RETURN"))
    private void logic$checkpointBoardAfterModuleSave(CallbackInfo ci) {
        logic$saveWorldBoard();
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void logic$autosaveWorldBoardOnClose(CallbackInfo ci) {
        logic$saveWorldBoard();
    }

    @Unique
    private void logic$saveWorldBoard() {
        if (logic$worldBoardPos == null || logic$worldBoardRoot == null) return;
        ClientBoardNetworking.save(logic$worldBoardPos, logic$worldBoardRoot);
    }
}
