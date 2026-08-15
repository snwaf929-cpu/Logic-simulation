package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.v2.EditorHistoryAccess;
import com.foreverspark.logicsim.client.screen.v2.PcbLayerAccess;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.PortSpec;
import com.foreverspark.logicsim.editor.model.RoutePoint;
import com.foreverspark.logicsim.editor.model.WireConnection;
import com.foreverspark.logicsim.editor.model.WireLayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** PCB front/back copper view, layer-aware trace rendering/selection, and through-board vias. */
@Mixin(value = CircuitCanvasWidget.class, priority = 2400)
public abstract class CircuitCanvasPcbLayerMixin implements PcbLayerAccess {
    @Shadow private CircuitDocument document;
    @Shadow private WireConnection selectedWire;
    @Shadow private double lastWireClickX;
    @Shadow private double lastWireClickY;
    @Shadow @Final private Consumer<String> status;

    @Shadow private int screenX(double worldX) { throw new AssertionError(); }
    @Shadow private int screenY(double worldY) { throw new AssertionError(); }
    @Shadow private double nodeWidth(EditorNode node) { throw new AssertionError(); }
    @Shadow private double nodeHeight(EditorNode node) { throw new AssertionError(); }
    @Shadow private double portStep(EditorNode node) { throw new AssertionError(); }
    @Shadow private double centeredPortY(EditorNode node, int port, int count) { throw new AssertionError(); }
    @Shadow private List<PortSpec> safeInputs(EditorNode node) { throw new AssertionError(); }
    @Shadow private List<PortSpec> safeOutputs(EditorNode node) { throw new AssertionError(); }
    @Shadow private int wireColor(WireConnection wire) { throw new AssertionError(); }

    @Unique private WireLayer logic$pcbViewLayer = WireLayer.FRONT;

    @Override
    public WireLayer logic$currentPcbLayer() {
        return logic$pcbViewLayer;
    }

    @Override
    public void logic$flipPcbBoardSide() {
        logic$pcbViewLayer = logic$pcbViewLayer.opposite();
        status.accept("PCB VIEW: " + logic$pcbViewLayer + " copper — B flips board side; L moves selected trace here; V toggles a via at the clicked route corner");
    }

    @Override
    public boolean logic$assignSelectedWireToCurrentLayer() {
        if (selectedWire == null || !document.wires.contains(selectedWire)) {
            status.accept("PCB LAYER: select a wire first");
            return false;
        }
        if (selectedWire.layer() == logic$pcbViewLayer) {
            status.accept("Selected trace already starts on " + logic$pcbViewLayer + " copper");
            return true;
        }
        logic$checkpoint("Move trace to " + logic$pcbViewLayer);
        selectedWire.setLayer(logic$pcbViewLayer);
        logic$commitHistory();
        status.accept("Selected trace now starts on " + logic$pcbViewLayer + " copper" +
                (selectedWire.viaRouteIndices().isEmpty() ? "" : "; existing vias still alternate layers"));
        return true;
    }

    @Override
    public boolean logic$toggleViaOnSelectedWire() {
        if (selectedWire == null || !document.wires.contains(selectedWire)) {
            status.accept("VIA: select a routed wire first");
            return false;
        }
        if (selectedWire.routePoints().isEmpty()) {
            status.accept("VIA: this wire has no permanent route corner — add a corner first, then click near it and press V");
            return false;
        }
        int index = logic$nearestRouteIndex(selectedWire, lastWireClickX, lastWireClickY, 24.0);
        if (index < 0) {
            status.accept("VIA: click near a permanent route corner, then press V");
            return false;
        }
        logic$checkpoint("Toggle PCB via");
        boolean added = selectedWire.toggleViaAtRouteIndex(index);
        logic$commitHistory();
        RoutePoint point = selectedWire.routePoints().get(index);
        status.accept((added ? "VIA added" : "VIA removed") + " at " + Math.round(point.x()) + "," + Math.round(point.y())
                + (added ? " — copper switches side after this point" : ""));
        return true;
    }

    @Inject(method = "drawWire", at = @At("HEAD"), cancellable = true)
    private void logic$drawLayeredWire(GuiGraphicsExtractor graphics, WireConnection wire, CallbackInfo ci) {
        List<LogicLayerSegment> segments = logic$segments(wire);
        for (LogicLayerSegment segment : segments) {
            boolean visible = segment.layer() == logic$pcbViewLayer;
            boolean selected = selectedWire != null && selectedWire.equals(wire);
            int base = wireColor(wire);
            int color = visible ? (selected ? 0xFFFFFFFF : base) : logic$darken(base, selected ? 0.42 : 0.20);
            int thickness = visible ? (selected ? 3 : 2) : 1;
            logic$drawSegment(graphics, segment.a(), segment.b(), thickness, color);
        }
        logic$drawVias(graphics, wire);
        logic$drawBusWidth(graphics, wire, segments);
        ci.cancel();
    }

