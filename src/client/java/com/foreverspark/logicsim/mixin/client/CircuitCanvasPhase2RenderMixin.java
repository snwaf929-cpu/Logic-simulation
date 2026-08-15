package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.v2.EditorGrid;
import com.foreverspark.logicsim.client.screen.v2.EditorPinGeometry;
import com.foreverspark.logicsim.editor.model.BusSliceOutput;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.PortSpec;
import com.foreverspark.logicsim.editor.model.WireConnection;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Clean schematic bodies and exact floating tooltips for Phase 2 routing infrastructure. */
@Mixin(value = CircuitCanvasWidget.class, priority = 1900)
public abstract class CircuitCanvasPhase2RenderMixin {
    @Shadow private CircuitDocument document;
    @Shadow private double zoom;
    @Shadow private int screenX(double worldX) { throw new AssertionError(); }
    @Shadow private int screenY(double worldY) { throw new AssertionError(); }
    @Shadow private double nodeWidth(EditorNode node) { throw new AssertionError(); }
    @Shadow private double nodeHeight(EditorNode node) { throw new AssertionError(); }
    @Shadow private double portStep(EditorNode node) { throw new AssertionError(); }
    @Shadow private double centeredPortY(EditorNode node, int port, int count) { throw new AssertionError(); }
    @Shadow private List<PortSpec> safeInputs(EditorNode node) { throw new AssertionError(); }
    @Shadow private List<PortSpec> safeOutputs(EditorNode node) { throw new AssertionError(); }
    @Shadow private int portDisplayColor(EditorNode node, int port, PortSpec spec, boolean input) { throw new AssertionError(); }
    @Shadow private boolean isNodeSelected(int nodeId) { throw new AssertionError(); }
    @Shadow private Font font() { throw new AssertionError(); }

    @Inject(method = "drawNode", at = @At("HEAD"), cancellable = true)
    private void logic$drawPhase2Node(GuiGraphicsExtractor graphics, EditorNode node, CallbackInfo ci) {
        if (node.kind != NodeKind.BUS_SLICE && node.kind != NodeKind.NET_LABEL) return;
        int x = screenX(node.x), y = screenY(node.y);
        int w = Math.max(20, (int)Math.round(nodeWidth(node) * zoom));
        int h = Math.max(16, (int)Math.round(nodeHeight(node) * zoom));
        int accent = node.kind == NodeKind.BUS_SLICE ? 0xFF55AFC2 : 0xFF8E73D8;
        int border = isNodeSelected(node.id) ? 0xFFFFFFFF : accent;
        graphics.fill(x, y, x + w, y + h, 0xF0131920);
        graphics.outline(x, y, w, h, border);
        graphics.fill(x + 1, y + 1, x + w - 1, y + Math.max(3, (int)Math.round(4 * zoom)), accent);

        if (node.kind == NodeKind.BUS_SLICE) {
            logic$center(graphics, "BUS SLICE [" + node.width + "]", x, w, y + 8, 0xFFF0F6F8);
            String summary = logic$sliceSummary(node);
            logic$center(graphics, summary, x, w, y + Math.max(20, h - 14), 0xFF8FC7D2);
        } else {
            String name = node.label == null || node.label.isBlank() ? "NET" + node.id : node.label.trim();
            logic$center(graphics, "NET LABEL", x, w, y + 8, 0xFFD9CCFF);
            logic$center(graphics, name, x, w, y + Math.max(20, (h - 9) / 2), 0xFFF2ECFF);
            String width = "[" + node.width + "]";
            graphics.text(font(), width, x + w - font().width(width) - 4, y + h - 11, 0xFFAA96E5, false);
        }
        ci.cancel();
    }

