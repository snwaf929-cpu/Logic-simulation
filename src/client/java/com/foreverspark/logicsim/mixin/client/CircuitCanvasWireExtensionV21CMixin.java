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

/**
 * V2.1C wire-extension controller.
 *
 * <p>Double-clicking a visible trace starts a branch from the exact grid-snapped trace anchor. The extension then uses
 * the same multi-corner orthogonal workflow as a newly-created V2.1B wire: click empty grid cells for any number of
 * corners, click a compatible input to finish, and use RMB/Esc to cancel. This mixin runs before both V2.1B's old
 * double-click-handle gesture and the legacy branch mixin, removing their gesture collision without changing normal
 * trace selection or drag behavior.</p>
 */
@Mixin(value = CircuitCanvasWidget.class, priority = 2850)
public abstract class CircuitCanvasWireExtensionV21CMixin {
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

    @Unique private LogicExtension logic$extension;

    @Inject(method = "onClick", at = @At("HEAD"), cancellable = true)
    private void logic$extensionClick(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        if (logic$extension != null) {
            if (event.button() == 1) {
                logic$cancelExtension("WIRE extension cancelled");
                ci.cancel();
                return;
            }
            if (event.button() != 0) return;

            LogicInputHit target = logic$inputAt(event.x(), event.y());
            if (target != null) {
                logic$finishExtension(target);
                ci.cancel();
                return;
            }

            if (nodeAt(event.x(), event.y()) == null) {
                EditorWireRouting.Point point = new EditorWireRouting.Point(
                        EditorGrid.snap(worldX(event.x())), EditorGrid.snap(worldY(event.y())));
                ArrayList<EditorWireRouting.Point> corners = logic$extension.corners();
                if (corners.isEmpty() || !logic$same(corners.getLast(), point)) {
                    corners.add(point);
                    status.accept("WIRE extension: corner " + corners.size()
                            + " placed — continue or click a matching input; RMB/Esc cancels");
                }
            } else {
                status.accept("WIRE extension: click empty grid for a corner, a matching input to finish, or RMB/Esc to cancel");
            }
            ci.cancel();
            return;
        }

        if (event.button() != 0 || !doubleClick || wireEditMode) return;
        if (nodeAt(event.x(), event.y()) != null) return;

        WireConnection parent = wireAt(event.x(), event.y());
        if (parent == null || !document.wires.contains(parent)) return;
        LogicTap tap = logic$closestVisibleTap(parent, event.x(), event.y());
        if (tap == null) return;

        int width;
        try {
            EditorNode source = document.node(parent.sourceNodeId());
            List<PortSpec> outputs = safeOutputs(source);
            if (parent.sourcePort() < 0 || parent.sourcePort() >= outputs.size()) return;
            width = outputs.get(parent.sourcePort()).width();
        } catch (RuntimeException ignored) {
            return;
        }

        logic$extension = new LogicExtension(parent, tap, width, new ArrayList<>());
        selectedWire = parent;
        status.accept("WIRE extension " + width + "-bit: click empty grid for corners, then a matching input; RMB/Esc cancels");
        ci.cancel();
    }

    @Inject(method = "cancelTransientMode", at = @At("HEAD"), cancellable = true)
    private void logic$cancelExtensionMode(CallbackInfoReturnable<Boolean> cir) {
        if (logic$extension == null) return;
        logic$cancelExtension("WIRE extension cancelled");
        cir.setReturnValue(true);
    }

    @Inject(method = "setDocument(Lcom/foreverspark/logicsim/editor/model/CircuitDocument;Ljava/lang/String;)V", at = @At("RETURN"))
    private void logic$clearExtensionOnDocument(CircuitDocument replacement, String name, CallbackInfo ci) {
        logic$extension = null;
    }

    @Inject(method = "openNestedChip", at = @At("RETURN"))
    private void logic$clearExtensionOnNested(EditorNode node, CallbackInfo ci) {
        logic$extension = null;
    }

