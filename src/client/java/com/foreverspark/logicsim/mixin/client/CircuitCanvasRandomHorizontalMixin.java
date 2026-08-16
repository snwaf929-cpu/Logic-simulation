package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.v2.EditorPinGeometry;
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

/** RANDOM source renderer with screen-space invariant text and ordinary shared pin geometry. */
@Mixin(value = CircuitCanvasWidget.class, priority = 2500)
public abstract class CircuitCanvasRandomHorizontalMixin {
    @Unique private static final int BODY = 0xF0191F26;
    @Unique private static final int TOP = 0xFFD29A45;
    @Unique private static final int BORDER = 0xFF805E2E;
    @Unique private static final int TEXT = 0xFFF2F5F8;

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

    @Inject(method = "drawNode", at = @At("HEAD"), order = 900, cancellable = true)
    private void logic$drawRandom(GuiGraphicsExtractor graphics, EditorNode node, CallbackInfo ci) {
        if (!logic$isRandom(node)) return;

        int x = screenX(node.x);
        int y = screenY(node.y);
        int w = Math.max(34, (int)Math.round(nodeWidth(node) * zoom));
        int h = Math.max(8, (int)Math.round(nodeHeight(node) * zoom));

        graphics.fill(x, y, x + w, y + h, BODY);
        int strip = Math.max(1, Math.min(h / 3, (int)Math.round(2.0 * zoom)));
        graphics.fill(x, y, x + w, y + strip, TOP);
        graphics.outline(x, y, w, h, isNodeSelected(node.id) ? 0xFFFFFFFF : BORDER);

        String label = "RND " + node.randomChancePercent + "%";
        LogicValue[] values = valueForNode(node);
        String state = logic$isHigh(values) ? "1" : "0";
        int stateColor = logic$stateColor(values);

        int stateWidth = 12;
        logic$fitText(graphics, label, x + 4, Math.max(x + 8, x + w - stateWidth), y, y + h, TEXT);
        logic$fitText(graphics, state, Math.max(x + 4, x + w - stateWidth), x + w - 2, y, y + h, stateColor);

        List<PortSpec> inputs = safeInputs(node);
        if (!inputs.isEmpty()) {
            logic$standardPin(graphics, node.x, node.y + nodeHeight(node) * 0.5,
                    inputs.getFirst().width(), portDisplayColor(node, 0, inputs.getFirst(), true));
        }
        List<PortSpec> outputs = safeOutputs(node);
        if (!outputs.isEmpty()) {
            logic$standardPin(graphics, node.x + nodeWidth(node), node.y + nodeHeight(node) * 0.5,
                    outputs.getFirst().width(), portDisplayColor(node, 0, outputs.getFirst(), false));
        }

        ci.cancel();
    }

    @Inject(method = "drawPortHoverTooltip", at = @At("HEAD"), order = 900, cancellable = true)
    private void logic$randomTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        EditorNode node = logic$randomAt(mouseX, mouseY);
        if (node == null) return;

        LogicValue[] value = valueForNode(node);
        String out = logic$isHigh(value) ? "1" : "0";
        String text = "RANDOM " + node.randomChancePercent + "%   TRIGGER 0 -> 1   OUT " + out + "   select + E to edit";
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
        graphics.outline(x, y, boxW, boxH, TOP);
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

    @Unique private void logic$standardPin(GuiGraphicsExtractor graphics, double worldX, double worldY,
                                           int width, int color) {
        double snappedX = Math.round(worldX / 6.0) * 6.0;
        double snappedY = Math.round(worldY / 6.0) * 6.0;
        EditorPinGeometry.draw(graphics, screenX(snappedX), screenY(snappedY), width, color);
    }

    /** Fixed-size font; narrow regions truncate rather than scale. */
    @Unique private void logic$fitText(GuiGraphicsExtractor graphics, String text,
                                       int left, int right, int top, int bottom, int color) {
        Font font = Minecraft.getInstance().font;
        int regionW = Math.max(0, right - left - 2);
        int regionH = Math.max(0, bottom - top);
        if (regionW <= 0 || regionH <= 2) return;
        String shown = logic$fit(font, text, regionW);
        if (shown.isEmpty()) return;
        int tx = left + 1 + Math.max(0, (regionW - font.width(shown)) / 2);
        int ty = top + Math.max(0, (regionH - 8) / 2);
        graphics.text(font, shown, tx, ty, color, false);
    }

    @Unique private static String logic$fit(Font font, String text, int maxWidth) {
        if (text == null || maxWidth <= 0) return "";
        if (font.width(text) <= maxWidth) return text;
        String suffix = "…";
        if (font.width(suffix) > maxWidth) return "";
        int end = text.length();
        while (end > 0 && font.width(text.substring(0, end) + suffix) > maxWidth) end--;
        return text.substring(0, end) + suffix;
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
