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
 * Dedicated RANDOM renderer. The visual intentionally resembles a compact horizontal hardware
 * source: a full-height TRIGGER pad on the left, a flat labelled body, and a full-height OUT pad
 * on the right. It stays one bit-row high regardless of probability.
 */
@Mixin(value = CircuitCanvasWidget.class, priority = 2500)
public abstract class CircuitCanvasRandomHorizontalMixin {
    @Unique private static final int LOGIC_BODY = 0xFF596168;
    @Unique private static final int LOGIC_TOP = 0xFFF0C64A;
    @Unique private static final int LOGIC_DARK = 0xFF171B20;
    @Unique private static final int LOGIC_TEXT = 0xFFF5F7F9;

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

    /*
     * order=900 deliberately runs before the editor's older default-order built-in render mixins.
     * Cancelling here means RANDOM gets exactly one renderer instead of being painted again as a
     * generic CONSTANT card afterward.
     */
    @Inject(method = "drawNode", at = @At("HEAD"), order = 900, cancellable = true)
    private void logic$drawRandom(GuiGraphicsExtractor graphics, EditorNode node, CallbackInfo ci) {
        if (!logic$isRandom(node)) return;

        int x = screenX(node.x);
        int y = screenY(node.y);
        int w = Math.max(34, (int)Math.round(nodeWidth(node) * zoom));
        int h = Math.max(8, (int)Math.round(nodeHeight(node) * zoom));

        // Pads are square and consume the full node height, matching the user's hardware-style mockup.
        int pad = Math.min(Math.max(6, h), Math.max(6, w / 4));
        int bodyLeft = x + pad;
        int bodyRight = x + w - pad;
        if (bodyRight <= bodyLeft + 8) {
            pad = Math.max(4, (w - 10) / 4);
            bodyLeft = x + pad;
            bodyRight = x + w - pad;
        }

        List<PortSpec> inputs = safeInputs(node);
        List<PortSpec> outputs = safeOutputs(node);
        int triggerColor = inputs.isEmpty()
                ? 0xFFE05252
                : portDisplayColor(node, 0, inputs.getFirst(), true);
        int outputColor = outputs.isEmpty()
                ? logic$stateColor(valueForNode(node))
                : portDisplayColor(node, 0, outputs.getFirst(), false);

        // Central body first, then the two full-height electrical pads.
        graphics.fill(bodyLeft, y, bodyRight, y + h, LOGIC_BODY);
        graphics.fill(x, y, bodyLeft, y + h, triggerColor);
        graphics.fill(bodyRight, y, x + w, y + h, outputColor);

        int strip = Math.max(1, Math.min(h / 3, (int)Math.round(2.0 * zoom)));
        graphics.fill(bodyLeft, y, bodyRight, y + strip, LOGIC_TOP);

        // Dark seams make each pad read as a terminal rather than part of the label body.
        graphics.fill(bodyLeft - 1, y, bodyLeft + 1, y + h, LOGIC_DARK);
        graphics.fill(bodyRight - 1, y, bodyRight + 1, y + h, LOGIC_DARK);

        int outline = isNodeSelected(node.id) ? 0xFFFFFFFF : LOGIC_DARK;
        graphics.outline(x, y, w, h, outline);
        graphics.outline(x, y, pad, h, validTarget(true) ? 0xFFFFFFFF : LOGIC_DARK);
        graphics.outline(bodyRight, y, x + w - bodyRight, h, validTarget(false) ? 0xFFFFFFFF : LOGIC_DARK);

        String label = "RND " + node.randomChancePercent + "%";
        String state = logic$isHigh(valueForNode(node)) ? "1" : "0";

        // Reserve a small right-hand section of the grey body for the live output value.
        int stateWidth = Math.max(9, (int)Math.round(11.0 * zoom));
        int labelLeft = bodyLeft + Math.max(2, (int)Math.round(3.0 * zoom));
        int labelRight = Math.max(labelLeft + 4, bodyRight - stateWidth);
        logic$fitText(graphics, label, labelLeft, labelRight, y, y + h, LOGIC_TEXT);
        logic$fitText(graphics, state, labelRight, bodyRight, y, y + h, logic$stateColor(valueForNode(node)));

        ci.cancel();
    }

    @Inject(method = "drawPortHoverTooltip", at = @At("HEAD"), order = 900, cancellable = true)
    private void logic$randomTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        EditorNode node = logic$randomAt(mouseX, mouseY);
        if (node == null) return;

        LogicValue[] value = valueForNode(node);
        String out = logic$isHigh(value) ? "1" : "0";
        String text = "RANDOM " + node.randomChancePercent + "%   TRIGGER: rising edge 0 -> 1   OUT: " + out + "   double-click to edit";
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
        graphics.outline(x, y, boxW, boxH, LOGIC_TOP);
        graphics.text(font, text, x + padding, y + 5, 0xFFE8EDF3, false);
        ci.cancel();
    }

    @Unique private EditorNode logic$randomAt(double mouseX, double mouseY) {
        for (int i = document.nodes.size() - 1; i >= 0; i--) {
            EditorNode node = document.nodes.get(i);
            if (!logic$isRandom(node)) continue;
            int x = screenX(node.x);
            int y = screenY(node.y);
            int w = Math.max(34, (int)Math.round(nodeWidth(node) * zoom));
            int h = Math.max(8, (int)Math.round(nodeHeight(node) * zoom));
            if (mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h) return node;
        }
        return null;
    }

    @Unique private void logic$fitText(GuiGraphicsExtractor graphics, String text,
                                       int left, int right, int top, int bottom, int color) {
        Font font = Minecraft.getInstance().font;
        int rawW = Math.max(1, font.width(text));
        int regionW = Math.max(3, right - left);
        int regionH = Math.max(5, bottom - top);
        float scale = (float)Math.max(0.28, Math.min(1.0,
                Math.min((regionW - 2.0) / rawW, (regionH - 1.0) / 9.0)));
        float cx = (left + right) * 0.5f;
        float cy = (top + bottom) * 0.5f;
        graphics.pose().pushMatrix();
        graphics.pose().scale(scale);
        int tx = Math.round(cx / scale - rawW / 2f);
        int ty = Math.round(cy / scale - 4.5f);
        graphics.text(font, text, tx, ty, color, false);
        graphics.pose().popMatrix();
    }

    @Unique private static boolean logic$isRandom(EditorNode node) {
        return node.kind == NodeKind.CONSTANT && node.randomSource;
    }

    @Unique private static boolean logic$isHigh(LogicValue[] values) {
        if (values == null) return false;
        for (LogicValue value : values) if (value == LogicValue.HIGH) return true;
        return false;
    }

    @Unique private static int logic$stateColor(LogicValue[] values) {
        if (values == null || values.length == 0) return 0xFFE05252;
        for (LogicValue value : values) if (value == LogicValue.UNKNOWN) return 0xFFFFC857;
        return logic$isHigh(values) ? 0xFF55D96B : 0xFFE05252;
    }
}
