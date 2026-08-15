package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.chip.ClientChipLibrary;
import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.editor.model.ChipVisualSettings;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.PortSpec;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Custom chip bodies are sized by their saved body dimensions and ports, never by label pixel length.
 * Long names are handled by dynamic text scaling instead of making the electrical symbol enormous.
 */
@Mixin(value = CircuitCanvasWidget.class, priority = 1300)
public abstract class CircuitCanvasCustomChipGeometryMixin {
    @Shadow @Final private ClientChipLibrary chips;
    @Shadow private List<PortSpec> safeInputs(EditorNode node) { throw new AssertionError(); }
    @Shadow private List<PortSpec> safeOutputs(EditorNode node) { throw new AssertionError(); }
    @Shadow private double portStep(EditorNode node) { throw new AssertionError(); }

    @Inject(method = "nodeWidth", at = @At("HEAD"), cancellable = true)
    private void logic$compactCustomWidth(EditorNode node, CallbackInfoReturnable<Double> cir) {
        if (node.kind != NodeKind.CUSTOM_CHIP) return;
        ChipVisualSettings visual = chips.chipVisual(node.chipName);
        cir.setReturnValue(logic$snapUp(Math.max(66.0, visual.width), 6.0));
    }

    @Inject(method = "nodeHeight", at = @At("HEAD"), cancellable = true)
    private void logic$compactCustomHeight(EditorNode node, CallbackInfoReturnable<Double> cir) {
        if (node.kind != NodeKind.CUSTOM_CHIP) return;
        ChipVisualSettings visual = chips.chipVisual(node.chipName);
        int count = Math.max(safeInputs(node).size(), safeOutputs(node).size());
        double step = portStep(node);
        double required = count <= 1 ? 48.0 : 30.0 + (count - 1) * step;
        cir.setReturnValue(logic$snapUp(Math.max(visual.minHeight, required), 6.0));
    }

    private static double logic$snapUp(double value, double grid) {
        return Math.ceil(value / grid) * grid;
    }
}
