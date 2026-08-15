package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.v2.EditorGrid;
import com.foreverspark.logicsim.client.screen.v2.EditorHistoryAccess;
import com.foreverspark.logicsim.client.screen.v2.EditorPinGeometry;
import com.foreverspark.logicsim.client.screen.v2.EditorWireGeometry;
import com.foreverspark.logicsim.client.screen.v2.EditorWireSelectionAccess;
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
 * Unifies wire selection with the V2 editor without reviving the legacy route-handle editor.
 * Alt-click is context sensitive: an exact pin remains pin selection, an exact visible trace is
 * wire selection, and Alt-drag on empty canvas selects every visible trace that crosses the box.
 */
@Mixin(value = CircuitCanvasWidget.class, priority = 2500)
public abstract class CircuitCanvasWireSelectionMixin implements EditorWireSelectionAccess {
    @Shadow private CircuitDocument document;
    @Shadow private WireConnection selectedWire;
    @Shadow @Final private LinkedHashSet<Integer> selectedNodeIds;
    @Shadow private Integer selectedNodeId;
    @Shadow private boolean wireEditMode;
    @Shadow private boolean marqueePending;
    @Shadow private boolean marqueeActive;
    @Shadow private double marqueeStartX;
    @Shadow private double marqueeStartY;
    @Shadow private double marqueeCurrentX;
    @Shadow private double marqueeCurrentY;
    @Shadow @Final private Consumer<String> status;

    @Shadow private EditorNode nodeAt(double mouseX, double mouseY) { throw new AssertionError(); }
    @Shadow private WireConnection wireAt(double mouseX, double mouseY) { throw new AssertionError(); }
    @Shadow private void beginMarquee(double mouseX, double mouseY) { throw new AssertionError(); }
    @Shadow private void recompile() { throw new AssertionError(); }
    @Shadow private int screenX(double worldX) { throw new AssertionError(); }
    @Shadow private int screenY(double worldY) { throw new AssertionError(); }
    @Shadow private double nodeWidth(EditorNode node) { throw new AssertionError(); }
    @Shadow private double nodeHeight(EditorNode node) { throw new AssertionError(); }
    @Shadow private double portStep(EditorNode node) { throw new AssertionError(); }
    @Shadow private double centeredPortY(EditorNode node, int port, int count) { throw new AssertionError(); }
    @Shadow private List<PortSpec> safeInputs(EditorNode node) { throw new AssertionError(); }
    @Shadow private List<PortSpec> safeOutputs(EditorNode node) { throw new AssertionError(); }

    @Unique private final LinkedHashSet<WireConnection> logic$wireSelection = new LinkedHashSet<>();
    @Unique private boolean logic$wireMarquee;
    @Unique private boolean logic$wireMarqueeAdditive;

    @Override
    public boolean logic$isWireSelected(WireConnection wire) {
        logic$pruneWireSelection();
        return wire != null && (wire == selectedWire || logic$wireSelection.contains(wire));
    }

    @Override
    public List<WireConnection> logic$selectedWires() {
        logic$pruneWireSelection();
        if (logic$wireSelection.isEmpty()) {
            if (selectedWire != null && document.wires.contains(selectedWire)) return List.of(selectedWire);
            return List.of();
        }
        return List.copyOf(logic$wireSelection);
    }

    @Inject(method = "onClick", at = @At("HEAD"), cancellable = true)
    private void logic$wireSelectClick(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        if (event.button() != 0) return;

        boolean alt = (event.modifiers() & GLFW.GLFW_MOD_ALT) != 0;
        boolean shift = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        if (!alt) {
            logic$wireMarquee = false;
            logic$wireSelection.clear();
            return;
        }

        // Exact pins keep the existing Alt / Alt+Shift pin-selection behavior.
        if (logic$exactPinAt(event.x(), event.y())) {
            logic$wireSelection.clear();
            logic$wireMarquee = false;
            return;
        }

        // Do not select a hidden trace underneath a component body.
        WireConnection hit = nodeAt(event.x(), event.y()) == null ? wireAt(event.x(), event.y()) : null;
        if (hit != null) {
            logic$leavePinSelectionMode();
            logic$wireMarquee = false;
            if (!shift) logic$wireSelection.clear();
            if (shift && logic$wireSelection.contains(hit)) {
                logic$wireSelection.remove(hit);
                selectedWire = logic$wireSelection.isEmpty() ? null : logic$wireSelection.getLast();
            } else {
                logic$wireSelection.add(hit);
                selectedWire = hit;
            }
            selectedNodeIds.clear();
            selectedNodeId = null;
            wireEditMode = false;
            marqueePending = false;
            marqueeActive = false;
            status.accept(logic$wireSelectionStatus());
            ci.cancel();
            return;
        }

        logic$leavePinSelectionMode();
        logic$wireMarquee = true;
        logic$wireMarqueeAdditive = shift;
        beginMarquee(event.x(), event.y());
        status.accept(shift ? "WIRE SELECT: drag a box to add intersecting traces" : "WIRE SELECT: drag a box across traces");
        ci.cancel();
    }

