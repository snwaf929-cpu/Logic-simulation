package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.v2.EditorGrid;
import com.foreverspark.logicsim.client.screen.v2.EditorHistoryAccess;
import com.foreverspark.logicsim.client.screen.v2.EditorPinGeometry;
import com.foreverspark.logicsim.client.screen.v2.EditorWireRouting;
import com.foreverspark.logicsim.client.screen.v2.PcbLayerAccess;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.PortSpec;
import com.foreverspark.logicsim.editor.model.RoutePoint;
import com.foreverspark.logicsim.editor.model.WireConnection;
import com.foreverspark.logicsim.editor.model.WireLayer;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * V2.2 direct wire interaction layer.
 *
 * <p>There is no double-click editing language. A trace is edited by dragging its existing bend/segment directly.
 * Ctrl+LMB on a trace creates a shared-net tap at the exact snapped point, then uses the same click-waypoint / click-
 * destination flow as ordinary wire creation. Manual "add handles/corners" commands are disabled because segment drag
 * already materializes the internal dogleg handles it needs automatically.</p>
 */
@Mixin(value = CircuitCanvasWidget.class, priority = 3050)
public abstract class CircuitCanvasWireDirectV22Mixin {
    @Shadow private CircuitDocument document;
    @Shadow private WireConnection selectedWire;
    @Shadow private boolean wireEditMode;
    @Shadow private NodeKind placementKind;
    @Shadow @Final private LinkedHashSet<Integer> selectedNodeIds;
    @Shadow private Integer selectedNodeId;
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

    @Unique private LogicBranch logic$branch;

    @Inject(method = "onClick", at = @At("HEAD"), cancellable = true)
    private void logic$directWireClick(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        if (logic$branch != null) {
            if (event.button() == 1) {
                logic$cancelBranch();
                ci.cancel();
                return;
            }
            if (event.button() != 0) return;

            LogicInputHit target = logic$inputAt(event.x(), event.y());
            if (target != null) {
                logic$finishBranch(target);
                ci.cancel();
                return;
            }

            if (nodeAt(event.x(), event.y()) == null) {
                EditorWireRouting.Point point = new EditorWireRouting.Point(
                        EditorGrid.snap(worldX(event.x())), EditorGrid.snap(worldY(event.y())));
                if (logic$branch.corners().isEmpty() || !logic$same(logic$branch.corners().getLast(), point)) {
                    logic$branch.corners().add(point);
                    status.accept("WIRE BRANCH: waypoint fixed — continue or click a matching input; RMB/Esc cancels");
                }
            } else {
                status.accept("WIRE BRANCH: click empty grid for an optional waypoint or a matching input to finish");
            }
            ci.cancel();
            return;
        }

        if (event.button() != 0 || placementKind != null) return;
        boolean alt = (event.modifiers() & GLFW.GLFW_MOD_ALT) != 0;
        boolean control = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        if (alt || logic$pinAt(event.x(), event.y()) != null) return;

        WireConnection hit = nodeAt(event.x(), event.y()) == null ? wireAt(event.x(), event.y()) : null;
        if (hit == null || !document.wires.contains(hit)) return;

        if (control) {
            if (logic$startBranch(hit, event.x(), event.y())) ci.cancel();
            return;
        }

        // Double-click deliberately means nothing beyond selecting the trace. The first click already selected it;
        // consuming the second click prevents the old V2.1B "insert two route handles" behavior entirely.
        if (doubleClick) {
            selectedWire = hit;
            selectedNodeIds.clear();
            selectedNodeId = null;
            wireEditMode = false;
            status.accept("WIRE selected — drag a bend or straight segment directly; Ctrl+click a trace to branch");
            ci.cancel();
        }
    }

    @Inject(method = "onRelease", at = @At("TAIL"))
    private void logic$directWireRelease(MouseButtonEvent event, CallbackInfo ci) {
        if (event.button() != 0 || logic$branch != null || selectedWire == null || !document.wires.contains(selectedWire)) return;
        if ((event.modifiers() & GLFW.GLFW_MOD_ALT) != 0) return;
        WireConnection hit = nodeAt(event.x(), event.y()) == null ? wireAt(event.x(), event.y()) : null;
        if (hit == selectedWire) {
            status.accept("WIRE selected — drag a bend or straight segment directly; Ctrl+click the trace to branch");
        }
    }

