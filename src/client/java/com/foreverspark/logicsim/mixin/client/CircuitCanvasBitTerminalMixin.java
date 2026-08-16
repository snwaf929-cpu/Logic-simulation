package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.v2.EditorPinGeometry;
import com.foreverspark.logicsim.core.LogicValue;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
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

/**
 * I/O nodes are board terminals, not logic chips. Their pins use the exact shared screen-space pin geometry.
 */
@Mixin(value = CircuitCanvasWidget.class, priority = 900)
public abstract class CircuitCanvasBitTerminalMixin {
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
    @Shadow private int nodeAccent(EditorNode node) { throw new AssertionError(); }
    @Shadow private LogicValue[] valueForNode(EditorNode node) { throw new AssertionError(); }
    @Shadow private Font font() { throw new AssertionError(); }

    @Inject(method = "drawNode", at = @At("HEAD"), cancellable = true)
    private void logic$drawBitTerminal(GuiGraphicsExtractor graphics, EditorNode node, CallbackInfo ci) {
        if (!logic$isTerminal(node)) return;

        int x = screenX(node.x);
        int y = screenY(node.y);
        int w = Math.max(7, (int)Math.round(nodeWidth(node) * zoom));
        int h = Math.max(6, (int)Math.round(nodeHeight(node) * zoom));
        int accent = nodeAccent(node);
        int border = isNodeSelected(node.id) ? 0xFFFFFFFF : logic$darken(accent, 0.90);
        LogicValue[] values = valueForNode(node);
        int valueColor = logic$valueColor(values);

        graphics.fill(x, y, x + w, y + h, 0xF010151B);
        graphics.outline(x, y, w, h, border);

        int square = Math.max(4, Math.min(h - 2, (int)Math.round(7.0 * zoom)));
        int inset = Math.max(1, (int)Math.round(3.0 * zoom));
        int sx = node.kind == NodeKind.INPUT ? x + inset : x + w - inset - square;
        int sy = y + (h - square) / 2;

        graphics.fill(sx, sy, sx + square, sy + square, logic$darken(valueColor, 0.40));
        graphics.outline(sx, sy, square, square, valueColor);
        if (logic$isHigh(values) && square >= 5) graphics.fill(sx + 2, sy + 2, sx + square - 1, sy + square - 1, valueColor);

        List<PortSpec> inputs = safeInputs(node);
        for (int i = 0; i < inputs.size(); i++) {
            logic$drawPin(graphics, node.x, node.y + nodeHeight(node) * 0.5, inputs.get(i).width(),
                    portDisplayColor(node, i, inputs.get(i), true));
        }
        List<PortSpec> outputs = safeOutputs(node);
        for (int i = 0; i < outputs.size(); i++) {
            logic$drawPin(graphics, node.x + nodeWidth(node), node.y + nodeHeight(node) * 0.5, outputs.get(i).width(),
                    portDisplayColor(node, i, outputs.get(i), false));
        }
        ci.cancel();
    }

    @Inject(method = "drawPortHoverTooltip", at = @At("HEAD"), cancellable = true)
    private void logic$terminalTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        TerminalHit hit = logic$terminalAt(mouseX, mouseY);
        if (hit == null) return;

        EditorNode node = hit.node;
        String type = node.kind == NodeKind.INPUT ? "INPUT" : "OUTPUT";
        String state = logic$stateText(valueForNode(node));
        String text = node.displayName() + "   " + type + " [" + node.width + " bit]   " + state;
        int padding = 5;
        int boxW = font().width(text) + padding * 2;
        int boxH = 17;
        CircuitCanvasWidget self = (CircuitCanvasWidget)(Object)this;
        int x = Math.min(mouseX + 12, self.getX() + self.getWidth() - boxW - 3);
        int y = mouseY - boxH - 8;
        if (x < self.getX() + 3) x = self.getX() + 3;
        if (y < self.getY() + 3) y = mouseY + 12;
        y = Math.min(y, self.getY() + self.getHeight() - boxH - 3);