    @Inject(method = "wireAt", at = @At("HEAD"), cancellable = true)
    private void logic$layerAwareWireAt(double mouseX, double mouseY, CallbackInfoReturnable<WireConnection> cir) {
        for (int i = document.wires.size() - 1; i >= 0; i--) {
            WireConnection wire = document.wires.get(i);
            for (LogicLayerSegment segment : logic$segments(wire)) {
                if (segment.layer() != logic$pcbViewLayer) continue;
                double distance = logic$distanceToSegment(
                        mouseX, mouseY,
                        screenX(segment.a().x()), screenY(segment.a().y()),
                        screenX(segment.b().x()), screenY(segment.b().y()));
                if (distance <= 6.0) {
                    cir.setReturnValue(wire);
                    return;
                }
            }
        }
        cir.setReturnValue(null);
    }

    @Inject(method = "extractWidgetRenderState", at = @At("TAIL"))
    private void logic$drawPcbLayerHud(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        CircuitCanvasWidget self = (CircuitCanvasWidget)(Object)this;
        String label = "PCB " + logic$pcbViewLayer + "   B FLIP   L LAYER   V VIA";
        int width = Minecraft.getInstance().font.width(label) + 10;
        int x = self.getX() + self.getWidth() - width - 7;
        int y = self.getY() + 6;
        graphics.fill(x, y, x + width, y + 16, 0xE012171D);
        graphics.outline(x, y, width, 16, logic$pcbViewLayer == WireLayer.FRONT ? 0xFF5AA9FF : 0xFFD18A5A);
        graphics.text(Minecraft.getInstance().font, label, x + 5, y + 5, 0xFFE5EBF2, false);
    }

    @Inject(method = "setDocument(Lcom/foreverspark/logicsim/editor/model/CircuitDocument;Ljava/lang/String;)V", at = @At("RETURN"))
    private void logic$normalizePcbOnDocument(CircuitDocument replacement, String name, CallbackInfo ci) {
        if (document != null) document.normalize();
    }

    @Unique
    private List<LogicLayerSegment> logic$segments(WireConnection wire) {
        List<LogicLayerSegment> result = new ArrayList<>();
        LogicPoint start;
        LogicPoint end;
        try {
            EditorNode source = document.node(wire.sourceNodeId());
            EditorNode target = document.node(wire.targetNodeId());
            start = logic$outputPoint(source, wire.sourcePort());
            end = logic$inputPoint(target, wire.targetPort());
        } catch (RuntimeException ignored) {
            return result;
        }

        if (wire.routePoints().isEmpty()) {
            WireLayer layer = wire.layer();
            if (logic$aligned(start, end)) {
                logic$append(result, start, end, layer);
            } else {
                double middleX = logic$snap((start.x() + end.x()) * 0.5);
                LogicPoint a = new LogicPoint(middleX, start.y());
                LogicPoint b = new LogicPoint(middleX, end.y());
                logic$append(result, start, a, layer);
                logic$append(result, a, b, layer);
                logic$append(result, b, end, layer);
            }
            return result;
        }

        List<LogicPoint> direct = new ArrayList<>();
        direct.add(start);
        for (RoutePoint point : wire.routePoints()) direct.add(new LogicPoint(point.x(), point.y()));
        direct.add(end);

        for (int i = 0; i + 1 < direct.size(); i++) {
            LogicPoint a = direct.get(i);
            LogicPoint b = direct.get(i + 1);
            WireLayer layer = wire.segmentLayer(i);
            if (logic$aligned(a, b)) {
                logic$append(result, a, b, layer);
            } else {
                LogicPoint corner = new LogicPoint(b.x(), a.y());
                logic$append(result, a, corner, layer);
                logic$append(result, corner, b, layer);
            }
        }
        return result;
    }

    @Unique
    private LogicPoint logic$inputPoint(EditorNode node, int port) {
        double y;
        if (node.kind == NodeKind.CUSTOM_CHIP) y = centeredPortY(node, port, safeInputs(node).size());
        else if (node.kind == NodeKind.CONSTANT && node.randomSource) y = node.y + nodeHeight(node) * 0.5;
        else y = switch (node.kind) {
            case OUTPUT, PROBE, BUS, SPLITTER, BUS_SLICE, NET_LABEL -> node.y + nodeHeight(node) * 0.5;
            default -> node.y + 30.0 + port * portStep(node);
        };
        return new LogicPoint(logic$snap(node.x), logic$snap(y));
    }

    @Unique
    private LogicPoint logic$outputPoint(EditorNode node, int port) {
        double y;
        if (node.kind == NodeKind.CUSTOM_CHIP) y = centeredPortY(node, port, safeOutputs(node).size());
        else y = switch (node.kind) {
            case INPUT, NAND, CONSTANT, BUS, MERGER, NET_LABEL -> node.y + nodeHeight(node) * 0.5;
            default -> node.y + 30.0 + port * portStep(node);
        };
        return new LogicPoint(logic$snap(node.x + nodeWidth(node)), logic$snap(y));
    }

