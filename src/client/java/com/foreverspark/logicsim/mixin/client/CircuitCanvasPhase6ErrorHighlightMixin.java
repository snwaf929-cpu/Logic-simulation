package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.v2.EditorErrorLocator;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws compile diagnostics on the actual schematic objects instead of relying on the status bar alone. */
@Mixin(value = CircuitCanvasWidget.class, priority = 2180)
public abstract class CircuitCanvasPhase6ErrorHighlightMixin {
    @Shadow private CircuitDocument document;
    @Shadow private String compileError;
    @Shadow private int screenX(double worldX) { throw new AssertionError(); }
    @Shadow private int screenY(double worldY) { throw new AssertionError(); }
    @Shadow private double nodeWidth(EditorNode node) { throw new AssertionError(); }
    @Shadow private double nodeHeight(EditorNode node) { throw new AssertionError(); }

    @Inject(method = "extractWidgetRenderState", at = @At("RETURN"))
    private void logic$phase6ErrorOverlay(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (compileError == null || compileError.isBlank()) return;
        for (Integer id : EditorErrorLocator.locate(document, compileError)) {
            EditorNode node;
            try {
                node = document.node(id);
            } catch (RuntimeException ignored) {
                continue;
            }
            int left = screenX(node.x);
            int top = screenY(node.y);
            int right = screenX(node.x + nodeWidth(node));
            int bottom = screenY(node.y + nodeHeight(node));
            int width = Math.max(1, right - left);
            int height = Math.max(1, bottom - top);
            graphics.outline(left - 4, top - 4, width + 8, height + 8, 0xFFFF4242);
            graphics.outline(left - 2, top - 2, width + 4, height + 4, 0xFFFF8A8A);
            graphics.fill(left - 7, top - 7, left + 2, top + 2, 0xFFFF4242);
        }
    }
}
