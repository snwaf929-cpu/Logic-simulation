package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.runtime.CompiledCircuit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/** Makes root INPUT switches real saved manual/default hardware values. */
@Mixin(value = CircuitCanvasWidget.class, priority = 1260)
public abstract class CircuitCanvasPersistentInputMixin {
    @Shadow private Map<Integer, Long> inputStates;
    @Shadow private CircuitDocument document;
    @Shadow private CompiledCircuit runtime;
    @Shadow private String runtimeScopePath;

    /**
     * CircuitEditorScreen is re-initialized after returning from child config screens. That creates
     * a new CircuitCanvasWidget around the same board document, so restore the saved INPUT defaults
     * immediately instead of showing every switch as OFF until the board is reloaded another way.
     */
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

    @Inject(method = "toggleInput", at = @At("RETURN"))
    private void logic$persistToggledInput(EditorNode node, CallbackInfo ci) {
        if (node == null || node.kind != NodeKind.INPUT || !CompiledCircuit.ROOT_SCOPE.equals(runtimeScopePath)) return;
        node.inputDefaultValue = mask(inputStates.getOrDefault(node.id, 0L), node.width);
    }

    @Inject(method = "changeSelectedWidth", at = @At("RETURN"))
    private void logic$persistInputAfterWidthChange(int direction, CallbackInfo ci) {
        if (document == null || !CompiledCircuit.ROOT_SCOPE.equals(runtimeScopePath)) return;
        for (EditorNode node : document.nodes) {
            if (node.kind != NodeKind.INPUT || !inputStates.containsKey(node.id)) continue;
            node.inputDefaultValue = mask(inputStates.get(node.id), node.width);
        }
    }

    private static long mask(long value, int width) {
        return width >= 64 ? value : value & ((1L << width) - 1L);
    }
}