    @Inject(method = "finishMarqueeSelection", at = @At("HEAD"), cancellable = true)
    private void logic$finishWireMarquee(CallbackInfo ci) {
        if (!logic$wireMarquee) return;

        double left = Math.min(marqueeStartX, marqueeCurrentX) - 3.0;
        double right = Math.max(marqueeStartX, marqueeCurrentX) + 3.0;
        double top = Math.min(marqueeStartY, marqueeCurrentY) - 3.0;
        double bottom = Math.max(marqueeStartY, marqueeCurrentY) + 3.0;

        LinkedHashSet<WireConnection> hits = logic$wireMarqueeAdditive
                ? new LinkedHashSet<>(logic$selectedWires())
                : new LinkedHashSet<>();
        for (WireConnection wire : document.wires) {
            if (logic$wireCrossesBox(wire, left, right, top, bottom)) hits.add(wire);
        }

        logic$wireSelection.clear();
        logic$wireSelection.addAll(hits);
        selectedWire = hits.isEmpty() ? null : hits.getLast();
        selectedNodeIds.clear();
        selectedNodeId = null;
        wireEditMode = false;
        logic$wireMarquee = false;
        status.accept(logic$wireSelectionStatus());
        ci.cancel();
    }

    /** Draw every selected trace as one selection set; PCB rendering still owns signal/layer colors. */
    @Inject(method = "extractWidgetRenderState", at = @At(value = "INVOKE", target = "Lcom/foreverspark/logicsim/client/screen/CircuitCanvasWidget;drawMarquee(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V", shift = At.Shift.BEFORE))
    private void logic$drawMultiWireSelection(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        logic$pruneWireSelection();
        if (logic$wireSelection.size() <= 1) return;
        WireLayer view = logic$currentLayer();
        for (WireConnection wire : logic$wireSelection) {
            for (LogicLayerSegment segment : logic$segments(wire)) {
                if (segment.layer() != view) continue;
                logic$drawSelectedSegment(graphics, segment.a(), segment.b());
            }
        }
    }

    @Inject(method = "deletionIntent", at = @At("HEAD"), cancellable = true)
    private void logic$multiWireDeletionIntent(CallbackInfoReturnable<CircuitCanvasWidget.DeletionIntent> cir) {
        List<WireConnection> wires = logic$selectedWires();
        if (wires.size() <= 1) return;
        cir.setReturnValue(new CircuitCanvasWidget.DeletionIntent(true, false,
                "Delete " + wires.size() + " selected wires"));
    }

    @Inject(method = "deleteSelectionConfirmed", at = @At("HEAD"), cancellable = true)
    private void logic$deleteMultiWireSelection(CallbackInfo ci) {
        List<WireConnection> wires = logic$selectedWires();
        if (wires.size() <= 1) return;
        Object self = this;
        if (self instanceof EditorHistoryAccess history) history.logic$checkpoint("Delete wires");
        int removed = 0;
        for (WireConnection wire : wires) {
            if (document.wires.remove(wire)) removed++;
        }
        logic$wireSelection.clear();
        selectedWire = null;
        wireEditMode = false;
        recompile();
        if (self instanceof EditorHistoryAccess history) history.logic$commitHistory();
        status.accept("Deleted " + removed + " selected wire" + (removed == 1 ? "" : "s"));
        ci.cancel();
    }

    /** Phase 2 click-routing is now the only route-edit workflow; remove the conflicting legacy E/+ editor. */
    @Inject(method = "toggleWireEditMode", at = @At("HEAD"), cancellable = true)
    private void logic$disableLegacyWireEdit(CallbackInfoReturnable<Boolean> cir) {
        wireEditMode = false;
        status.accept("WIRE ROUTING: click a trace to set an anchor, then click empty grid to place a detour; legacy E edit is disabled");
        cir.setReturnValue(false);
    }

    @Inject(method = "addRoutePointToSelection", at = @At("HEAD"), cancellable = true)
    private void logic$disableLegacyRoutePoint(CallbackInfoReturnable<Boolean> cir) {
        wireEditMode = false;
        status.accept("WIRE ROUTING: use trace anchors/click routing; legacy + route handles are disabled");
        cir.setReturnValue(false);
    }

