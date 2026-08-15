package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.v2.EditorGrid;
import com.foreverspark.logicsim.client.screen.v2.EditorHistoryAccess;
import com.foreverspark.logicsim.client.screen.v2.EditorPinGeometry;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.PortSpec;
import com.foreverspark.logicsim.editor.model.RoutePoint;
import com.foreverspark.logicsim.editor.model.WireConnection;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Phase 2 click-based PCB routing.
 * A pin starts a route, each empty click commits a snapped corner, and an opposite pin finishes it.
 * Clicking an existing trace establishes an anchor; the next empty click inserts a permanent detour.
 */
@Mixin(value = CircuitCanvasWidget.class, priority = 2200)
public abstract class CircuitCanvasWiringV2Mixin {
    @Shadow private CircuitDocument document;
    @Shadow private WireConnection selectedWire;
    @Shadow private boolean wireEditMode;
    @Shadow @Final private Consumer<String> status;
    @Shadow private EditorNode nodeAt(double mouseX, double mouseY) { throw new AssertionError(); }
    @Shadow private WireConnection wireAt(double mouseX, double mouseY) { throw new AssertionError(); }
    @Shadow private void recompile() { throw new AssertionError(); }
    @Shadow private double worldX(double screenX) { throw new AssertionError(); }
    @Shadow private double worldY(double screenY) { throw new AssertionError(); }
    @Shadow private int screenX(double worldX) { throw new AssertionError(); }
    @Shadow private int screenY(double worldY) { throw new AssertionError(); }
    @Shadow private double nodeWidth(EditorNode node) { throw new AssertionError(); }
    @Shadow private double nodeHeight(EditorNode node) { throw new AssertionError(); }
    @Shadow private double portStep(EditorNode node) { throw new AssertionError(); }
    @Shadow private double centeredPortY(EditorNode node, int port, int count) { throw new AssertionError(); }
    @Shadow private List<PortSpec> safeInputs(EditorNode node) { throw new AssertionError(); }
    @Shadow private List<PortSpec> safeOutputs(EditorNode node) { throw new AssertionError(); }
    @Shadow private void ensureEditableRoute(WireConnection wire) { throw new AssertionError(); }

    @Unique private LogicRoute logic$route;
    @Unique private LogicWireAnchor logic$wireAnchor;

    @Inject(method = "onClick", at = @At("HEAD"), cancellable = true)
    private void logic$routeClick(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        if (event.button() != 0) return;
        if ((event.modifiers() & GLFW.GLFW_MOD_ALT) != 0) return;

        LogicPinHit pin = logic$pinAt(event.x(), event.y());
        if (logic$wireAnchor != null && pin == null) {
            if (nodeAt(event.x(), event.y()) == null) {
                logic$insertDetour(event.x(), event.y());
                ci.cancel();
            }
            return;
        }

        if (pin != null) {
            if (logic$route == null) logic$beginRoute(pin);
            else logic$advanceOrFinish(pin);
            ci.cancel();
            return;
        }

        if (logic$route != null) {
            if (nodeAt(event.x(), event.y()) == null) {
                double x = EditorGrid.snap(worldX(event.x()));
                double y = EditorGrid.snap(worldY(event.y()));
                LogicPoint point = new LogicPoint(x, y);
                if (logic$route.corners().isEmpty() || !logic$route.corners().getLast().equals(point)) {
                    logic$route.corners().add(point);
                    status.accept("WIRE: corner " + logic$route.corners().size() + " placed — click another corner or a compatible pin");
                }
                ci.cancel();
            } else {
                status.accept("WIRE: click empty grid for a corner or a compatible terminal to finish");
                ci.cancel();
            }
            return;
        }

        if (nodeAt(event.x(), event.y()) == null) {
            WireConnection wire = wireAt(event.x(), event.y());
            if (wire != null) {
                logic$beginWireAnchor(wire, event.x(), event.y());
                ci.cancel();
            }
        }
    }

    @Unique
    private void logic$beginRoute(LogicPinHit pin) {
        logic$checkpoint("Create routed wire");
        logic$wireAnchor = null;
        logic$route = new LogicRoute(pin, new ArrayList<>());
        selectedWire = null;
        wireEditMode = false;
        status.accept("WIRE " + pin.spec().width() + "-bit: click empty grid to place corners, then click a compatible "
                + (pin.input() ? "output" : "input") + " terminal");
    }

