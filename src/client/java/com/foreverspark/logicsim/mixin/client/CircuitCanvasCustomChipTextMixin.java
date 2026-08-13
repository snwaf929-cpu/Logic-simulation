package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.PortSpec;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(value = CircuitCanvasWidget.class, priority = 1250)
public abstract class CircuitCanvasCustomChipTextMixin {
    @Shadow private double zoom;
    @Shadow private int screenX(double worldX) { throw new AssertionError(); }
    @Shadow private int screenY(double worldY) { throw new AssertionError(); }
    @Shadow private double nodeWidth(EditorNode node) { throw new AssertionError(); }
    @Shadow private double nodeHeight(EditorNode node) { throw new AssertionError(); }
    @Shadow private double centeredPortY(EditorNode node, int port, int count) { throw new AssertionError(); }
    @Shadow private List<PortSpec> safeInputs(EditorNode node) { throw new AssertionError(); }
    @Shadow private List<PortSpec> safeOutputs(EditorNode node) { throw new AssertionError(); }
    @Shadow private int portDisplayColor(EditorNode node, int port, PortSpec spec, boolean input) { throw new AssertionError(); }
    @Shadow private boolean validTarget(boolean input) { throw new AssertionError(); }
    @Shadow private boolean isNodeSelected(int nodeId) { throw new AssertionError(); }
    @Shadow private int nodeAccent(EditorNode node) { throw new AssertionError(); }
    @Shadow private Font font() { throw new AssertionError(); }

    @Inject(method = "drawCustomChipNode", at = @At("HEAD"), cancellable = true)
    private void logic$dynamicCustomChip(GuiGraphicsExtractor graphics, EditorNode node, List<PortSpec> inputs, List<PortSpec> outputs, int x, int y, int w, int h, CallbackInfo ci) {
        int accent = nodeAccent(node);
        int border = isNodeSelected(node.id) ? 0xFFFFFFFF : logic$darken(accent, 0.92);
        graphics.fill(x, y, x + w, y + h, 0xF0191F26);
        graphics.outline(x, y, w, h, border);
        if (w > 4 && h > 4) graphics.outline(x + 1, y + 1, w - 2, h - 2, 0xFF252E37);
        graphics.fill(x + 1, y + 1, x + w - 1, y + Math.max(2, (int)Math.round(4 * zoom)), accent);

        logic$text(graphics, node.displayName(), x + w / 2, y + Math.max(3, (int)Math.round(9 * zoom)), Math.max(6, w - 10), 1.0f, 0xFFF2F5F8);

        for (int port = 0; port < inputs.size(); port++) {
            double py = centeredPortY(node, port, inputs.size());
            logic$port(graphics, node.x, py, portDisplayColor(node, port, inputs.get(port), true), validTarget(true));
        }
        for (int port = 0; port < outputs.size(); port++) {
            double py = centeredPortY(node, port, outputs.size());
            logic$port(graphics, node.x + nodeWidth(node), py, portDisplayColor(node, port, outputs.get(port), false), validTarget(false));
        }
        ci.cancel();
    }

    @Unique private void logic$text(GuiGraphicsExtractor graphics, String text, int centerX, int y, int maxPixels, float cap, int color) {
        if (text == null || text.isEmpty()) return;
        int rawWidth = Math.max(1, font().width(text));
        float zoomScale = (float)Math.max(0.30, Math.min(1.0, zoom));
        float fitScale = (float)Math.min(1.0, Math.max(1, maxPixels) / (double)rawWidth);
        float scale = Math.max(0.22f, Math.min(cap, Math.min(zoomScale, fitScale)));
        graphics.pose().pushMatrix();
        graphics.pose().scale(scale);
        graphics.text(font(), text, Math.round(centerX / scale - rawWidth / 2.0f), Math.round(y / scale), color, false);
        graphics.pose().popMatrix();
    }

    @Unique private void logic$port(GuiGraphicsExtractor graphics, double worldX, double worldY, int color, boolean target) {
        int x = screenX(logic$snap(worldX));
        int y = screenY(logic$snap(worldY));
        int r = Math.max(1, (int)Math.round((target ? 4.0 : 3.0) * zoom));
        graphics.fill(x - r, y - r, x + r + 1, y + r + 1, color);
        graphics.outline(x - r - 1, y - r - 1, r * 2 + 3, r * 2 + 3, 0xFF090B0D);
    }

    @Unique private static int logic$darken(int color, double factor) {
        int r = (int)(((color >>> 16) & 0xFF) * factor), g = (int)(((color >>> 8) & 0xFF) * factor), b = (int)((color & 0xFF) * factor);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    @Unique private static double logic$snap(double value) { return Math.round(value / 6.0) * 6.0; }
}