    /** E is no longer a mode switch: selected traces are always directly editable. */
    @Inject(method = "toggleWireEditMode", at = @At("HEAD"), cancellable = true)
    private void logic$removeWireEditMode(CallbackInfoReturnable<Boolean> cir) {
        wireEditMode = false;
        if (selectedWire == null || !document.wires.contains(selectedWire)) {
            status.accept("WIRE: click a trace, then drag its bend or segment directly");
            cir.setReturnValue(false);
        } else {
            status.accept("WIRE: no edit mode needed — drag bends/segments directly; Ctrl+click branches");
            cir.setReturnValue(true);
        }
    }

    /** '+' and legacy route-handle insertion are intentionally gone. */
    @Inject(method = "addRoutePointToSelection", at = @At("HEAD"), cancellable = true)
    private void logic$removeManualHandleInsertion(CallbackInfoReturnable<Boolean> cir) {
        status.accept("WIRE: manual corner insertion removed — drag the segment where you want the route to move");
        cir.setReturnValue(false);
    }

    @Inject(method = "cancelTransientMode", at = @At("HEAD"), cancellable = true)
    private void logic$cancelBranchMode(CallbackInfoReturnable<Boolean> cir) {
        if (logic$branch == null) return;
        logic$cancelBranch();
        cir.setReturnValue(true);
    }

    @Inject(method = "setDocument(Lcom/foreverspark/logicsim/editor/model/CircuitDocument;Ljava/lang/String;)V", at = @At("RETURN"))
    private void logic$clearBranchOnDocument(CircuitDocument replacement, String name, CallbackInfo ci) {
        logic$branch = null;
    }

    @Inject(method = "openNestedChip", at = @At("RETURN"))
    private void logic$clearBranchOnNested(EditorNode node, CallbackInfo ci) {
        logic$branch = null;
    }

