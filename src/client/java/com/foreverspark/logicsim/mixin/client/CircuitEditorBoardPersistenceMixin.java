package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.ClientBoardNetworking;
import com.foreverspark.logicsim.client.ClientProgramUploader;
import com.foreverspark.logicsim.client.chip.ClientChipLibrary;
import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.CircuitEditorScreen;
import com.foreverspark.logicsim.client.screen.EditorClockRuntime;
import com.foreverspark.logicsim.client.screen.WorldBoardContextAccess;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.CircuitHardwareSignature;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.runtime.CompiledCircuit;
import com.foreverspark.logicsim.platform.ClientEditorBridge;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.LinkedHashMap;
import java.util.Map;

@Mixin(value = CircuitEditorScreen.class, priority = 1400)
public abstract class CircuitEditorBoardPersistenceMixin implements WorldBoardContextAccess {
    @Unique private static final int MAX_PREVIEW_SESSIONS = 4;
    @Unique private static final Map<BlockPos, PreviewSession> logic$previewSessions = new LinkedHashMap<>();

    @Shadow private CircuitCanvasWidget canvas;
    @Shadow @Final private ClientChipLibrary library;
    @Shadow private void setStatus(String status) { throw new AssertionError(); }

    @Unique private CircuitDocument logic$worldBoardRoot;
    @Unique private BlockPos logic$worldBoardPos;
    @Unique private String logic$openedHardwareSignature;
    @Unique private PreviewSession logic$previewBeforeReinit;