    @Unique
    private int logic$nearestRouteIndex(WireConnection wire, double mouseX, double mouseY, double maxDistance) {
        if (!Double.isFinite(mouseX) || !Double.isFinite(mouseY)) return -1;
        int best = -1;
        double bestDistance = maxDistance;
        for (int i = 0; i < wire.routePoints().size(); i++) {
            RoutePoint point = wire.routePoints().get(i);
            double distance = Math.hypot(mouseX - screenX(point.x()), mouseY - screenY(point.y()));
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    @Unique
    private void logic$drawVias(GuiGraphicsExtractor graphics, WireConnection wire) {
        for (int routeIndex : wire.viaRouteIndices()) {
            if (routeIndex < 0 || routeIndex >= wire.routePoints().size()) continue;
            RoutePoint point = wire.routePoints().get(routeIndex);
            int x = screenX(point.x());
            int y = screenY(point.y());
            graphics.fill(x - 4, y - 4, x + 5, y + 5, 0xFFFFC857);
            graphics.outline(x - 5, y - 5, 11, 11, 0xFF080A0D);
            graphics.fill(x - 1, y - 1, x + 2, y + 2, 0xFF11161B);
        }
    }

    @Unique
    private void logic$drawBusWidth(GuiGraphicsExtractor graphics, WireConnection wire, List<LogicLayerSegment> segments) {
        int width;
        try {
            EditorNode source = document.node(wire.sourceNodeId());
            List<PortSpec> outputs = safeOutputs(source);
            if (wire.sourcePort() < 0 || wire.sourcePort() >= outputs.size()) return;
            width = outputs.get(wire.sourcePort()).width();
        } catch (RuntimeException ignored) {
            return;
        }
        if (width <= 1) return;
        LogicLayerSegment chosen = null;
        for (LogicLayerSegment segment : segments) {
            if (segment.layer() == logic$pcbViewLayer) { chosen = segment; break; }
        }
        if (chosen == null && !segments.isEmpty()) chosen = segments.getFirst();
        if (chosen == null) return;
        int x = screenX((chosen.a().x() + chosen.b().x()) * 0.5);
        int y = screenY((chosen.a().y() + chosen.b().y()) * 0.5);
        String text = "[" + width + "]";
        graphics.fill(x - 2, y - 7, x + Minecraft.getInstance().font.width(text) + 3, y + 5, 0xE011161C);
        graphics.text(Minecraft.getInstance().font, text, x, y - 5, 0xFF8DB7FF, false);
    }

    @Unique
    private void logic$drawSegment(GuiGraphicsExtractor graphics, LogicPoint a, LogicPoint b, int thickness, int color) {
        int x1 = screenX(a.x()), y1 = screenY(a.y());
        int x2 = screenX(b.x()), y2 = screenY(b.y());
        if (Math.abs(a.y() - b.y()) < 0.001) {
            graphics.fill(Math.min(x1, x2), y1 - thickness / 2, Math.max(x1, x2) + 1, y1 + (thickness + 1) / 2, color);
        } else {
            graphics.fill(x1 - thickness / 2, Math.min(y1, y2), x1 + (thickness + 1) / 2, Math.max(y1, y2) + 1, color);
        }
    }

    @Unique private static void logic$append(List<LogicLayerSegment> result, LogicPoint a, LogicPoint b, WireLayer layer) {
        if (Math.hypot(b.x() - a.x(), b.y() - a.y()) > 0.001) result.add(new LogicLayerSegment(a, b, layer));
    }

    @Unique private static boolean logic$aligned(LogicPoint a, LogicPoint b) {
        return Math.abs(a.x() - b.x()) < 0.001 || Math.abs(a.y() - b.y()) < 0.001;
    }

    @Unique private static double logic$snap(double value) {
        return Math.round(value / 6.0) * 6.0;
    }

    @Unique private static int logic$darken(int color, double factor) {
        int r = (int)(((color >>> 16) & 0xFF) * factor);
        int g = (int)(((color >>> 8) & 0xFF) * factor);
        int b = (int)((color & 0xFF) * factor);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    @Unique private static double logic$distanceToSegment(double px, double py, double x1, double y1, double x2, double y2) {
        double dx = x2 - x1, dy = y2 - y1;
        if (dx == 0.0 && dy == 0.0) return Math.hypot(px - x1, py - y1);
        double t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy);
        t = Math.max(0.0, Math.min(1.0, t));
        return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
    }

    @Unique private void logic$checkpoint(String label) {
        Object self = this;
        if (self instanceof EditorHistoryAccess history) history.logic$checkpoint(label);
    }

    @Unique private void logic$commitHistory() {
        Object self = this;
        if (self instanceof EditorHistoryAccess history) history.logic$commitHistory();
    }

    @Unique private record LogicPoint(double x, double y) {}
    @Unique private record LogicLayerSegment(LogicPoint a, LogicPoint b, WireLayer layer) {}
}
