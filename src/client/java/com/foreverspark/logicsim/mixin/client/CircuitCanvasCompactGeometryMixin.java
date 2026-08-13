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
            case INPUT, OUTPUT -> cir.setReturnValue(72.0);
            case NAND -> cir.setReturnValue(78.0);
            case CONSTANT -> cir.setReturnValue(78.0);
            case PROBE -> cir.setReturnValue(84.0);
            case BUS -> cir.setReturnValue(node.width <= 1 ? 24.0 : 36.0);
            case SPLITTER, MERGER -> cir.setReturnValue(78.0);
            case CUSTOM_CHIP -> { }
        }
    }

    @Inject(method = "nodeHeight", at = @At("HEAD"), cancellable = true)
    private void logic$compactHeight(EditorNode node, CallbackInfoReturnable<Double> cir) {
        switch (node.kind) {
            case INPUT, OUTPUT, CONSTANT, PROBE -> cir.setReturnValue(48.0);
            case NAND -> cir.setReturnValue(54.0);
            case BUS -> cir.setReturnValue(24.0);
            case SPLITTER, MERGER -> {
                int ports = Math.max(safeInputs(node).size(), safeOutputs(node).size());
                double height = Math.max(48.0, 38.0 + Math.max(0, ports - 1) * 12.0);
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
