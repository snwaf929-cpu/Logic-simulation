package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CircuitCanvasWidget.class, priority = 1250)
public abstract class CircuitCanvasTerminalInteractionMixin {
    @Shadow private double zoom;
    @Shadow private int screenX(double worldX) { throw new AssertionError(); }
    @Shadow private int screenY(double worldY) { throw new AssertionError(); }
    @Shadow private double nodeWidth(EditorNode node) { throw new AssertionError(); }
    @Shadow private double nodeHeight(EditorNode node) { throw new AssertionError(); }

    @Inject(method = "inputToggleHit", at = @At("HEAD"), cancellable = true)
    private void logic$wholeInputTerminal(EditorNode node, double mouseX, double mouseY, CallbackInfoReturnable<Boolean> cir) {
        if (node.kind != NodeKind.INPUT) return;
        int x = screenX(node.x);
        int y = screenY(node.y);
        double w = nodeWidth(node) * zoom;
        double h = nodeHeight(node) * zoom;
        cir.setReturnValue(mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h);
    }
}
