package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.PortSpec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(CircuitCanvasWidget.class)
public abstract class CircuitCanvasCompactGeometryMixin {
    @Shadow private List<PortSpec> safeInputs(EditorNode node) { throw new AssertionError(); }
    @Shadow private List<PortSpec> safeOutputs(EditorNode node) { throw new AssertionError(); }
    @Shadow private double nodeHeight(EditorNode node) { throw new AssertionError(); }

    @Inject(method = "nodeWidth", at = @At("HEAD"), cancellable = true)
    private void logic$compactWidth(EditorNode node, CallbackInfoReturnable<Double> cir) {
        switch (node.kind) {
            // I/O terminals are tiny one-bit controls. RANDOM is still one row tall, but wider so
            // its probability can be read without turning it into a full-size chip card.
            case INPUT, OUTPUT -> cir.setReturnValue(18.0);
            case NAND -> cir.setReturnValue(66.0);
            case CONSTANT -> cir.setReturnValue(node.randomSource ? 54.0 : 66.0);
            case PROBE -> cir.setReturnValue(66.0);
            case BUS -> cir.setReturnValue(node.width <= 1 ? 20.0 : 30.0);
            case SPLITTER, MERGER -> cir.setReturnValue(72.0);
            case CUSTOM_CHIP -> { }
        }
    }

    @Inject(method = "nodeHeight", at = @At("HEAD"), cancellable = true)
    private void logic$compactHeight(EditorNode node, CallbackInfoReturnable<Double> cir) {
        switch (node.kind) {
            // One terminal = one merger/splitter bit-row. RANDOM is also exactly one bit-row.
            case INPUT, OUTPUT -> cir.setReturnValue(12.0);
            case CONSTANT -> cir.setReturnValue(node.randomSource ? 12.0 : 42.0);
            case PROBE -> cir.setReturnValue(42.0);
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

    /**
     * CONSTANT normally places any input at the old 30-unit component row. RANDOM is a compact
     * CONSTANT subtype with both TRIGGER and OUT, so its real interactive TRIGGER pin must move to
     * the same center row that the compact renderer uses. This keeps rendered pins, hitboxes and
     * wire endpoints on exactly the same grid point.
     */
    @ModifyConstant(method = "inputPortPoint", constant = @Constant(doubleValue = 30.0))
    private double logic$centerRandomTrigger(double original, EditorNode node, int port) {
        if (node.kind == NodeKind.CONSTANT && node.randomSource) return nodeHeight(node) * 0.5;
        return original;
    }

    @Inject(method = "portStep", at = @At("HEAD"), cancellable = true)
    private void logic$compactPortStep(EditorNode node, CallbackInfoReturnable<Double> cir) {
        if (node.kind == NodeKind.NAND) cir.setReturnValue(14.0);
        if (node.kind == NodeKind.SPLITTER || node.kind == NodeKind.MERGER) cir.setReturnValue(12.0);
    }
}
