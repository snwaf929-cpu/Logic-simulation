package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.PortSpec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(CircuitCanvasWidget.class)
public abstract class CircuitCanvasCompactGeometryMixin {
    @Shadow private List<PortSpec> safeInputs(EditorNode node) { throw new AssertionError(); }
    @Shadow private List<PortSpec> safeOutputs(EditorNode node) { throw new AssertionError(); }

    @Inject(method = "nodeWidth", at = @At("HEAD"), cancellable = true)
    private void logic$compactWidth(EditorNode node, CallbackInfoReturnable<Double> cir) {
        switch (node.kind) {
            // I/O terminals are deliberately tiny controls, not chip-sized nodes. 18x12 lets sixteen
            // terminals stack exactly on the 12-unit bit rows of a 16-bit splitter/merger.
            case INPUT, OUTPUT -> cir.setReturnValue(18.0);
            case NAND -> cir.setReturnValue(66.0);
            case CONSTANT -> cir.setReturnValue(66.0);
            case PROBE -> cir.setReturnValue(66.0);
            case BUS -> cir.setReturnValue(node.width <= 1 ? 20.0 : 30.0);
            case SPLITTER, MERGER -> cir.setReturnValue(72.0);
            case CUSTOM_CHIP -> { }
        }
    }

    @Inject(method = "nodeHeight", at = @At("HEAD"), cancellable = true)
    private void logic$compactHeight(EditorNode node, CallbackInfoReturnable<Double> cir) {
        switch (node.kind) {
            // One terminal = one merger/splitter bit-row.
            case INPUT, OUTPUT -> cir.setReturnValue(12.0);
            case CONSTANT, PROBE -> cir.setReturnValue(42.0);
            case NAND -> cir.setReturnValue(48.0);
            case BUS -> cir.setReturnValue(20.0);
            case SPLITTER, MERGER -> {
                int ports = Math.max(safeInputs(node).size(), safeOutputs(node).size());
                double height = Math.max(42.0, 34.0 + Math.max(0, ports - 1) * 12.0);
                cir.setReturnValue(Math.ceil(height / 6.0) * 6.0);
            }
            case CUSTOM_CHIP -> { }
        }
    }

    @Inject(method = "portStep", at = @At("HEAD"), cancellable = true)
    private void logic$compactPortStep(EditorNode node, CallbackInfoReturnable<Double> cir) {
        if (node.kind == NodeKind.NAND) cir.setReturnValue(14.0);
        if (node.kind == NodeKind.SPLITTER || node.kind == NodeKind.MERGER) cir.setReturnValue(12.0);
    }
}
