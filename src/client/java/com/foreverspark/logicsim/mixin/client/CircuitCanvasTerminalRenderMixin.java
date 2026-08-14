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

@Mixin(value = CircuitCanvasWidget.class, priority = 1500)
public abstract class CircuitCanvasTerminalRenderMixin {
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
    private void logic$terminal(GuiGraphicsExtractor graphics, EditorNode node, CallbackInfo ci) {
        if (node.kind == NodeKind.CONSTANT && node.randomSource) {
            logic$random(graphics, node);
            ci.cancel();
            return;
        }
        if (node.kind == NodeKind.CONSTANT && node.clockSource) {
            logic$clock(graphics, node);
            ci.cancel();
            return;
        }
        if (node.kind != NodeKind.INPUT && node.kind != NodeKind.OUTPUT) return;

        int x = screenX(node.x);
        int y = screenY(node.y);
        int w = Math.max(7, (int)Math.round(nodeWidth(node) * zoom));
        int h = Math.max(6, (int)Math.round(nodeHeight(node) * zoom));
        LogicValue[] values = valueForNode(node);
        int accent = nodeAccent(node);
        int border = isNodeSelected(node.id) ? 0xFFFFFFFF : logic$darken(accent, 0.90);

        // A terminal is a tiny hardware pin control, not a full component card.
        graphics.fill(x, y, x + w, y + h, 0xF010151B);
        graphics.outline(x, y, w, h, border);

        int indicator = Math.max(4, Math.min(h - 2, (int)Math.round(7.0 * zoom)));
        int inset = Math.max(1, (int)Math.round(3.0 * zoom));
        int sx = node.kind == NodeKind.INPUT
                ? x + inset
                : x + w - inset - indicator;
        int sy = y + (h - indicator) / 2;
        int stateColor = logic$valueColor(values);

        // INPUT: this square is the clickable saved ON/OFF switch.
        // OUTPUT: the same square is a read-only live state indicator.
        graphics.fill(sx, sy, sx + indicator, sy + indicator, logic$darken(stateColor, 0.42));
        graphics.outline(sx, sy, indicator, indicator, stateColor);
        if (logic$isHigh(values) && indicator >= 5) {
            graphics.fill(sx + 2, sy + 2, sx + indicator - 1, sy + indicator - 1, stateColor);
        }

        logic$drawPorts(graphics, node);
        ci.cancel();
    }

    @Unique private void logic$clock(GuiGraphicsExtractor graphics, EditorNode node) {
        int x = screenX(node.x), y = screenY(node.y);
        int w = Math.max(28, (int)Math.round(nodeWidth(node) * zoom));
        int h = Math.max(24, (int)Math.round(nodeHeight(node) * zoom));
        int accent = 0xFF5FA8FF;
        int border = isNodeSelected(node.id) ? 0xFFFFFFFF : accent;
        graphics.fill(x, y, x + w, y + h, 0xF0121820);
        graphics.outline(x, y, w, h, border);
        graphics.fill(x + 1, y + 1, x + w - 1, y + Math.max(3, (int)Math.round(4 * zoom)), accent);
        logic$smallText(graphics, "CLK", x + w / 2, y + Math.max(7, (int)Math.round(9 * zoom)), Math.max(8, w - 6), 0xFFF4F8FC);
        logic$smallText(graphics, EditorNode.formatFrequency(node.clockFrequencyHz), x + w / 2,
                y + h - Math.max(15, (int)Math.round(16 * zoom)), Math.max(8, w - 6), 0xFF9BCBFF);
        logic$drawPorts(graphics, node);
    }

    @Unique private void logic$random(GuiGraphicsExtractor graphics, EditorNode node) {
        int x = screenX(node.x), y = screenY(node.y);
        int w = Math.max(30, (int)Math.round(nodeWidth(node) * zoom));
        int h = Math.max(24, (int)Math.round(nodeHeight(node) * zoom));
        int accent = 0xFFB06CE8;
        int border = isNodeSelected(node.id) ? 0xFFFFFFFF : accent;
        LogicValue[] values = valueForNode(node);
        int stateColor = logic$valueColor(values);

        graphics.fill(x, y, x + w, y + h, 0xF015121A);
        graphics.outline(x, y, w, h, border);
        graphics.fill(x + 1, y + 1, x + w - 1, y + Math.max(3, (int)Math.round(4 * zoom)), accent);
        logic$smallText(graphics, "RANDOM", x + w / 2, y + Math.max(7, (int)Math.round(9 * zoom)), Math.max(8, w - 6), 0xFFF7F1FB);
        logic$smallText(graphics, node.randomChancePercent + "% -> 1", x + w / 2,
                y + h - Math.max(15, (int)Math.round(16 * zoom)), Math.max(8, w - 14), 0xFFC9A7E8);

        int lamp = Math.max(3, (int)Math.round(5 * zoom));
        int lx = x + w - lamp - Math.max(2, (int)Math.round(4 * zoom));
        int ly = y + Math.max(6, (int)Math.round(8 * zoom));
        graphics.fill(lx, ly, lx + lamp, ly + lamp, logic$darken(stateColor, 0.45));
        graphics.outline(lx, ly, lamp, lamp, stateColor);
        if (logic$isHigh(values) && lamp >= 4) graphics.fill(lx + 1, ly + 1, lx + lamp, ly + lamp, stateColor);

        logic$drawPorts(graphics, node);
    }

    @Unique private void logic$drawPorts(GuiGraphicsExtractor graphics, EditorNode node) {
        List<PortSpec> inputs = safeInputs(node);
        List<PortSpec> outputs = safeOutputs(node);
        for (int i = 0; i < inputs.size(); i++) {
            logic$pin(graphics, node.x, node.y + nodeHeight(node) * .5,
                    portDisplayColor(node, i, inputs.get(i), true), validTarget(true));
        }
        for (int i = 0; i < outputs.size(); i++) {
            logic$pin(graphics, node.x + nodeWidth(node), node.y + nodeHeight(node) * .5,
                    portDisplayColor(node, i, outputs.get(i), false), validTarget(false));
        }
    }

    @Unique private void logic$smallText(GuiGraphicsExtractor graphics, String text, int cx, int y, int maxW, int color) {
        int raw = Math.max(1, font().width(text));
        float scale = (float)Math.max(.28, Math.min(1.0, Math.min(zoom, maxW / (double)raw)));
        graphics.pose().pushMatrix();
        graphics.pose().scale(scale);
        graphics.text(font(), text, Math.round(cx / scale - raw / 2f), Math.round(y / scale), color, false);
        graphics.pose().popMatrix();
    }

    @Unique private void logic$pin(GuiGraphicsExtractor graphics, double wx, double wy, int color, boolean target) {
        int x = screenX(Math.round(wx / 6.0) * 6.0);
        int y = screenY(Math.round(wy / 6.0) * 6.0);
        int r = Math.max(1, (int)Math.round((target ? 4.0 : 3.0) * zoom));
        graphics.fill(x-r, y-r, x+r+1, y+r+1, color);
        graphics.outline(x-r-1, y-r-1, r*2+3, r*2+3, 0xFF090B0D);
    }

    @Unique private static boolean logic$isHigh(LogicValue[] values) {
        if (values == null) return false;
        for (LogicValue value : values) if (value == LogicValue.HIGH) return true;
        return false;
    }

    @Unique private static int logic$valueColor(LogicValue[] values) {
        if (values == null || values.length == 0) return 0xFF777777;
        boolean unknown = false;
        boolean high = false;
        boolean low = false;
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
}