    @Inject(method = "navigateBack", at = @At("RETURN"))
    private void logic$clearBranchOnBack(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) logic$branch = null;
    }

    @Inject(method = "extractWidgetRenderState", at = @At("TAIL"))
    private void logic$drawBranchPreview(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        LogicBranch branch = logic$branch;
        if (branch == null) return;

        EditorWireRouting.Point current = new EditorWireRouting.Point(branch.tap().x(), branch.tap().y());
        logic$drawTap(graphics, current, 0xFF9AD2FF);
        for (EditorWireRouting.Point corner : branch.corners()) {
            logic$drawOrthogonal(graphics, current, corner, 0xFF6CA9FF);
            logic$drawTap(graphics, corner, 0xFF8FC5FF);
            current = corner;
        }

        LogicInputHit target = logic$inputAt(mouseX, mouseY);
        EditorWireRouting.Point end = target == null
                ? new EditorWireRouting.Point(EditorGrid.snap(worldX(mouseX)), EditorGrid.snap(worldY(mouseY)))
                : logic$inputPoint(target.node(), target.port());
        int color = target == null ? 0xFF6CA9FF
                : target.spec().width() == branch.width() ? 0xFF55D96B : 0xFFE05252;
        logic$drawOrthogonal(graphics, current, end, color);
    }

    @Unique
    private boolean logic$startBranch(WireConnection parent, double mouseX, double mouseY) {
        LogicTap tap = logic$closestTap(parent, mouseX, mouseY);
        if (tap == null) return false;
        try {
            EditorNode source = document.node(parent.sourceNodeId());
            List<PortSpec> outputs = safeOutputs(source);
            if (parent.sourcePort() < 0 || parent.sourcePort() >= outputs.size()) return false;
            int width = outputs.get(parent.sourcePort()).width();
            logic$branch = new LogicBranch(parent, tap, width, new ArrayList<>());
            selectedWire = parent;
            selectedNodeIds.clear();
            selectedNodeId = null;
            wireEditMode = false;
            status.accept("WIRE BRANCH " + width + "-bit: started at exact trace point — click destination or optional waypoint; RMB/Esc cancels");
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Unique
    private void logic$finishBranch(LogicInputHit target) {
        LogicBranch branch = logic$branch;
        if (branch == null) return;
        if (target.spec().width() != branch.width()) {
            status.accept("WIDTH MISMATCH: branch is " + branch.width() + "-bit, target is " + target.spec().width() + "-bit");
            return;
        }
        if (branch.parent().targetNodeId() == target.node().id && branch.parent().targetPort() == target.port()) {
            status.accept("That input is already the parent trace destination");
            return;
        }

        EditorWireRouting.Point anchor = new EditorWireRouting.Point(branch.tap().x(), branch.tap().y());
        EditorWireRouting.Point targetPoint = logic$inputPoint(target.node(), target.port());
        List<RoutePoint> prefix = logic$prefixToTap(branch.parent(), branch.tap());
        List<RoutePoint> continuation = EditorWireRouting.explicitRoute(anchor, branch.corners(), targetPoint);
        ArrayList<RoutePoint> route = new ArrayList<>(prefix.size() + continuation.size());
        for (RoutePoint point : prefix) logic$appendRoutePoint(route, point);
        for (RoutePoint point : continuation) logic$appendRoutePoint(route, point);

        logic$checkpoint("Branch wire");
        try {
            document.connect(branch.parent().sourceNodeId(), branch.parent().sourcePort(), target.node().id, target.port());
            WireConnection created = document.wires.getLast();
            created.setLayer(branch.parent().layer());
            created.setRoutePoints(route);
            created.setBranchStart(new RoutePoint(branch.tap().x(), branch.tap().y()));
            logic$copyPrefixVias(branch.parent(), created, branch.tap().segmentIndex());
            selectedWire = created;
            logic$branch = null;
            recompile();
            logic$commitHistory();
            status.accept("WIRE branch connected — edit it by dragging the trace directly");
        } catch (RuntimeException exception) {
            logic$commitHistory();
            status.accept("ERROR: Cannot branch wire: " + logic$message(exception));
        }
    }

    @Unique
    private LogicTap logic$closestTap(WireConnection wire, double mouseX, double mouseY) {
        EditorWireRouting.Point start = logic$wireStart(wire);
        EditorWireRouting.Point end = logic$wireEnd(wire);
        WireLayer layer = logic$currentLayer();
        double wx = worldX(mouseX);
        double wy = worldY(mouseY);
        double best = Double.POSITIVE_INFINITY;
        LogicTap result = null;

        for (EditorWireRouting.Segment segment : EditorWireRouting.segments(wire, start, end, true)) {
            if (wire.segmentLayer(segment.index()) != layer) continue;
            EditorWireRouting.Point a = segment.a();
            EditorWireRouting.Point b = segment.b();
            double px;
            double py;
            if (Math.abs(a.y() - b.y()) < 0.001) {
                px = logic$clamp(EditorGrid.snap(wx), Math.min(a.x(), b.x()), Math.max(a.x(), b.x()));
                py = a.y();
            } else if (Math.abs(a.x() - b.x()) < 0.001) {
                px = a.x();
                py = logic$clamp(EditorGrid.snap(wy), Math.min(a.y(), b.y()), Math.max(a.y(), b.y()));
            } else continue;

            double distance = Math.hypot(mouseX - screenX(px), mouseY - screenY(py));
            if (distance < best) {
                best = distance;
                result = new LogicTap(EditorGrid.snap(px), EditorGrid.snap(py), segment.index());
            }
        }
        return result;
    }

    @Unique
    private List<RoutePoint> logic$prefixToTap(WireConnection parent, LogicTap tap) {
        List<EditorWireRouting.Point> full = EditorWireRouting.fullPoints(parent, logic$wireStart(parent), logic$wireEnd(parent), true);
        ArrayList<RoutePoint> result = new ArrayList<>();
        int lastInterior = Math.min(tap.segmentIndex(), full.size() - 2);
        for (int index = 1; index <= lastInterior; index++) {
            EditorWireRouting.Point point = full.get(index);
            logic$appendRoutePoint(result, new RoutePoint(point.x(), point.y()));
        }
        logic$appendRoutePoint(result, new RoutePoint(tap.x(), tap.y()));
        return List.copyOf(result);
    }

    @Unique
    private void logic$copyPrefixVias(WireConnection parent, WireConnection created, int tapSegmentIndex) {
        if (parent.viaRouteIndices().isEmpty() || created.routePoints().isEmpty()) return;
        ArrayList<Integer> copied = new ArrayList<>();
        for (Integer parentVia : parent.viaRouteIndices()) {
            if (parentVia == null || parentVia < 0 || parentVia >= parent.routePoints().size()) continue;
            if (parentVia >= tapSegmentIndex) continue;
            RoutePoint sourceVia = parent.routePoints().get(parentVia);
            for (int index = 0; index < created.routePoints().size(); index++) {
                RoutePoint candidate = created.routePoints().get(index);
                if (logic$same(candidate.x(), candidate.y(), sourceVia.x(), sourceVia.y())) {
                    copied.add(index);
                    break;
                }
            }
        }
        created.setViaRouteIndices(copied);
    }

    @Unique
    private LogicInputHit logic$inputAt(double mouseX, double mouseY) {
        for (int n = document.nodes.size() - 1; n >= 0; n--) {
            EditorNode node = document.nodes.get(n);
            List<PortSpec> inputs = safeInputs(node);
            for (int port = 0; port < inputs.size(); port++) {
                EditorWireRouting.Point point = logic$inputPoint(node, port);
                PortSpec spec = inputs.get(port);
                if (EditorPinGeometry.contains(mouseX - screenX(point.x()), mouseY - screenY(point.y()), spec.width())) {
                    return new LogicInputHit(node, port, spec);
                }
            }
        }
        return null;
    }

    @Unique
    private LogicPinHit logic$pinAt(double mouseX, double mouseY) {
        for (int n = document.nodes.size() - 1; n >= 0; n--) {
            EditorNode node = document.nodes.get(n);
            List<PortSpec> outputs = safeOutputs(node);
            for (int port = 0; port < outputs.size(); port++) {
                EditorWireRouting.Point point = logic$outputPoint(node, port);
                if (EditorPinGeometry.contains(mouseX - screenX(point.x()), mouseY - screenY(point.y()), outputs.get(port).width())) {
                    return new LogicPinHit();
                }
            }
            List<PortSpec> inputs = safeInputs(node);
            for (int port = 0; port < inputs.size(); port++) {
                EditorWireRouting.Point point = logic$inputPoint(node, port);
                if (EditorPinGeometry.contains(mouseX - screenX(point.x()), mouseY - screenY(point.y()), inputs.get(port).width())) {
                    return new LogicPinHit();
                }
            }
        }
        return null;
    }

    @Unique
    private EditorWireRouting.Point logic$wireStart(WireConnection wire) {
        EditorNode source = document.node(wire.sourceNodeId());
        return logic$outputPoint(source, wire.sourcePort());
    }

    @Unique
    private EditorWireRouting.Point logic$wireEnd(WireConnection wire) {
        EditorNode target = document.node(wire.targetNodeId());
        return logic$inputPoint(target, wire.targetPort());
    }

    @Unique
    private EditorWireRouting.Point logic$inputPoint(EditorNode node, int port) {
        double y;
        if (node.kind == NodeKind.CUSTOM_CHIP) y = centeredPortY(node, port, safeInputs(node).size());
        else if (node.kind == NodeKind.CONSTANT && node.randomSource) y = node.y + nodeHeight(node) * 0.5;
        else y = switch (node.kind) {
            case OUTPUT, PROBE, BUS, SPLITTER, BUS_SLICE, NET_LABEL -> node.y + nodeHeight(node) * 0.5;
            default -> node.y + 30.0 + port * portStep(node);
        };
        return new EditorWireRouting.Point(EditorGrid.snap(node.x), EditorGrid.snap(y));
    }

    @Unique
    private EditorWireRouting.Point logic$outputPoint(EditorNode node, int port) {
        double y;
        if (node.kind == NodeKind.CUSTOM_CHIP) y = centeredPortY(node, port, safeOutputs(node).size());
        else y = switch (node.kind) {
            case INPUT, NAND, CONSTANT, BUS, MERGER, NET_LABEL -> node.y + nodeHeight(node) * 0.5;
            default -> node.y + 30.0 + port * portStep(node);
        };
        return new EditorWireRouting.Point(EditorGrid.snap(node.x + nodeWidth(node)), EditorGrid.snap(y));
    }

    @Unique
    private WireLayer logic$currentLayer() {
        Object self = this;
        return self instanceof PcbLayerAccess pcb ? pcb.logic$currentPcbLayer() : WireLayer.FRONT;
    }

    @Unique
    private void logic$drawTap(GuiGraphicsExtractor graphics, EditorWireRouting.Point point, int color) {
        int x = screenX(point.x());
        int y = screenY(point.y());
        graphics.fill(x - 3, y - 3, x + 4, y + 4, 0xFF11171D);
        graphics.outline(x - 4, y - 4, 9, 9, color);
    }

    @Unique
    private void logic$drawOrthogonal(GuiGraphicsExtractor graphics, EditorWireRouting.Point a, EditorWireRouting.Point b, int color) {
        int x1 = screenX(a.x()), y1 = screenY(a.y());
        int x2 = screenX(b.x()), y2 = screenY(b.y());
        if (Math.abs(a.y() - b.y()) < 0.001) {
            graphics.fill(Math.min(x1, x2), y1 - 1, Math.max(x1, x2) + 1, y1 + 1, color);
        } else if (Math.abs(a.x() - b.x()) < 0.001) {
            graphics.fill(x1 - 1, Math.min(y1, y2), x1 + 1, Math.max(y1, y2) + 1, color);
        } else {
            graphics.fill(Math.min(x1, x2), y1 - 1, Math.max(x1, x2) + 1, y1 + 1, color);
            graphics.fill(x2 - 1, Math.min(y1, y2), x2 + 1, Math.max(y1, y2) + 1, color);
        }
    }

    @Unique
    private void logic$checkpoint(String label) {
        Object self = this;
        if (self instanceof EditorHistoryAccess history) history.logic$checkpoint(label);
    }

    @Unique
    private void logic$commitHistory() {
        Object self = this;
        if (self instanceof EditorHistoryAccess history) history.logic$commitHistory();
    }

    @Unique
    private void logic$cancelBranch() {
        logic$branch = null;
        status.accept("WIRE branch cancelled");
    }

    @Unique
    private static void logic$appendRoutePoint(List<RoutePoint> route, RoutePoint point) {
        if (point == null) return;
        if (!route.isEmpty()) {
            RoutePoint last = route.getLast();
            if (logic$same(last.x(), last.y(), point.x(), point.y())) return;
        }
        route.add(new RoutePoint(EditorGrid.snap(point.x()), EditorGrid.snap(point.y())));
    }

    @Unique private static boolean logic$same(EditorWireRouting.Point a, EditorWireRouting.Point b) {
        return logic$same(a.x(), a.y(), b.x(), b.y());
    }

    @Unique private static boolean logic$same(double ax, double ay, double bx, double by) {
        return Math.abs(ax - bx) < 0.001 && Math.abs(ay - by) < 0.001;
    }

    @Unique private static double logic$clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @Unique private static String logic$message(RuntimeException exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    @Unique private record LogicBranch(WireConnection parent, LogicTap tap, int width, ArrayList<EditorWireRouting.Point> corners) {}
    @Unique private record LogicTap(double x, double y, int segmentIndex) {}
    @Unique private record LogicInputHit(EditorNode node, int port, PortSpec spec) {}
    @Unique private record LogicPinHit() {}
}