        graphics.fill(x, y, x + boxW, y + boxH, 0xF0181D23);
        graphics.outline(x, y, boxW, boxH, nodeAccent(node));
        graphics.text(font(), text, x + padding, y + 5, 0xFFE8EDF3, false);
        ci.cancel();
    }

    @Unique private TerminalHit logic$terminalAt(double mouseX, double mouseY) {
        for (int i = document.nodes.size() - 1; i >= 0; i--) {
            EditorNode node = document.nodes.get(i);
            if (!logic$isTerminal(node)) continue;
            int x = screenX(node.x);
            int y = screenY(node.y);
            int w = Math.max(7, (int)Math.round(nodeWidth(node) * zoom));
            int h = Math.max(6, (int)Math.round(nodeHeight(node) * zoom));
            if (mouseX >= x - 2 && mouseX <= x + w + 2 && mouseY >= y - 2 && mouseY <= y + h + 2) return new TerminalHit(node);

            List<PortSpec> specs = node.kind == NodeKind.INPUT ? safeOutputs(node) : safeInputs(node);
            int width = specs.isEmpty() ? 1 : specs.getFirst().width();
            double portX = node.kind == NodeKind.INPUT ? node.x + nodeWidth(node) : node.x;
            double portY = node.y + nodeHeight(node) * 0.5;
            double dx = mouseX - screenX(logic$snap(portX));
            double dy = mouseY - screenY(logic$snap(portY));
            if (EditorPinGeometry.contains(dx, dy, width)) return new TerminalHit(node);
        }
        return null;
    }

    @Unique private void logic$drawPin(GuiGraphicsExtractor graphics, double wx, double wy, int width, int color) {
        EditorPinGeometry.draw(graphics, screenX(logic$snap(wx)), screenY(logic$snap(wy)), width, color);
    }

    @Unique private static boolean logic$isTerminal(EditorNode node) {
        return node.kind == NodeKind.INPUT || node.kind == NodeKind.OUTPUT;
    }

    @Unique private static boolean logic$isHigh(LogicValue[] values) {
        if (values == null) return false;
        for (LogicValue value : values) if (value == LogicValue.HIGH) return true;
        return false;
    }

    @Unique private static String logic$stateText(LogicValue[] values) {
        if (values == null || values.length == 0) return "NO VALUE";
        for (LogicValue value : values) if (value == LogicValue.UNKNOWN) return "UNKNOWN";
        if (values.length == 1) return values[0] == LogicValue.HIGH ? "ON" : "OFF";
        long numeric = 0L;
        for (int bit = 0; bit < values.length; bit++) if (values[bit] == LogicValue.HIGH) numeric |= 1L << bit;
        int digits = Math.max(1, (values.length + 3) / 4);
        String raw = Long.toUnsignedString(numeric, 16).toUpperCase();
        if (raw.length() < digits) raw = "0".repeat(digits - raw.length()) + raw;
        if (raw.length() > digits) raw = raw.substring(raw.length() - digits);
        return "0x" + raw;
    }

    @Unique private static int logic$valueColor(LogicValue[] values) {
        if (values == null || values.length == 0) return 0xFF777777;
        boolean unknown = false, high = false, low = false;
        for (LogicValue value : values) {
            if (value == LogicValue.UNKNOWN) unknown = true;
            if (value == LogicValue.HIGH) high = true;
            if (value == LogicValue.LOW) low = true;
        }
        if (unknown) return 0xFFFFC857;
        if (high && low) return 0xFF5AA9FF;
        if (high) return 0xFF55D96B;
        return 0xFFE05252;
    }

    @Unique private static int logic$darken(int color, double factor) {
        int r = (int)(((color >>> 16) & 0xFF) * factor);
        int g = (int)(((color >>> 8) & 0xFF) * factor);
        int b = (int)((color & 0xFF) * factor);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    @Unique private static double logic$snap(double value) { return Math.round(value / 6.0) * 6.0; }
    @Unique private record TerminalHit(EditorNode node) {}
}
