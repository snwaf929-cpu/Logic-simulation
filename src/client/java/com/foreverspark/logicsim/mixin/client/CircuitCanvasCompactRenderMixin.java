package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.core.LogicValue;
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

@Mixin(CircuitCanvasWidget.class)
public abstract class CircuitCanvasCompactRenderMixin {
    @Shadow private double zoom;

    @Shadow private int screenX(double worldX) { throw new AssertionError(); }
    @Shadow private int screenY(double worldY) { throw new AssertionError(); }
    @Shadow private double nodeWidth(EditorNode node) { throw new AssertionError(); }
    @Shadow private double nodeHeight(EditorNode node) { throw new AssertionError(); }
    @Shadow private double portStep(EditorNode node) { throw new AssertionError(); }
    @Shadow private List<PortSpec> safeInputs(EditorNode node) { throw new AssertionError(); }
    @Shadow private List<PortSpec> safeOutputs(EditorNode node) { throw new AssertionError(); }
    @Shadow private int portDisplayColor(EditorNode node, int port, PortSpec spec, boolean input) { throw new AssertionError(); }
    @Shadow private boolean validTarget(boolean input) { throw new AssertionError(); }
    @Shadow private boolean isNodeSelected(int nodeId) { throw new AssertionError(); }
    @Shadow private int nodeAccent(EditorNode node) { throw new AssertionError(); }
    @Shadow private LogicValue[] valueForNode(EditorNode node) { throw new AssertionError(); }
    @Shadow private Font font() { throw new AssertionError(); }

    @Inject(method = "drawNode", at = @At("HEAD"), cancellable = true)
    private void logic$drawBuiltIn(GuiGraphicsExtractor graphics, EditorNode node, CallbackInfo ci) {
        if (node.kind == NodeKind.CUSTOM_CHIP) return;

        List<PortSpec> inputs = safeInputs(node);
        List<PortSpec> outputs = safeOutputs(node);
        int x = screenX(node.x);
        int y = screenY(node.y);
        int w = Math.max(18, (int) Math.round(nodeWidth(node) * zoom));
        int h = Math.max(18, (int) Math.round(nodeHeight(node) * zoom));

        if (node.kind == NodeKind.BUS) {
            logic$drawConnector(graphics, node, inputs, outputs, x, y, w, h);
            ci.cancel();
            return;
        }

        int accent = nodeAccent(node);
        int border = isNodeSelected(node.id) ? 0xFFFFFFFF : logic$darken(accent, 0.88);
        graphics.fill(x, y, x + w, y + h, 0xF0191F26);
        graphics.outline(x, y, w, h, border);
        if (w > 4 && h > 4) graphics.outline(x + 1, y + 1, w - 2, h - 2, 0xFF252E37);
        graphics.fill(x + 1, y + 1, x + w - 1, y + Math.max(3, (int) Math.round(4 * zoom)), accent);

        logic$centerText(graphics, logic$title(node), x, w,
                y + Math.max(8, (int) Math.round(9 * zoom)), 0xFFF2F5F8);

        if (node.kind == NodeKind.INPUT || node.kind == NodeKind.OUTPUT
                || node.kind == NodeKind.CONSTANT || node.kind == NodeKind.PROBE) {
            LogicValue[] value = valueForNode(node);
            logic$centerText(graphics, logic$formatValue(value), x, w,
                    y + h - Math.max(15, (int) Math.round(16 * zoom)), logic$valueColor(value));
        }

        if (node.kind == NodeKind.SPLITTER || node.kind == NodeKind.MERGER) {
            String mode = node.kind == NodeKind.SPLITTER ? "BUS→BITS" : "BITS→BUS";
            logic$centerText(graphics, mode + " [" + node.width + "]", x, w,
                    y + Math.max(20, (int) Math.round(21 * zoom)), 0xFF8FA2B6);
        }

        for (int port = 0; port < inputs.size(); port++) {
            LogicPoint point = logic$inputPoint(node, port);
            logic$drawPort(graphics, point,
                    portDisplayColor(node, port, inputs.get(port), true), validTarget(true));
        }
        for (int port = 0; port < outputs.size(); port++) {
            LogicPoint point = logic$outputPoint(node, port);
            logic$drawPort(graphics, point,
                    portDisplayColor(node, port, outputs.get(port), false), validTarget(false));
        }

        ci.cancel();
    }