    @Unique
    private void logic$advanceOrFinish(LogicPinHit pin) {
        LogicPinHit start = logic$route.start();
        if (start.input() == pin.input()) {
            logic$commitHistory();
            logic$checkpoint("Create routed wire");
            logic$route = new LogicRoute(pin, new ArrayList<>());
            status.accept("WIRE source changed to " + logic$pinLabel(pin));
            return;
        }
        LogicPinHit output = start.input() ? pin : start;
        LogicPinHit input = start.input() ? start : pin;
        if (output.spec().width() != input.spec().width()) {
            status.accept(logic$pinLabel(output) + " [" + output.spec().width() + "] -> " + logic$pinLabel(input)
                    + " [" + input.spec().width() + "] — WIDTH MISMATCH");
            return;
        }

        document.connect(output.node().id, output.port(), input.node().id, input.port());
        WireConnection wire = document.wires.getLast();
        List<LogicPoint> corners = new ArrayList<>(logic$route.corners());
        if (start.input()) Collections.reverse(corners);
        wire.routePoints().clear();
        for (LogicPoint corner : corners) wire.routePoints().add(new RoutePoint(corner.x(), corner.y()));
        selectedWire = wire;
        wireEditMode = false;
        logic$route = null;
        recompile();
        logic$commitHistory();
        status.accept("Connected " + output.spec().width() + "-bit " + (output.spec().width() == 1 ? "wire" : "bus")
                + (corners.isEmpty() ? "" : " with " + corners.size() + " permanent route corner" + (corners.size() == 1 ? "" : "s")));
    }

    @Unique
    private void logic$beginWireAnchor(WireConnection wire, double mouseX, double mouseY) {
        ensureEditableRoute(wire);
        List<LogicPoint> points = logic$wirePoints(wire);
        int segment = logic$segmentAt(points, mouseX, mouseY);
        if (segment < 0) return;
        LogicPoint a = points.get(segment);
        LogicPoint b = points.get(segment + 1);
        LogicPoint anchor;
        if (Math.abs(a.y() - b.y()) < 0.001) {
            double x = EditorGrid.snap(worldX(mouseX));
            x = clamp(x, Math.min(a.x(), b.x()), Math.max(a.x(), b.x()));
            anchor = new LogicPoint(x, a.y());
        } else {
            double y = EditorGrid.snap(worldY(mouseY));
            y = clamp(y, Math.min(a.y(), b.y()), Math.max(a.y(), b.y()));
            anchor = new LogicPoint(a.x(), y);
        }
        selectedWire = wire;
        wireEditMode = false;
        logic$wireAnchor = new LogicWireAnchor(wire, segment, anchor);
        status.accept("WIRE ANCHOR set — click an empty grid position to insert a routed detour");
    }

    @Unique
    private void logic$insertDetour(double mouseX, double mouseY) {
        LogicWireAnchor state = logic$wireAnchor;
        if (state == null || !document.wires.contains(state.wire())) {
            logic$wireAnchor = null;
            return;
        }
        logic$checkpoint("Reroute wire");
        double x = EditorGrid.snap(worldX(mouseX));
        double y = EditorGrid.snap(worldY(mouseY));
        int insertion = Math.max(0, Math.min(state.wire().routePoints().size(), state.segmentIndex()));
        state.wire().routePoints().add(insertion, new RoutePoint(state.anchor().x(), state.anchor().y()));
        state.wire().routePoints().add(insertion + 1, new RoutePoint(x, y));
        logic$wireAnchor = null;
        logic$commitHistory();
        status.accept("Wire detour inserted — click the trace again anywhere to add another anchor");
    }

    @Inject(method = "cancelTransientMode", at = @At("HEAD"), cancellable = true)
    private void logic$cancelRoute(CallbackInfoReturnable<Boolean> cir) {
        if (logic$route == null && logic$wireAnchor == null) return;
        logic$route = null;
        logic$wireAnchor = null;
        logic$commitHistory();
        status.accept("Cancelled wire routing");
        cir.setReturnValue(true);
    }