    @Inject(method = "init", at = @At("HEAD"))
    private void logic$capturePreviewBeforeScreenReinit(CallbackInfo ci) {
        if (canvas == null) return;
        CircuitDocument root = ((CanvasAccess)(Object)canvas).logic$getRuntimeRootDocument();
        logic$previewBeforeReinit = logic$capturePreview(canvas, root);
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void logic$restoreWorldBoard(CallbackInfo ci) {
        if (canvas == null) return;

        if (logic$worldBoardRoot != null) {
            if (logic$previewBeforeReinit != null) {
                logic$restorePreview(canvas, logic$previewBeforeReinit, ((CanvasAccess)(Object)canvas).logic$getRuntimeRootDocument());
                logic$previewBeforeReinit = null;
            }
            return;
        }

        BlockPos pos = ClientEditorBridge.activeCircuitPos();
        CircuitDocument restored = ClientBoardNetworking.consumePendingBoard(pos);
        if (restored == null) return;

        canvas.setDocument(restored, null);
        logic$worldBoardRoot = restored;
        logic$worldBoardPos = pos == null ? null : pos.immutable();
        logic$openedHardwareSignature = logic$hardwareSignature(restored);

        PreviewSession cached = logic$getPreview(logic$worldBoardPos);
        if (cached != null) logic$restorePreview(canvas, cached, restored);

        if (restored.nodes.isEmpty()) {
            setStatus("BOARD is empty — edits are saved on close. Opening/closing an unchanged block does not restart it.");
        } else if (cached != null && cached.hardwareSignature().equals(logic$openedHardwareSignature)) {
            setStatus("BOARD restored with its previous live inspector state. Closing it will NOT restart unchanged hardware.");
        } else {
            setStatus("BOARD restored: " + restored.nodes.size() + " components, " + restored.wires.size()
                    + " wires. Closing only reinstalls the runtime if electrical hardware or device binding actually changed.");
        }
    }

    @Inject(method = "newCircuit", at = @At("RETURN"))
    private void logic$newWorldBoard(CallbackInfo ci) {
        if (logic$worldBoardPos == null || canvas == null) return;
        logic$worldBoardRoot = ((CanvasAccess)(Object)canvas).logic$getRuntimeRootDocument();
    }

    @Override
    public void logic$replaceWorldBoardRoot(CircuitDocument root) {
        if (root == null) return;
        logic$worldBoardRoot = root;
        // Keep the signature of the hardware currently running in the block. The newly loaded board will therefore
        // be recognized as a hardware change and installed when the editor closes or explicitly checkpoints it.
        if (logic$openedHardwareSignature == null) logic$openedHardwareSignature = "";
    }

    @Inject(method = "applySave", at = @At("RETURN"))
    private void logic$checkpointBoardAfterModuleSave(CallbackInfo ci) {
        logic$saveAndMaybeProgramWorldBoard(true, true);
        if (logic$worldBoardRoot != null) logic$openedHardwareSignature = logic$hardwareSignature(logic$worldBoardRoot);
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void logic$autosaveWorldBoardOnClose(CallbackInfo ci) {
        if (logic$worldBoardPos != null && canvas != null) {
            CircuitDocument root = ((CanvasAccess)(Object)canvas).logic$getRuntimeRootDocument();
            PreviewSession session = logic$capturePreview(canvas, root);
            if (session != null) logic$putPreview(logic$worldBoardPos, session);
        }

        EditorClockRuntime.clearAll();
        logic$saveAndMaybeProgramWorldBoard(false, false);
    }

    @Unique
    private void logic$saveAndMaybeProgramWorldBoard(boolean showStatus, boolean forceProgram) {
        if (logic$worldBoardPos == null || logic$worldBoardRoot == null) return;
        ClientBoardNetworking.save(logic$worldBoardPos, logic$worldBoardRoot);

        String currentSignature = logic$hardwareSignature(logic$worldBoardRoot);
        boolean hardwareChanged = logic$openedHardwareSignature == null || !logic$openedHardwareSignature.equals(currentSignature);
        if (!forceProgram && !hardwareChanged) {
            if (showStatus) setStatus("BOARD saved. Running hardware was unchanged, so the computer was not restarted.");
            return;
        }

        try {
            ClientProgramUploader.uploadBoard(logic$worldBoardPos, logic$worldBoardRoot, library);
            logic$openedHardwareSignature = currentSignature;
            if (showStatus) setStatus("BOARD electrical hardware/device binding changed and was reinstalled into this Circuit Block.");
        } catch (java.io.IOException | RuntimeException error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            if (showStatus) setStatus("BOARD saved, but cannot run yet: " + message);
        }
    }

    @Unique
    private static PreviewSession logic$capturePreview(CircuitCanvasWidget target, CircuitDocument root) {
        if (target == null || root == null) return null;
        CanvasAccess access = (CanvasAccess)(Object)target;
        CompiledCircuit runtime = access.logic$getRuntime();
        if (runtime == null) return null;
        return new PreviewSession(runtime, Map.copyOf(access.logic$getInputStates()), logic$hardwareSignature(root));
    }

    @Unique
    private static void logic$restorePreview(CircuitCanvasWidget target, PreviewSession session, CircuitDocument root) {
        if (target == null || session == null || root == null) return;
        if (!session.hardwareSignature().equals(logic$hardwareSignature(root))) return;

        CanvasAccess access = (CanvasAccess)(Object)target;
        access.logic$setRuntime(session.runtime());
        Map<Integer, Long> inputs = access.logic$getInputStates();
        inputs.clear();
        inputs.putAll(session.inputStates());
        for (EditorNode input : root.inputNodes()) {
            Long value = inputs.get(input.id);
            if (value == null) continue;
            try {
                session.runtime().driveInputUnsigned(input.id, value);
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Unique
    private static synchronized PreviewSession logic$getPreview(BlockPos pos) {
        return pos == null ? null : logic$previewSessions.get(pos);
    }

    @Unique
    private static synchronized void logic$putPreview(BlockPos pos, PreviewSession session) {
        if (pos == null || session == null) return;
        logic$previewSessions.remove(pos);
        logic$previewSessions.put(pos.immutable(), session);
        while (logic$previewSessions.size() > MAX_PREVIEW_SESSIONS) {
            BlockPos oldest = logic$previewSessions.keySet().iterator().next();
            logic$previewSessions.remove(oldest);
        }
    }

    @Unique
    private static String logic$hardwareSignature(CircuitDocument board) {
        return CircuitHardwareSignature.of(board);
    }

    @Unique
    private record PreviewSession(CompiledCircuit runtime, Map<Integer, Long> inputStates, String hardwareSignature) {}
}
