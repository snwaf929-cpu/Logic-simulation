package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.EditorClockRuntime;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.function.Consumer;

@Mixin(value = CircuitCanvasWidget.class, priority = 1250)
public abstract class CircuitCanvasTerminalInteractionMixin {
    @Shadow private double zoom;
    @Shadow private Consumer<String> status;
    @Shadow private Map<Integer, Long> inputStates;
    @Shadow private int screenX(double worldX) { throw new AssertionError(); }
    @Shadow private int screenY(double worldY) { throw new AssertionError(); }
    @Shadow private double nodeWidth(EditorNode node) { throw new AssertionError(); }
    @Shadow private double nodeHeight(EditorNode node) { throw new AssertionError(); }

    @Inject(method = "inputToggleHit", at = @At("HEAD"), cancellable = true)
    private void logic$compactInputSwitch(EditorNode node, double mouseX, double mouseY, CallbackInfoReturnable<Boolean> cir) {
        // CLOCK/RANDOM are CONSTANT subtypes. Never let the base constant-toggle click path mutate them.
        if (node.kind == NodeKind.CONSTANT && (node.clockSource || node.randomSource)) {
            cir.setReturnValue(false);
            return;
        }
        if (node.kind != NodeKind.INPUT) return;

        int x = screenX(node.x);
        int y = screenY(node.y);
        int w = Math.max(7, (int)Math.round(nodeWidth(node) * zoom));
        int h = Math.max(6, (int)Math.round(nodeHeight(node) * zoom));
        int indicator = Math.max(4, Math.min(h - 2, (int)Math.round(7.0 * zoom)));
        int inset = Math.max(1, (int)Math.round(3.0 * zoom));
        int sx = x + inset;
        int sy = y + (h - indicator) / 2;

        // The visible square is the switch. Keep the rest of the tiny terminal available as a drag handle.
        double padding = Math.max(1.0, zoom);
        cir.setReturnValue(mouseX >= sx - padding && mouseX <= sx + indicator + padding
                && mouseY >= sy - padding && mouseY <= sy + indicator + padding);
    }

    @Inject(method = "toggleInput", at = @At("RETURN"))
    private void logic$explainPersistentInput(EditorNode node, CallbackInfo ci) {
        long value = inputStates.getOrDefault(node.id, 0L);
        status.accept(node.displayName() + " = " + (value == 0L ? "OFF" : "ON") + " — saved as this board's default input");
        EditorClockRuntime.processRandomSources((CircuitCanvasWidget)(Object)this);
    }

    @Inject(method = "toggleConstant", at = @At("RETURN"))
    private void logic$explainStoredConstant(EditorNode node, CallbackInfo ci) {
        status.accept("CONSTANT is stored in the board and runs in the physical Circuit Block as a fixed internal source.");
    }
}