    @Inject(method = "navigateBack", at = @At("RETURN"))
    private void logic$clearExtensionOnBack(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) logic$extension = null;
    }

    @Inject(method = "extractWidgetRenderState", at = @At("TAIL"))
    private void logic$drawExtension(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        LogicExtension extension = logic$extension;
        if (extension == null) return;

        EditorWireRouting.Point current = new EditorWireRouting.Point(extension.tap().x(), extension.tap().y());
        int normalColor = 0xFF6CA9FF;
        logic$drawHandle(graphics, current, 0xFF8FC5FF);
        for (EditorWireRouting.Point corner : extension.corners()) {
            logic$drawOrthogonal(graphics, current, corner, normalColor);
            logic$drawHandle(graphics, corner, 0xFF8FC5FF);
            current = corner;
        }

        LogicInputHit target = logic$inputAt(mouseX, mouseY);
        EditorWireRouting.Point end;
        int color = normalColor;
        if (target != null) {
            end = logic$inputPoint(target.node(), target.port());
            color = target.spec().width() == extension.width() ? 0xFF55D96B : 0xFFE05252;
        } else {
            end = new EditorWireRouting.Point(EditorGrid.snap(worldX(mouseX)), EditorGrid.snap(worldY(mouseY)));
        }
        logic$drawOrthogonal(graphics, current, end, color);
    }

    @Unique
    private void logic$finishExtension(LogicInputHit target) {
        LogicExtension extension = logic$extension;
        if (extension == null) return;
        if (target.spec().width() != extension.width()) {
            status.accept("WIDTH MISMATCH: extension is " + extension.width() + "-bit, target is "
                    + target.spec().width() + "-bit");
            return;
        }
        if (extension.parent().targetNodeId() == target.node().id
                && extension.parent().targetPort() == target.port()) {
            status.accept("That input is already the parent wire's destination");
            return;
        }

        LogicTap tap = extension.tap();
        EditorWireRouting.Point anchor = new EditorWireRouting.Point(tap.x(), tap.y());
        EditorWireRouting.Point targetPoint = logic$inputPoint(target.node(), target.port());
        List<RoutePoint> prefix = logic$prefixToTap(extension.parent(), tap);
        List<RoutePoint> continuation = EditorWireRouting.explicitRoute(anchor, extension.corners(), targetPoint);
        ArrayList<RoutePoint> route = new ArrayList<>(prefix.size() + continuation.size());
        for (RoutePoint point : prefix) logic$appendRoutePoint(route, point);
        for (RoutePoint point : continuation) logic$appendRoutePoint(route, point);

        logic$checkpoint("Extend wire");
        try {
            document.connect(extension.parent().sourceNodeId(), extension.parent().sourcePort(), target.node().id, target.port());
            WireConnection created = document.wires.getLast();
            created.setLayer(extension.parent().layer());
            created.setRoutePoints(route);
            created.setBranchStart(new RoutePoint(tap.x(), tap.y()));
            logic$copyPrefixVias(extension.parent(), created, tap.segmentIndex());
            selectedWire = created;
            logic$extension = null;
            recompile();
            logic$commitHistory();
            status.accept("Created " + extension.width() + "-bit shared-net extension with "
                    + extension.corners().size() + " user corner" + (extension.corners().size() == 1 ? "" : "s")
                    + " — same V2.1 routing workflow as a new wire");
        } catch (RuntimeException exception) {
            logic$commitHistory();
            status.accept("ERROR: Cannot extend wire: " + logic$message(exception));
        }
    }

    @Unique
    private LogicTap logic$closestVisibleTap(WireConnection wire, double mouseX, double mouseY) {
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
            } else {
                continue;
            }
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
        EditorWireRouting.Point start = logic$wireStart(parent);
        EditorWireRouting.Point end = logic$wireEnd(parent);
        List<EditorWireRouting.Point> full = EditorWireRouting.fullPoints(parent, start, end, true);
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
    private void logic$drawOrthogonal(GuiGraphicsExtractor graphics, EditorWireRouting.Point a,
                                      EditorWireRouting.Point b, int color) {
        int x1 = screenX(a.x());
        int y1 = screenY(a.y());
        int x2 = screenX(b.x());
        int y2 = screenY(b.y());
        if (Math.abs(a.y() - b.y()) < 0.001) {
            graphics.fill(Math.min(x1, x2), y1 - 1, Math.max(x1, x2) + 1, y1 + 2, color);
        } else if (Math.abs(a.x() - b.x()) < 0.001) {
            graphics.fill(x1 - 1, Math.min(y1, y2), x1 + 2, Math.max(y1, y2) + 1, color);
        } else {
            graphics.fill(Math.min(x1, x2), y1 - 1, Math.max(x1, x2) + 1, y1 + 2, color);
            graphics.fill(x2 - 1, Math.min(y1, y2), x2 + 2, Math.max(y1, y2) + 1, color);
        }
    }

    @Unique
    private void logic$drawHandle(GuiGraphicsExtractor graphics, EditorWireRouting.Point point, int color) {
        int x = screenX(point.x());
        int y = screenY(point.y());
        graphics.fill(x - 4, y - 4, x + 5, y + 5, 0xFF12171D);
        graphics.outline(x - 5, y - 5, 11, 11, color);
    }

    @Unique
    private void logic$cancelExtension(String message) {
        logic$extension = null;
        status.accept(message);
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
    private static void logic$appendRoutePoint(List<RoutePoint> route, RoutePoint point) {
        if (point == null) return;
        double x = EditorGrid.snap(point.x());
        double y = EditorGrid.snap(point.y());
        if (!route.isEmpty()) {
            RoutePoint last = route.getLast();
            if (logic$same(last.x(), last.y(), x, y)) return;
        }
        route.add(new RoutePoint(x, y));
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
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    @Unique private record LogicTap(double x, double y, int segmentIndex) {}
    @Unique private record LogicInputHit(EditorNode node, int port, PortSpec spec) {}
    @Unique private record LogicExtension(WireConnection parent, LogicTap tap, int width,
                                          ArrayList<EditorWireRouting.Point> corners) {}
}
