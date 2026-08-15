package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.v2.EditorGrid;
import com.foreverspark.logicsim.client.screen.v2.EditorPinGeometry;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.PortDirection;
import com.foreverspark.logicsim.editor.model.PortSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Clear schematic identity for Phase 4 BOARD sockets. */
@Mixin(value = CircuitCanvasWidget.class, priority = 2350)
public abstract class CircuitCanvasPhase4RenderMixin {
    @Shadow private double zoom;
    @Shadow private int screenX(double worldX) { throw new AssertionError(); }
    @Shadow private int screenY(double worldY) { throw new AssertionError(); }
    @Shadow private double nodeWidth(EditorNode node) { throw new AssertionError(); }
    @Shadow private double nodeHeight(EditorNode node) { throw new AssertionError(); }
    @Shadow private List<PortSpec> safeInputs(EditorNode node) { throw new AssertionError(); }
    @Shadow private List<PortSpec> safeOutputs(EditorNode node) { throw new AssertionError(); }
    @Shadow private int portDisplayColor(EditorNode node, int port, PortSpec spec, boolean input) { throw new AssertionError(); }
    @Shadow private boolean isNodeSelected(int nodeId) { throw new AssertionError(); }

    @Inject(method = "drawNode", at = @At("HEAD"), cancellable = true)
    private void logic$drawSocket(GuiGraphicsExtractor graphics, EditorNode node, CallbackInfo ci) {
        if (node == null || !node.isBoardSocket()) return;
        int x = screenX(node.x);
        int y = screenY(node.y);
        int w = Math.max(26, (int)Math.round(nodeWidth(node) * zoom));
        int h = Math.max(18, (int)Math.round(nodeHeight(node) * zoom));
        int accent = node.socketDirection == PortDirection.INPUT ? 0xFF55AFC2 : 0xFFD18A5A;
        int border = isNodeSelected(node.id) ? 0xFFFFFFFF : accent;
        graphics.fill(x, y, x + w, y + h, 0xF012181E);
        graphics.outline(x, y, w, h, border);
        graphics.fill(x + 1, y + 1, x + w - 1, y + Math.max(3, (int)Math.round(4 * zoom)), accent);

        String title = (node.socketDirection == PortDirection.INPUT ? "IN  " : "OUT ") + node.label;
        logic$center(graphics, title, x, w, y + Math.max(8, (int)Math.round(9 * zoom)), 0xFFF1F4F7);
        String meta = "[" + node.width + "]  #" + node.interfaceOrder;
        logic$center(graphics, meta, x, w, y + h - Math.max(14, (int)Math.round(15 * zoom)), 0xFF9FB0C0);

        List<PortSpec> inputs = safeInputs(node);
        for (int port = 0; port < inputs.size(); port++) {
            logic$pin(graphics, node.x, node.y + nodeHeight(node) * 0.5, inputs.get(port).width(),
                    portDisplayColor(node, port, inputs.get(port), true));
        }
        List<PortSpec> outputs = safeOutputs(node);
        for (int port = 0; port < outputs.size(); port++) {
            logic$pin(graphics, node.x + nodeWidth(node), node.y + nodeHeight(node) * 0.5, outputs.get(port).width(),
                    portDisplayColor(node, port, outputs.get(port), false));
        }

        if (node.templateInstanceId > 0 && zoom >= 0.72) {
            String instance = "T" + node.templateInstanceId;
            graphics.text(Minecraft.getInstance().font, instance, x + 4, y + h - 11, 0xFF65798C, false);
        }
        ci.cancel();
    }

    @Unique
    private void logic$pin(GuiGraphicsExtractor graphics, double wx, double wy, int width, int color) {
        int x = screenX(EditorGrid.snap(wx));
        int y = screenY(EditorGrid.snap(wy));
        EditorPinGeometry.draw(graphics, x, y, width, color);
    }

    @Unique
    private void logic$center(GuiGraphicsExtractor graphics, String text, int x, int w, int y, int color) {
        var font = Minecraft.getInstance().font;
        String shown = text == null ? "" : text;
        while (shown.length() > 1 && font.width(shown) > w - 8) shown = shown.substring(0, shown.length() - 1);
        graphics.text(font, shown, x + Math.max(3, (w - font.width(shown)) / 2), y, color, false);
    }
}
