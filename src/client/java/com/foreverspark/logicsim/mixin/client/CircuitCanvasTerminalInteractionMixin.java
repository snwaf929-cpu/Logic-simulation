package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Consumer;

@Mixin(value = CircuitCanvasWidget.class, priority = 1250)
public abstract class CircuitCanvasTerminalInteractionMixin {
    @Shadow private double zoom;
    @Shadow private Consumer<String> status;
    @Shadow private int screenX(double worldX) { throw new AssertionError(); }
    @Shadow private int screenY(double worldY) { throw new AssertionError(); }
    @Shadow private double nodeWidth(EditorNode node) { throw new AssertionError(); }
    @Shadow private double nodeHeight(EditorNode node) { throw new AssertionError(); }

    @Inject(method = "inputToggleHit", at = @At("HEAD"), cancellable = true)
    private void logic$centerInputSwitch(EditorNode node, double mouseX, double mouseY, CallbackInfoReturnable<Boolean> cir) {
        if (node.kind != NodeKind.INPUT) return;
        int x = screenX(node.x);
        int y = screenY(node.y);
        double w = nodeWidth(node) * zoom;
        double h = nodeHeight(node) * zoom;
        double size = Math.max(8.0, 12.0 * zoom);
        double cx = x + w * 0.5;
        double cy = y + h * 0.5;
        cir.setReturnValue(mouseX >= cx - size * 0.5 && mouseX <= cx + size * 0.5
                && mouseY >= cy - size * 0.5 && mouseY <= cy + size * 0.5);
    }

    @Inject(method = "toggleInput", at = @At("RETURN"))
    private void logic$explainPersistentInput(EditorNode node, CallbackInfo ci) {
        status.accept("INPUT manual state saved: leaving the editor keeps this value running in the Circuit Block. A real external input can still drive this port later.");
    }

    @Inject(method = "toggleConstant", at = @At("RETURN"))
    private void logic$explainStoredConstant(EditorNode node, CallbackInfo ci) {
        status.accept("CONSTANT is stored in the board and runs in the physical Circuit Block as a fixed internal source.");
    }
}
