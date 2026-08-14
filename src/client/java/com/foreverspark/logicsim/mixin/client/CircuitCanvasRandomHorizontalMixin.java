package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.core.LogicValue;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.PortSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * RANDOM is a one-bit edge-triggered source, not a normal CONSTANT card. Keep it exactly one
 * bit-row tall while allowing enough horizontal room to show its probability. This mixin has a
 * deliberately high priority so older generic built-in renderers cannot turn RANDOM back into a
 * tall CONSTANT-looking card.
 */
@Mixin(value = CircuitCanvasWidget.class, priority = 2500)
public abstract class CircuitCanvasRandomHorizontalMixin {
    @Shadow private CircuitDocument document;
    @Shadow private double zoom;
    @Shadow private int screenX(double worldX) { throw new AssertionError(); }
    @Shadow private int screenY(double worldY) { throw new AssertionError(); }
    @Shadow private double nodeWidth(EditorNode node) { throw new AssertionError(); }
    @Shadow private double nodeHeight(EditorNode node) { throw new AssertionError(); }
    @Shadow private List<PortSpec> safeInputs(EditorNode node) { throw new AssertionError(); }
    @Shadow private List<PortSpec> safeOutputs(EditorNode node) { throw new AssertionError(); }
    @Shadow private int portDisplayColor(EditorNode node, int port, PortSpec spec, boolean input) { throw new AssertionError(); }
    @Shadow private boolean validTarget(boolean input) { throw new AssertionError(); }
    @Shadow private boolean isNodeSelected(int nodeId) { throw new AssertionError(); }
    @Shadow private LogicValue[] valueForNode(EditorNode node) { throw new AssertionError(); }

    @Inject(method = "drawNode", at = @At("HEAD"), cancellable = true)
    private void logic$drawRandom(GuiGraphicsExtractor graphics, EditorNode node, CallbackInfo ci) {
        if (!logic$isRandom(node)) return;

        int x = screenX(node.x);
        int y = screenY(node.y);
        int w = Math.max(20, (int)Math.round(nodeWidth(node) * zoom));
        int h = Math.max(8, (int)Math.round(nodeHeight(node) * zoom));
        int accent = 0xFFB06CE8;
        int border = isNodeSelected(node.id) ? 0xFFFFFFFF : 0xFF8050AA;

        graphics.fill(x, y, x + w, y + h, 0xF012171D);
        graphics.outline(x, y, w, h, border);

        String label = "RND " + node.randomChancePercent + "%";
        logic$centerSmallText(graphics, label, x, y, w, h, accent);

        List<PortSpec> inputs = safeInputs(node);
        for (int port = 0; port < inputs.size(); port++) {
            logic$pin(graphics, node.x, node.y + nodeHeight(node) * 0.5,
                    portDisplayColor(node, port, inputs.get(port), true), validTarget(true));
        }
        List<PortSpec> outputs = safeOutputs(node);
        for (int port = 0; port < outputs.size(); port++) {
            logic$pin(graphics, node.x + nodeWidth(node), node.y + nodeHeight(node) * 0.5,
                    portDisplayColor(node, port, outputs.get(port), false), validTarget(false));
        }

        ci.cancel();
    }

    @Inject(method = "drawPortHoverTooltip", at = @At("HEAD"), cancellable = true)
    private void logic$randomTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        EditorNode node = logic$randomAt(mouseX, mouseY);
        if (node == null) return;

        LogicValue[] value = valueForNode(node);
        String out = logic$isHigh(value) ? "1" : "0";
        String text = "RANDOM " + node.randomChancePercent + "%   TRIGGER 0 -> 1   OUT " + out + "   double-click to edit";
        Font font = Minecraft.getInstance().font;
        int padding = 5;
        int boxW = font.width(text) + padding * 2;
        int boxH = 17;
        CircuitCanvasWidget self = (CircuitCanvasWidget)(Object)this;
        int x = Math.min(mouseX + 12, self.getX() + self.getWidth() - boxW - 3);
        int y = mouseY - boxH - 8;
        if (x < self.getX() + 3) x = self.getX() + 3;
        if (y < self.getY() + 3) y = mouseY + 12;
        y = Math.min(y, self.getY() + self.getHeight() - boxH - 3);

        graphics.fill(x, y, x + boxW, y + boxH, 0xF0181D23);
        graphics.outline(x, y, boxW, boxH, 0xFFB06CE8);
        graphics.text(font, text, x + padding, y + 5, 0xFFE8EDF3, false);
        ci.cancel();
    }

    @Unique private EditorNode logic$randomAt(double mouseX, double mouseY) {
        for (int i = document.nodes.size() - 1; i >= 0; i--) {
            EditorNode node = document.nodes.get(i);
            if (!logic$isRandom(node)) continue;
            int x = screenX(node.x);
            int y = screenY(node.y);
            int w = Math.max(20, (int)Math.round(nodeWidth(node) * zoom));
            int h = Math.max(8, (int)Math.round(nodeHeight(node) * zoom));
            if (mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h) return node;
        }
        return null;
    }

    @Unique private void logic$centerSmallText(GuiGraphicsExtractor graphics, String text, int x, int y, int w, int h, int color) {
        Font font = Minecraft.getInstance().font;
        int rawW = Math.max(1, font.width(text));
        double availableW = Math.max(6.0, w - 8.0);
        double availableH = Math.max(5.0, h - 2.0);
        float scale = (float)Math.max(0.35, Math.min(1.0, Math.min(availableW / rawW, availableH / 9.0)));
        float cx = x + w * 0.5f;
        float cy = y + h * 0.5f;
        graphics.pose().pushMatrix();
        graphics.pose().scale(scale);
        int tx = Math.round(cx / scale - rawW / 2f);
        int ty = Math.round(cy / scale - 4.5f);
        graphics.text(font, text, tx, ty, color, false);
        graphics.pose().popMatrix();
    }

    @Unique private void logic$pin(GuiGraphicsExtractor graphics, double worldX, double worldY, int color, boolean target) {
        double snappedX = Math.round(worldX / 6.0) * 6.0;
        double snappedY = Math.round(worldY / 6.0) * 6.0;
        int x = screenX(snappedX);
        int y = screenY(snappedY);
        int r = Math.max(1, (int)Math.round((target ? 3.5 : 3.0) * zoom));
        graphics.fill(x - r, y - r, x + r + 1, y + r + 1, color);
        graphics.outline(x - r - 1, y - r - 1, r * 2 + 3, r * 2 + 3, 0xFF090B0D);
    }

    @Unique private static boolean logic$isRandom(EditorNode node) {
        return node.kind == NodeKind.CONSTANT && node.randomSource;
    }

    @Unique private static boolean logic$isHigh(LogicValue[] values) {
        if (values == null) return false;
        for (LogicValue value : values) if (value == LogicValue.HIGH) return true;
        return false;
    }
}