    @Inject(method = "drawPortHoverTooltip", at = @At("HEAD"), cancellable = true)
    private void logic$floatingTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        FloatingHit hit = logic$floatingInputAt(mouseX, mouseY);
        if (hit == null) return;
        String name = hit.spec().name() == null || hit.spec().name().isBlank() ? "INPUT" : hit.spec().name();
        String text = name + "   [" + hit.spec().width() + " bit]   FLOATING -> default 0";
        int boxW = font().width(text) + 10;
        int boxH = 17;
        CircuitCanvasWidget self = (CircuitCanvasWidget)(Object)this;
        int pinX = screenX(hit.point().x());
        int pinY = screenY(hit.point().y());
        int x = Math.max(self.getX() + 3, Math.min(pinX - boxW - 12, self.getX() + self.getWidth() - boxW - 3));
        int y = Math.max(self.getY() + 3, Math.min(pinY - boxH / 2, self.getY() + self.getHeight() - boxH - 3));
        graphics.fill(x, y, x + boxW, y + boxH, 0xF01B1811);
        graphics.outline(x, y, boxW, boxH, 0xFFFFB347);
        graphics.text(font(), text, x + 5, y + 5, 0xFFFFD08A, false);
        ci.cancel();
    }

    @Unique
    private FloatingHit logic$floatingInputAt(double mx, double my) {
        for (int n = document.nodes.size() - 1; n >= 0; n--) {
            EditorNode node = document.nodes.get(n);
            List<PortSpec> inputs = safeInputs(node);
            for (int port = 0; port < inputs.size(); port++) {
                if (!logic$isFloating(node, port)) continue;
                Point point = logic$inputPoint(node, port);
                if (EditorPinGeometry.contains(mx - screenX(point.x()), my - screenY(point.y()), inputs.get(port).width())) {
                    return new FloatingHit(inputs.get(port), point);
                }
            }
        }
        return null;
    }

    @Unique
    private boolean logic$isFloating(EditorNode node, int port) {
        if (node.kind == NodeKind.NET_LABEL) {
            String name = node.label == null ? "" : node.label.trim();
            for (EditorNode candidate : document.nodes) {
                if (candidate.kind != NodeKind.NET_LABEL || candidate.label == null || !candidate.label.trim().equalsIgnoreCase(name)) continue;
                if (logic$hasIncoming(candidate.id, 0)) return false;
            }
            return true;
        }
        return !logic$hasIncoming(node.id, port);
    }

    @Unique private boolean logic$hasIncoming(int nodeId, int port) {
        for (WireConnection wire : document.wires) if (wire.targetNodeId() == nodeId && wire.targetPort() == port) return true;
        return false;
    }

    @Unique
    private Point logic$inputPoint(EditorNode node, int port) {
        double py;
        if (node.kind == NodeKind.CUSTOM_CHIP) py = centeredPortY(node, port, safeInputs(node).size());
        else py = switch (node.kind) {
            case OUTPUT, PROBE, BUS, SPLITTER, BUS_SLICE, NET_LABEL -> node.y + nodeHeight(node) * 0.5;
            default -> node.y + 30.0 + port * portStep(node);
        };
        return new Point(EditorGrid.snap(node.x), EditorGrid.snap(py));
    }

    @Unique
    private String logic$sliceSummary(EditorNode node) {
        List<BusSliceOutput> slices = node.normalizedSlices();
        if (slices.isEmpty()) return "NO RANGES";
        BusSliceOutput first = slices.getFirst();
        String text = first.name + "[" + (first.startBit + first.width - 1) + ":" + first.startBit + "]";
        if (slices.size() > 1) text += " +" + (slices.size() - 1);
        return text;
    }

    @Unique private void logic$center(GuiGraphicsExtractor graphics, String text, int x, int w, int y, int color) {
        String shown = text;
        while (shown.length() > 1 && font().width(shown) > w - 8) shown = shown.substring(0, shown.length() - 1);
        graphics.text(font(), shown, x + Math.max(3, (w - font().width(shown)) / 2), y, color, false);
    }

    @Unique private record Point(double x, double y) {}
    @Unique private record FloatingHit(PortSpec spec, Point point) {}
}