    @Inject(method = "setDocument(Lcom/foreverspark/logicsim/editor/model/CircuitDocument;Ljava/lang/String;)V", at = @At("RETURN"))
    private void logic$clearWireSelectionOnDocument(CircuitDocument replacement, String name, CallbackInfo ci) {
        logic$wireSelection.clear();
        logic$wireMarquee = false;
    }

    @Unique
    private void logic$leavePinSelectionMode() {
        // Editor V2 already clears its private pin set whenever selectAllNodes runs. Trigger that
        // public path, then immediately clear the temporary component selection before entering
        // wire mode. No document state changes and the final wire status replaces its status text.
        ((CircuitCanvasWidget)(Object)this).selectAllNodes();
        selectedNodeIds.clear();
        selectedNodeId = null;
    }

    @Unique
    private String logic$wireSelectionStatus() {
        logic$pruneWireSelection();
        int count = logic$wireSelection.size();
        if (count == 0) return "No wires selected";
        if (count == 1) return "1 wire selected — Alt+Shift adds/removes traces; Del deletes";
        return count + " wires selected — Alt+Shift adds/removes traces; Del deletes all";
    }

    @Unique
    private void logic$pruneWireSelection() {
        if (document == null) {
            logic$wireSelection.clear();
            selectedWire = null;
            return;
        }
        logic$wireSelection.removeIf(wire -> !document.wires.contains(wire));
        if (selectedWire != null && !document.wires.contains(selectedWire)) selectedWire = null;
    }

    @Unique
    private boolean logic$exactPinAt(double mouseX, double mouseY) {
        for (int n = document.nodes.size() - 1; n >= 0; n--) {
            EditorNode node = document.nodes.get(n);
            List<PortSpec> outputs = safeOutputs(node);
            for (int port = 0; port < outputs.size(); port++) {
                LogicPoint point = logic$outputPoint(node, port);
                if (EditorPinGeometry.contains(mouseX - screenX(point.x()), mouseY - screenY(point.y()), outputs.get(port).width())) return true;
            }
        }
        for (int n = document.nodes.size() - 1; n >= 0; n--) {
            EditorNode node = document.nodes.get(n);
            List<PortSpec> inputs = safeInputs(node);
            for (int port = 0; port < inputs.size(); port++) {
                LogicPoint point = logic$inputPoint(node, port);
                if (EditorPinGeometry.contains(mouseX - screenX(point.x()), mouseY - screenY(point.y()), inputs.get(port).width())) return true;
            }
        }
        return false;
    }

    @Unique
    private boolean logic$wireCrossesBox(WireConnection wire, double left, double right, double top, double bottom) {
        WireLayer view = logic$currentLayer();
        for (LogicLayerSegment segment : logic$segments(wire)) {
            if (segment.layer() != view) continue;
            if (EditorWireGeometry.segmentIntersectsRect(
                    screenX(segment.a().x()), screenY(segment.a().y()),
                    screenX(segment.b().x()), screenY(segment.b().y()),
                    left, right, top, bottom)) return true;
        }
        return false;
    }

    @Unique
    private WireLayer logic$currentLayer() {
        Object self = this;
        return self instanceof PcbLayerAccess pcb ? pcb.logic$currentPcbLayer() : WireLayer.FRONT;
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
                double middleX = EditorGrid.snap((start.x() + end.x()) * 0.5);
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

    @Unique private static boolean logic$aligned(LogicPoint a, LogicPoint b) {
        return Math.abs(a.x() - b.x()) < 0.001 || Math.abs(a.y() - b.y()) < 0.001;
    }

    @Unique private static void logic$append(List<LogicLayerSegment> result, LogicPoint a, LogicPoint b, WireLayer layer) {
        if (Math.hypot(b.x() - a.x(), b.y() - a.y()) > 0.001) result.add(new LogicLayerSegment(a, b, layer));
    }

    @Unique
    private void logic$drawSelectedSegment(GuiGraphicsExtractor graphics, LogicPoint a, LogicPoint b) {
        int x1 = screenX(a.x()), y1 = screenY(a.y());
        int x2 = screenX(b.x()), y2 = screenY(b.y());
        if (Math.abs(a.y() - b.y()) < 0.001) {
            graphics.fill(Math.min(x1, x2), y1 - 1, Math.max(x1, x2) + 1, y1 + 2, 0xFFFFFFFF);
        } else {
            graphics.fill(x1 - 1, Math.min(y1, y2), x1 + 2, Math.max(y1, y2) + 1, 0xFFFFFFFF);
        }
    }

    @Unique private record LogicPoint(double x, double y) {}
    @Unique private record LogicLayerSegment(LogicPoint a, LogicPoint b, WireLayer layer) {}
}