    @Inject(method = "setDocument(Lcom/foreverspark/logicsim/editor/model/CircuitDocument;Ljava/lang/String;)V", at = @At("RETURN"))
    private void logic$clearRouteOnDocument(CircuitDocument replacement, String name, CallbackInfo ci) {
        logic$route = null;
        logic$wireAnchor = null;
    }

    @Inject(method = "openNestedChip", at = @At("RETURN"))
    private void logic$clearRouteOnNested(EditorNode node, CallbackInfo ci) {
        logic$route = null;
        logic$wireAnchor = null;
    }

    @Inject(method = "navigateBack", at = @At("RETURN"))
    private void logic$clearRouteOnBack(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            logic$route = null;
            logic$wireAnchor = null;
        }
    }

    @Inject(method = "extractWidgetRenderState", at = @At(value = "INVOKE", target = "Lcom/foreverspark/logicsim/client/screen/CircuitCanvasWidget;drawMarquee(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V", shift = At.Shift.BEFORE))
    private void logic$drawRoutePreview(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (logic$route != null) {
            LogicPoint start = logic$point(logic$route.start());
            LogicPoint current = start;
            for (LogicPoint corner : logic$route.corners()) {
                logic$drawOrthogonal(graphics, current, corner, 0xFF6CA9FF);
                logic$drawCorner(graphics, corner, 0xFF8FC5FF);
                current = corner;
            }
            LogicPinHit target = logic$pinAt(mouseX, mouseY);
            LogicPoint end = target != null ? logic$point(target) : new LogicPoint(EditorGrid.snap(worldX(mouseX)), EditorGrid.snap(worldY(mouseY)));
            int color = 0xFF6CA9FF;
            if (target != null && target.input() != logic$route.start().input()) {
                color = target.spec().width() == logic$route.start().spec().width() ? 0xFF55D96B : 0xFFE05252;
            }
            logic$drawOrthogonal(graphics, current, end, color);
        }
        if (logic$wireAnchor != null) {
            LogicPoint anchor = logic$wireAnchor.anchor();
            int x = screenX(anchor.x()), y = screenY(anchor.y());
            graphics.outline(x - 5, y - 5, 11, 11, 0xFFFFC857);
        }
    }

    @Unique
    private LogicPinHit logic$pinAt(double mouseX, double mouseY) {
        for (int n = document.nodes.size() - 1; n >= 0; n--) {
            EditorNode node = document.nodes.get(n);
            List<PortSpec> outputs = safeOutputs(node);
            for (int port = 0; port < outputs.size(); port++) {
                LogicPoint point = logic$outputPoint(node, port);
                if (EditorPinGeometry.contains(mouseX - screenX(point.x()), mouseY - screenY(point.y()), outputs.get(port).width())) {
                    return new LogicPinHit(node, port, outputs.get(port), false);
                }
            }
        }
        for (int n = document.nodes.size() - 1; n >= 0; n--) {
            EditorNode node = document.nodes.get(n);
            List<PortSpec> inputs = safeInputs(node);
            for (int port = 0; port < inputs.size(); port++) {
                LogicPoint point = logic$inputPoint(node, port);
                if (EditorPinGeometry.contains(mouseX - screenX(point.x()), mouseY - screenY(point.y()), inputs.get(port).width())) {
                    return new LogicPinHit(node, port, inputs.get(port), true);
                }
            }
        }
        return null;
    }

    @Unique private LogicPoint logic$point(LogicPinHit hit) { return hit.input() ? logic$inputPoint(hit.node(), hit.port()) : logic$outputPoint(hit.node(), hit.port()); }

    @Unique
    private LogicPoint logic$inputPoint(EditorNode node, int port) {
        double y;
        if (node.kind == NodeKind.CUSTOM_CHIP) y = centeredPortY(node, port, safeInputs(node).size());
        else if (node.kind == NodeKind.CONSTANT && node.randomSource) y = node.y + nodeHeight(node) * 0.5;
        else y = switch (node.kind) {
            case OUTPUT, PROBE, BUS, SPLITTER, BUS_SLICE, NET_LABEL -> node.y + nodeHeight(node) * 0.5;
            default -> node.y + 30.0 + port * portStep(node);
        };
        return new LogicPoint(EditorGrid.snap(node.x), EditorGrid.snap(y));
    }

    @Unique
    private LogicPoint logic$outputPoint(EditorNode node, int port) {
        double y;
        if (node.kind == NodeKind.CUSTOM_CHIP) y = centeredPortY(node, port, safeOutputs(node).size());
        else y = switch (node.kind) {
            case INPUT, NAND, CONSTANT, BUS, MERGER, NET_LABEL -> node.y + nodeHeight(node) * 0.5;
            default -> node.y + 30.0 + port * portStep(node);
        };
        return new LogicPoint(EditorGrid.snap(node.x + nodeWidth(node)), EditorGrid.snap(y));
    }

    @Unique
    private List<LogicPoint> logic$wirePoints(WireConnection wire) {
        List<LogicPoint> result = new ArrayList<>();
        try {
            EditorNode source = document.node(wire.sourceNodeId());
            EditorNode target = document.node(wire.targetNodeId());
            result.add(logic$outputPoint(source, wire.sourcePort()));
            for (RoutePoint point : wire.routePoints()) result.add(new LogicPoint(point.x(), point.y()));
            result.add(logic$inputPoint(target, wire.targetPort()));
        } catch (RuntimeException ignored) {
            return List.of();
        }
        return result;
    }

    @Unique
    private int logic$segmentAt(List<LogicPoint> points, double mouseX, double mouseY) {
        for (int i = 0; i + 1 < points.size(); i++) {
            LogicPoint a = points.get(i), b = points.get(i + 1);
            double d = distanceToSegment(mouseX, mouseY, screenX(a.x()), screenY(a.y()), screenX(b.x()), screenY(b.y()));
            if (d <= 7.0) return i;
        }
        return -1;
    }

    @Unique
    private void logic$drawOrthogonal(GuiGraphicsExtractor graphics, LogicPoint a, LogicPoint b, int color) {
        int x1 = screenX(a.x()), y1 = screenY(a.y()), x2 = screenX(b.x()), y2 = screenY(b.y());
        if (x1 == x2 || y1 == y2) {
            logic$line(graphics, x1, y1, x2, y2, color);
            return;
        }
        logic$line(graphics, x1, y1, x2, y1, color);
        logic$line(graphics, x2, y1, x2, y2, color);
    }

    @Unique private void logic$drawCorner(GuiGraphicsExtractor graphics, LogicPoint point, int color) {
        int x = screenX(point.x()), y = screenY(point.y());
        graphics.fill(x - 2, y - 2, x + 3, y + 3, color);
    }

    @Unique private void logic$line(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2, int color) {
        if (y1 == y2) graphics.fill(Math.min(x1, x2), y1 - 1, Math.max(x1, x2) + 1, y1 + 2, color);
        else graphics.fill(x1 - 1, Math.min(y1, y2), x1 + 2, Math.max(y1, y2) + 1, color);
    }

    @Unique private String logic$pinLabel(LogicPinHit hit) {
        String port = hit.spec().name() == null || hit.spec().name().isBlank() ? (hit.input() ? "IN" : "OUT") : hit.spec().name();
        return hit.node().displayName() + "." + port;
    }

    @Unique private void logic$checkpoint(String label) {
        Object self = this;
        if (self instanceof EditorHistoryAccess history) history.logic$checkpoint(label);
    }

    @Unique private void logic$commitHistory() {
        Object self = this;
        if (self instanceof EditorHistoryAccess history) history.logic$commitHistory();
    }

    @Unique private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }

    @Unique private static double distanceToSegment(double px, double py, double x1, double y1, double x2, double y2) {
        double dx = x2 - x1, dy = y2 - y1;
        if (dx == 0.0 && dy == 0.0) return Math.hypot(px - x1, py - y1);
        double t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy);
        t = clamp(t, 0.0, 1.0);
        return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
    }

    @Unique private record LogicPoint(double x, double y) {}
    @Unique private record LogicPinHit(EditorNode node, int port, PortSpec spec, boolean input) {}
    @Unique private record LogicRoute(LogicPinHit start, ArrayList<LogicPoint> corners) {}
    @Unique private record LogicWireAnchor(WireConnection wire, int segmentIndex, LogicPoint anchor) {}
}
