package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.runtime.CompiledCircuit;
import com.foreverspark.logicsim.network.DriveCircuitInputPayload;
import com.foreverspark.logicsim.platform.ClientEditorBridge;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

/**
 * Root INPUT switches are runtime controls, not circuit-definition edits.
 * Their initial editor value comes from inputDefaultValue, but clicking one drives both the local preview and the
 * already-running physical Circuit Block without rewriting the saved default or reinstalling the whole program.
 */
@Mixin(value = CircuitCanvasWidget.class, priority = 1260)
public abstract class CircuitCanvasPersistentInputMixin {
    @Shadow private Map<Integer, Long> inputStates;
    @Shadow private CircuitDocument document;
    @Shadow private CompiledCircuit runtime;
    @Shadow private String runtimeScopePath;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void logic$restoreDefaultsAfterConstruction(CallbackInfo ci) {
        logic$restoreSavedInputDefaultsNow();
    }

    @Inject(
            method = "setDocument(Lcom/foreverspark/logicsim/editor/model/CircuitDocument;Ljava/lang/String;)V",
            at = @At("RETURN")
    )
    private void logic$restoreSavedInputDefaults(CircuitDocument restored, String rootChipName, CallbackInfo ci) {
        logic$restoreSavedInputDefaultsNow();
    }

    private void logic$restoreSavedInputDefaultsNow() {
        if (document == null || !CompiledCircuit.ROOT_SCOPE.equals(runtimeScopePath)) return;
        for (EditorNode node : document.nodes) {
            if (node.kind != NodeKind.INPUT) continue;
            long value = mask(node.inputDefaultValue, node.width);
            inputStates.put(node.id, value);
            if (runtime != null) {
                try {
                    runtime.driveInputUnsigned(node.id, value);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    /** Mirror a manual editor switch change into the persistent server-side runtime. */
    @Inject(method = "toggleInput", at = @At("RETURN"))
    private void logic$drivePhysicalInput(EditorNode node, CallbackInfo ci) {
        if (node == null || node.kind != NodeKind.INPUT || document == null
                || !CompiledCircuit.ROOT_SCOPE.equals(runtimeScopePath)) return;
        BlockPos pos = ClientEditorBridge.activeCircuitPos();
        if (pos == null) return;

        String portName = rootInputPortName(node.id);
        if (portName == null) return;
        long value = mask(inputStates.getOrDefault(node.id, 0L), node.width);
        ClientPlayNetworking.send(new DriveCircuitInputPayload(
                pos,
                portName,
                Long.toUnsignedString(value, 16)
        ));
    }

    private String rootInputPortName(int nodeId) {
        List<EditorNode> inputs = document.inputNodes();
        for (int index = 0; index < inputs.size(); index++) {
            EditorNode input = inputs.get(index);
            if (input.id != nodeId) continue;
            return input.label == null || input.label.isBlank() ? "IN" + index : input.label;
        }
        return null;
    }

    private static long mask(long value, int width) {
        return width >= 64 ? value : value & ((1L << width) - 1L);
    }
}