    @Unique
    private void logic$drawConnector(GuiGraphicsExtractor graphics, EditorNode node,
                                     List<PortSpec> inputs, List<PortSpec> outputs,
                                     int x, int y, int w, int h) {
        int border = isNodeSelected(node.id) ? 0xFFFFFFFF : 0xFF4B5662;
        graphics.fill(x, y, x + w, y + h, 0xFF090C10);
        graphics.outline(x, y, w, h, border);
        if (w > 4 && h > 4) graphics.outline(x + 1, y + 1, w - 2, h - 2, 0xFF151B21);

        String center = node.width <= 1 ? "1" : "[" + node.width + "]";
        logic$centerText(graphics, center, x, w,
                y + Math.max(6, (h - 8) / 2), node.width <= 1 ? 0xFFA8B1BC : 0xFF8DB7FF);

        for (int port = 0; port < inputs.size(); port++) {
            LogicPoint point = logic$inputPoint(node, port);
            logic$drawPort(graphics, point,
                    portDisplayColor(node, port, inputs.get(port), true), validTarget(true));
        }
        for (int port = 0; port < outputs.size(); port++) {
            LogicPoint point = logic$outputPoint(node, port);
            logic$drawPort(graphics, point,
                    portDisplayColor(node, port, outputs.get(port), false), validTarget(false));
        }
    }

    @Unique
    private LogicPoint logic$inputPoint(EditorNode node, int port) {
        double y = switch (node.kind) {
            case OUTPUT, PROBE, BUS, SPLITTER -> node.y + nodeHeight(node) * 0.5;
            default -> node.y + 30.0 + port * portStep(node);
        };
        return new LogicPoint(logic$snap(node.x), logic$snap(y));
    }

    @Unique
    private LogicPoint logic$outputPoint(EditorNode node, int port) {
        double y = switch (node.kind) {
            case INPUT, NAND, CONSTANT, BUS, MERGER -> node.y + nodeHeight(node) * 0.5;
            default -> node.y + 30.0 + port * portStep(node);
        };
        return new LogicPoint(logic$snap(node.x + nodeWidth(node)), logic$snap(y));
    }

    @Unique
    private void logic$drawPort(GuiGraphicsExtractor graphics, LogicPoint point,
                                int color, boolean wiringTarget) {
        int x = screenX(point.x);
        int y = screenY(point.y);
        int r = Math.max(2, (int) Math.round((wiringTarget ? 4.0 : 3.0) * zoom));
        graphics.fill(x - r, y - r, x + r + 1, y + r + 1, color);
        graphics.outline(x - r - 1, y - r - 1, r * 2 + 3, r * 2 + 3, 0xFF090B0D);
    }

    @Unique
    private void logic$centerText(GuiGraphicsExtractor graphics, String text,
                                  int x, int w, int y, int color) {
        String shown = logic$fitText(text, Math.max(6, w - 10));
        graphics.text(font(), shown, x + (w - font().width(shown)) / 2, y, color, false);
    }

    @Unique
    private String logic$fitText(String text, int maxPixels) {
        if (text == null) return "";
        if (font().width(text) <= maxPixels) return text;
        String suffix = "…";
        int end = text.length();
        while (end > 1 && font().width(text.substring(0, end - 1) + suffix) > maxPixels) end--;
        return text.substring(0, Math.max(0, end - 1)) + suffix;
    }

    @Unique
    private String logic$title(EditorNode node) {
        if (node.kind == NodeKind.INPUT)
            return node.label == null || node.label.isBlank() ? "INPUT" : node.label;
        if (node.kind == NodeKind.OUTPUT)
            return node.label == null || node.label.isBlank() ? "OUTPUT" : node.label;
        return switch (node.kind) {
            case NAND -> "NAND";
            case CONSTANT -> "CONST";
            case PROBE -> "PROBE";
            case SPLITTER -> "SPLIT";
            case MERGER -> "MERGE";
            default -> node.displayName();
        };
    }

    @Unique
    private static int logic$valueColor(LogicValue[] values) {
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

    @Unique
    private static String logic$formatValue(LogicValue[] values) {
        if (values == null || values.length == 0) return "-";
        for (LogicValue value : values) {
            if (value == LogicValue.UNKNOWN) return values.length == 1 ? "X" : "X[" + values.length + "]";
        }
        long numeric = 0L;
        for (int bit = 0; bit < values.length; bit++) {
            if (values[bit] == LogicValue.HIGH) numeric |= (1L << bit);
        }
        if (values.length == 1) return Long.toString(numeric);
        int digits = Math.max(1, (values.length + 3) / 4);
        String raw = Long.toUnsignedString(numeric, 16).toUpperCase();
        if (raw.length() < digits) raw = "0".repeat(digits - raw.length()) + raw;
        if (raw.length() > digits) raw = raw.substring(raw.length() - digits);
        return "0x" + raw;
    }

    @Unique
    private static int logic$darken(int color, double factor) {
        int r = (int) (((color >>> 16) & 0xFF) * factor);
        int g = (int) (((color >>> 8) & 0xFF) * factor);
        int b = (int) ((color & 0xFF) * factor);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    @Unique private static double logic$snap(double value) { return Math.round(value / 6.0) * 6.0; }
    @Unique private record LogicPoint(double x, double y) {}
}
