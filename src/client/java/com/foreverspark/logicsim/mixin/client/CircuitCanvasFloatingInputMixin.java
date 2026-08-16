package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.v2.EditorGrid;
import com.foreverspark.logicsim.client.screen.v2.EditorPinGeometry;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.PortSpec;
import com.foreverspark.logicsim.editor.model.WireConnection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/** Explicit visual diagnostics for the compiler's ordinary unconnected-input LOW semantics. */
@Mixin(value = CircuitCanvasWidget.class, priority = 2350)
public abstract class CircuitCanvasFloatingInputMixin {
    @Shadow private CircuitDocument document;
    @Shadow private double zoom;
    @Shadow private int screenX(double worldX) { throw new AssertionError(); }
    @Shadow private int screenY(double worldY) { throw new AssertionError(); }
    @Shadow private double nodeHeight(EditorNode node) { throw new AssertionError(); }
    @Shadow private double portStep(EditorNode node) { throw new AssertionError(); }
    @Shadow private double centeredPortY(EditorNode node, int port, int count) { throw new AssertionError(); }
    @Shadow private List<PortSpec> safeInputs(EditorNode node) { throw new AssertionError(); }

    @Inject(method = "extractWidgetRenderState", at = @At("TAIL"))
    private void logic$drawFloatingInputWarnings(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (document == null) return;
        List<FloatingPin> floating = logic$floatingPins();
        if (floating.isEmpty()) return;

        CircuitCanvasWidget self = (CircuitCanvasWidget)(Object)this;
        var font = Minecraft.getInstance().font;
        String hud = "FLOATING -> 0   " + floating.size() + " INPUT" + (floating.size() == 1 ? "" : "S");
        int hudWidth = font.width(hud) + 10;
        int hx = self.getX() + 7;
        int hy = self.getY() + 7;
        graphics.fill(hx, hy, hx + hudWidth, hy + 16, 0xE51A1710);
        graphics.outline(hx, hy, hudWidth, 16, 0xFFFFB347);
        graphics.text(font, hud, hx + 5, hy + 5, 0xFFFFC56F, false);

        // At normal/close zoom, annotate the actual pin as well as outlining it in the Editor V2 pin renderer.
        if (zoom < 0.72) return;
        for (FloatingPin pin : floating) {
            int x = screenX(pin.x());
            int y = screenY(pin.y());
            int half = EditorPinGeometry.halfSize(pin.width());
            String text = "F->0";
            int tx = x + half + 6;
            int ty = y - 4;
            if (tx + font.width(text) + 3 > self.getX() + self.getWidth()) tx = x - half - font.width(text) - 8;
            graphics.fill(tx - 2, ty - 2, tx + font.width(text) + 3, ty + 10, 0xC918160F);
            graphics.text(font, text, tx, ty, 0xFFFFB347, false);
        }
    }

    @Unique
    private List<FloatingPin> logic$floatingPins() {
        ArrayList<FloatingPin> result = new ArrayList<>();
        for (EditorNode node : document.nodes) {
            List<PortSpec> inputs = safeInputs(node);
            for (int port = 0; port < inputs.size(); port++) {
                if (!logic$defaultsLowWhenUnwired(node, port)) continue;
                if (logic$isElectricallyDriven(node, port)) continue;
                PortPoint point = logic$inputPoint(node, port, inputs.size());
                result.add(new FloatingPin(node.id, port, inputs.get(port).width(), point.x(), point.y()));
            }
        }
        return List.copyOf(result);
    }

    @Unique
    private boolean logic$defaultsLowWhenUnwired(EditorNode node, int port) {
        // CLOCK ENABLE is special: CircuitTimingController only binds it when a wire exists. No wire = free-run.
        if (node.kind == NodeKind.CONSTANT && node.clockSource && !node.randomSource && port == 0) return false;
        return true;
    }

    @Unique
    private boolean logic$isElectricallyDriven(EditorNode node, int port) {
        if (node.kind == NodeKind.NET_LABEL) {
            String name = node.label == null ? "" : node.label.trim();
            for (EditorNode candidate : document.nodes) {
                if (candidate.kind != NodeKind.NET_LABEL || candidate.label == null
                        || !candidate.label.trim().equalsIgnoreCase(name)) continue;
                if (logic$hasIncoming(candidate.id, 0)) return true;
            }
            return false;
        }
        return logic$hasIncoming(node.id, port);
    }

    @Unique
    private boolean logic$hasIncoming(int nodeId, int port) {
        for (WireConnection wire : document.wires) {
            if (wire.targetNodeId() == nodeId && wire.targetPort() == port) return true;
        }
        return false;
    }

    @Unique
    private PortPoint logic$inputPoint(EditorNode node, int port, int inputCount) {
        double y;
        if (node.kind == NodeKind.CUSTOM_CHIP) y = centeredPortY(node, port, inputCount);
        else if (node.kind == NodeKind.CONSTANT && node.randomSource) y = node.y + nodeHeight(node) * 0.5;
        else y = switch (node.kind) {
            case OUTPUT, PROBE, BUS, SPLITTER, BUS_SLICE, NET_LABEL -> node.y + nodeHeight(node) * 0.5;
            default -> node.y + 30.0 + port * portStep(node);
        };
        return new PortPoint(EditorGrid.snap(node.x), EditorGrid.snap(y));
    }

    @Unique private record PortPoint(double x, double y) {}
    @Unique private record FloatingPin(int nodeId, int port, int width, double x, double y) {}
}
