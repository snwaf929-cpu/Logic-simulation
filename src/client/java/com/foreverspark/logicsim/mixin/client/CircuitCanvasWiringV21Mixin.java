package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.v2.EditorGrid;
import com.foreverspark.logicsim.client.screen.v2.EditorHistoryAccess;
import com.foreverspark.logicsim.client.screen.v2.EditorPinGeometry;
import com.foreverspark.logicsim.client.screen.v2.EditorWireGeometry;
import com.foreverspark.logicsim.client.screen.v2.EditorWireRouting;
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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * Editor V2.1B canonical wire controller.
 *
 * <p>This replaces the old click-anchor router and the separate wire-selection mixin. One state machine now owns
 * wire creation, direct selection, route handles, corner/segment dragging, RMB/Esc cancellation and wire marquee.
 * The legacy base E-mode remains disabled and is used only as dormant implementation detail.</p>
 */
@Mixin(value = CircuitCanvasWidget.class, priority = 2650)
public abstract class CircuitCanvasWiringV21Mixin implements EditorWireSelectionAccess {
    @Shadow private CircuitDocument document;
    @Shadow private WireConnection selectedWire;
    @Shadow @Final private LinkedHashSet<Integer> selectedNodeIds;
    @Shadow private Integer selectedNodeId;
    @Shadow private boolean wireEditMode;
    @Shadow private NodeKind placementKind;
    @Shadow private boolean marqueePending;
    @Shadow private boolean marqueeActive;
    @Shadow private double marqueeStartX;
    @Shadow private double marqueeStartY;
    @Shadow private double marqueeCurrentX;
    @Shadow private double marqueeCurrentY;
    @Shadow private double lastWireClickX;
    @Shadow private double lastWireClickY;
    @Shadow private double zoom;
    @Shadow @Final private Consumer<String> status;

    @Shadow private EditorNode nodeAt(double mouseX, double mouseY) { throw new AssertionError(); }
    @Shadow private WireConnection wireAt(double mouseX, double mouseY) { throw new AssertionError(); }
    @Shadow private void beginMarquee(double mouseX, double mouseY) { throw new AssertionError(); }
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

    @Unique private LogicRoute logic$route;
    @Unique private final LinkedHashSet<WireConnection> logic$wireSelection = new LinkedHashSet<>();
    @Unique private boolean logic$wireMarquee;
    @Unique private boolean logic$wireMarqueeAdditive;
    @Unique private Integer logic$pendingRoutePointDrag;
    @Unique private Integer logic$pendingSegmentDrag;
    @Unique private Integer logic$draggingRoutePoint;
    @Unique private Integer logic$draggingSegment;
    @Unique private boolean logic$dragHistoryActive;

    /* ----------------------------- selection API ----------------------------- */

    @Override
    public boolean logic$isWireSelected(WireConnection wire) {
        logic$pruneWireSelection();
        return wire != null && (wire == selectedWire || logic$wireSelection.contains(wire));
    }

    @Override
    public List<WireConnection> logic$selectedWires() {
        logic$pruneWireSelection();
        if (logic$wireSelection.isEmpty()) {
            return selectedWire != null && document.wires.contains(selectedWire) ? List.of(selectedWire) : List.of();
        }
        return List.copyOf(logic$wireSelection);
    }

    /* ----------------------------- pointer interaction ----------------------------- */

    @Inject(method = "onClick", at = @At("HEAD"), cancellable = true)
    private void logic$wireClick(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        int button = event.button();

        // The most important V2.1B rule: RMB cancels an unfinished wire before the base canvas can start panning.
        if (button == 1 && logic$route != null) {
            logic$route = null;
            status.accept("WIRE cancelled");
            ci.cancel();
            return;
        }
        if (button != 0) return;
        if (placementKind != null) return;

        boolean alt = (event.modifiers() & GLFW.GLFW_MOD_ALT) != 0;
        boolean shift = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        boolean control = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;

        LogicPinHit pin = logic$pinAt(event.x(), event.y());
        // Alt+pin remains the established batch-pin selection gesture owned by EditorV2.
        if (alt && pin != null) return;

        if (logic$route != null) {
            if (pin != null) logic$advanceOrFinishRoute(pin);
            else if (nodeAt(event.x(), event.y()) == null) logic$appendRouteCorner(event.x(), event.y());
            else status.accept("WIRE: click empty grid for a corner, a compatible pin to finish, or RMB/Esc to cancel");
            ci.cancel();
            return;
        }

        if (!alt && pin != null) {
            logic$beginRoute(pin);
            ci.cancel();
            return;
        }

        // Ctrl+double-click is reserved for the existing shared-net BRANCH feature. Plain double-click belongs to
        // route editing now, eliminating the old gesture collision.
        WireConnection hit = nodeAt(event.x(), event.y()) == null ? wireAt(event.x(), event.y()) : null;
        if (control && doubleClick && hit != null) return;

        if (alt) {
            logic$handleAltWireSelection(hit, shift, event.x(), event.y());
            ci.cancel();
            return;
        }

        if (hit == null) {
            logic$wireSelection.clear();
            logic$clearWireDragState();
            return;
        }

        logic$selectWire(hit);
        lastWireClickX = event.x();
        lastWireClickY = event.y();

        EditorWireRouting.Point start = logic$wireStart(hit);
        EditorWireRouting.Point end = logic$wireEnd(hit);
        LogicRoutePointHit routePoint = logic$routePointAt(hit, start, end, event.x(), event.y());
        LogicSegmentHit segment = routePoint == null
                ? logic$segmentAt(hit, start, end, event.x(), event.y())
                : null;

        if (doubleClick && segment != null) {
            logic$historyCheckpoint("Add wire route handles");
            int movable = EditorWireRouting.addSegmentHandles(
                    hit, start, end, segment.directIndex(), worldX(event.x()), worldY(event.y()));
            if (movable >= 0) {
                logic$historyCommit();
                status.accept("WIRE: route handles added — drag the new middle segment perpendicular");
            } else {
                logic$historyCommit();
                status.accept("WIRE: segment is too short to add another route handle");
            }
            ci.cancel();
            return;
        }

        logic$pendingRoutePointDrag = routePoint == null ? null : routePoint.routeIndex();
        logic$pendingSegmentDrag = segment == null ? null : segment.directIndex();
        status.accept(routePoint != null
                ? "WIRE selected — drag this corner; Del deletes; Ctrl+double-click trace branches"
                : "WIRE selected — drag a segment perpendicular, double-click adds handles, Ctrl+double-click branches");
        ci.cancel();
    }

    @Inject(method = "onDrag", at = @At("HEAD"), cancellable = true)
    private void logic$wireDrag(MouseButtonEvent event, double dx, double dy, CallbackInfo ci) {
        if (event.button() != 0 || selectedWire == null || !document.wires.contains(selectedWire)) return;

        if (logic$pendingRoutePointDrag != null || logic$draggingRoutePoint != null) {
            if (logic$draggingRoutePoint == null) {
                logic$historyCheckpoint("Move wire corner");
                EditorWireRouting.Point start = logic$wireStart(selectedWire);
                EditorWireRouting.Point end = logic$wireEnd(selectedWire);
                EditorWireRouting.materialize(selectedWire, start, end);
                logic$draggingRoutePoint = logic$pendingRoutePointDrag;
                logic$pendingRoutePointDrag = null;
                logic$dragHistoryActive = true;
            }
            EditorWireRouting.moveRoutePoint(
                    selectedWire, logic$wireStart(selectedWire), logic$wireEnd(selectedWire),
                    logic$draggingRoutePoint, worldX(event.x()), worldY(event.y()));
            ci.cancel();
            return;
        }

        if (logic$pendingSegmentDrag != null || logic$draggingSegment != null) {
            if (logic$draggingSegment == null) {
                logic$historyCheckpoint("Move wire segment");
                EditorWireRouting.Point start = logic$wireStart(selectedWire);
                EditorWireRouting.Point end = logic$wireEnd(selectedWire);
                int prepared = EditorWireRouting.prepareSegmentDrag(
                        selectedWire, start, end, logic$pendingSegmentDrag,
                        worldX(event.x()), worldY(event.y()));
                logic$pendingSegmentDrag = null;
                if (prepared < 0) {
                    logic$historyCommit();
                    status.accept("WIRE: this segment is too short to drag; double-click a longer span to add handles");
                    logic$dragHistoryActive = false;
                    ci.cancel();
                    return;
                }
                logic$draggingSegment = prepared;
                logic$dragHistoryActive = true;
            }
            EditorWireRouting.moveSegment(selectedWire, logic$draggingSegment, dx / zoom, dy / zoom);
            EditorWireRouting.snapAndAlign(selectedWire, logic$wireStart(selectedWire), logic$wireEnd(selectedWire));
            ci.cancel();
        }
    }

    @Inject(method = "onRelease", at = @At("HEAD"))
    private void logic$wireRelease(MouseButtonEvent event, CallbackInfo ci) {
        if (event.button() != 0) return;
        if (selectedWire != null && document.wires.contains(selectedWire)
                && (logic$draggingRoutePoint != null || logic$draggingSegment != null)) {
            EditorWireRouting.snapAndAlign(selectedWire, logic$wireStart(selectedWire), logic$wireEnd(selectedWire));
        }
        if (logic$dragHistoryActive) logic$historyCommit();
        logic$pendingRoutePointDrag = null;
        logic$pendingSegmentDrag = null;
        logic$draggingRoutePoint = null;
        logic$draggingSegment = null;
        logic$dragHistoryActive = false;
    }

    /* ----------------------------- creation ----------------------------- */

    @Unique
    private void logic$beginRoute(LogicPinHit pin) {
        logic$wireSelection.clear();
        logic$clearWireDragState();
        logic$route = new LogicRoute(pin, new ArrayList<>());
        selectedWire = null;
        selectedNodeIds.clear();
        selectedNodeId = null;
        wireEditMode = false;
        status.accept("WIRE " + pin.spec().width() + "-bit: click empty grid for corners, then a matching "
                + (pin.input() ? "output" : "input") + " pin; RMB/Esc cancels");
    }

    @Unique
    private void logic$appendRouteCorner(double mouseX, double mouseY) {
        EditorWireRouting.Point point = new EditorWireRouting.Point(
                EditorGrid.snap(worldX(mouseX)), EditorGrid.snap(worldY(mouseY)));
        if (logic$route.corners().isEmpty() || !logic$same(logic$route.corners().getLast(), point)) {
            logic$route.corners().add(point);
            status.accept("WIRE: corner " + logic$route.corners().size() + " placed — continue or click destination pin");
        }
    }

    @Unique
    private void logic$advanceOrFinishRoute(LogicPinHit pin) {
        LogicPinHit first = logic$route.start();
        if (first.input() == pin.input()) {
            logic$route = new LogicRoute(pin, new ArrayList<>());
            status.accept("WIRE start changed to " + logic$pinLabel(pin));
            return;
        }

        LogicPinHit output = first.input() ? pin : first;
        LogicPinHit input = first.input() ? first : pin;
        if (output.spec().width() != input.spec().width()) {
            status.accept("WIDTH MISMATCH: " + logic$pinLabel(output) + " [" + output.spec().width() + "] -> "
                    + logic$pinLabel(input) + " [" + input.spec().width() + "]");
            return;
        }

        ArrayList<EditorWireRouting.Point> waypoints = new ArrayList<>(logic$route.corners());
        if (first.input()) Collections.reverse(waypoints);

        logic$historyCheckpoint("Create wire");
        try {
            document.connect(output.node().id, output.port(), input.node().id, input.port());
            WireConnection created = document.wires.getLast();
            created.setRoutePoints(EditorWireRouting.explicitRoute(
                    logic$point(output), waypoints, logic$point(input)));
            selectedWire = created;
            logic$wireSelection.clear();
            logic$wireSelection.add(created);
            selectedNodeIds.clear();
            selectedNodeId = null;
            wireEditMode = false;
            logic$route = null;
            recompile();
            logic$historyCommit();
            status.accept("Connected " + output.spec().width() + "-bit "
                    + (output.spec().width() == 1 ? "wire" : "bus")
                    + " — click the trace anytime to reshape it");
        } catch (RuntimeException exception) {
            logic$historyCommit();
            status.accept("ERROR: Cannot connect: " + logic$message(exception));
        }
    }

    /* ----------------------------- direct selection / multi-wire ----------------------------- */

    @Unique
    private void logic$selectWire(WireConnection wire) {
        logic$leavePinSelectionMode();
        logic$wireSelection.clear();
        logic$wireSelection.add(wire);
        selectedWire = wire;
        selectedNodeIds.clear();
        selectedNodeId = null;
        wireEditMode = false;
        marqueePending = false;
        marqueeActive = false;
        logic$wireMarquee = false;
        logic$clearWireDragState();
    }

    @Unique
    private void logic$handleAltWireSelection(WireConnection hit, boolean additive, double mouseX, double mouseY) {
        logic$leavePinSelectionMode();
        logic$clearWireDragState();
        if (hit != null) {
            logic$wireMarquee = false;
            if (!additive) logic$wireSelection.clear();
            if (additive && logic$wireSelection.contains(hit)) logic$wireSelection.remove(hit);
            else logic$wireSelection.add(hit);
            selectedWire = logic$wireSelection.isEmpty() ? null : logic$wireSelection.getLast();
            selectedNodeIds.clear();
            selectedNodeId = null;
            wireEditMode = false;
            marqueePending = false;
            marqueeActive = false;
            lastWireClickX = mouseX;
            lastWireClickY = mouseY;
            status.accept(logic$wireSelectionStatus());
            return;
        }

        logic$wireMarquee = true;
        logic$wireMarqueeAdditive = additive;
        beginMarquee(mouseX, mouseY);
        status.accept(additive ? "WIRE SELECT: drag to add intersecting traces" : "WIRE SELECT: drag across traces");
    }

    @Inject(method = "finishMarqueeSelection", at = @At("HEAD"), cancellable = true)
    private void logic$finishWireMarquee(CallbackInfo ci) {
        if (!logic$wireMarquee) return;
        double left = Math.min(marqueeStartX, marqueeCurrentX) - 3.0;
        double right = Math.max(marqueeStartX, marqueeCurrentX) + 3.0;
        double top = Math.min(marqueeStartY, marqueeCurrentY) - 3.0;
        double bottom = Math.max(marqueeStartY, marqueeCurrentY) + 3.0;

        LinkedHashSet<WireConnection> hits = logic$wireMarqueeAdditive
                ? new LinkedHashSet<>(logic$selectedWires()) : new LinkedHashSet<>();
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

    @Inject(method = "deletionIntent", at = @At("HEAD"), cancellable = true)
    private void logic$multiWireDeletionIntent(CallbackInfoReturnable<CircuitCanvasWidget.DeletionIntent> cir) {
        List<WireConnection> wires = logic$selectedWires();
        if (wires.size() <= 1) return;
        cir.setReturnValue(new CircuitCanvasWidget.DeletionIntent(true, false, "Delete " + wires.size() + " selected wires"));
    }

    @Inject(method = "deleteSelectionConfirmed", at = @At("HEAD"), cancellable = true)
    private void logic$deleteMultiWireSelection(CallbackInfo ci) {
        List<WireConnection> wires = logic$selectedWires();
        if (wires.size() <= 1) return;
        logic$historyCheckpoint("Delete wires");
        int removed = 0;
        for (WireConnection wire : wires) if (document.wires.remove(wire)) removed++;
        logic$wireSelection.clear();
        selectedWire = null;
        wireEditMode = false;
        recompile();
        logic$historyCommit();
        status.accept("Deleted " + removed + " selected wire" + (removed == 1 ? "" : "s"));
        ci.cancel();
    }

    /* ----------------------------- remove legacy E/+ mode ----------------------------- */

    @Inject(method = "toggleWireEditMode", at = @At("HEAD"), cancellable = true)
    private void logic$directEditInsteadOfLegacyMode(CallbackInfoReturnable<Boolean> cir) {
        wireEditMode = false;
        logic$clearWireDragState();
        if (selectedWire == null || !document.wires.contains(selectedWire)) {
            status.accept("WIRE: click a trace directly — E mode is no longer needed");
            cir.setReturnValue(false);
            return;
        }
        status.accept("WIRE: selected traces are always editable — drag corners/segments directly; double-click adds handles");
        cir.setReturnValue(true);
    }

    @Inject(method = "addRoutePointToSelection", at = @At("HEAD"), cancellable = true)
    private void logic$directAddRouteHandles(CallbackInfoReturnable<Boolean> cir) {
        if (selectedWire == null || !document.wires.contains(selectedWire)) {
            status.accept("WIRE: select a trace first; double-click it to add route handles");
            cir.setReturnValue(false);
            return;
        }
        EditorWireRouting.Point start = logic$wireStart(selectedWire);
        EditorWireRouting.Point end = logic$wireEnd(selectedWire);
        LogicSegmentHit segment = logic$segmentAt(selectedWire, start, end, lastWireClickX, lastWireClickY);
        if (segment == null) {
            status.accept("WIRE: click the segment where you want handles, then press +; double-click does the same directly");
            cir.setReturnValue(false);
            return;
        }
        logic$historyCheckpoint("Add wire route handles");
        int result = EditorWireRouting.addSegmentHandles(
                selectedWire, start, end, segment.directIndex(), worldX(lastWireClickX), worldY(lastWireClickY));
        logic$historyCommit();
        if (result >= 0) status.accept("WIRE: route handles added — drag the middle segment perpendicular");
        else status.accept("WIRE: segment is too short for another pair of handles");
        cir.setReturnValue(result >= 0);
    }

    /* ----------------------------- cancellation / lifecycle ----------------------------- */

    @Inject(method = "cancelTransientMode", at = @At("HEAD"), cancellable = true)
    private void logic$cancelActiveWire(CallbackInfoReturnable<Boolean> cir) {
        if (logic$route == null) return;
        logic$route = null;
        logic$clearWireDragState();
        status.accept("WIRE cancelled");
        cir.setReturnValue(true);
    }

    @Inject(method = "setDocument(Lcom/foreverspark/logicsim/editor/model/CircuitDocument;Ljava/lang/String;)V", at = @At("RETURN"))
    private void logic$clearOnDocument(CircuitDocument replacement, String name, CallbackInfo ci) {
        logic$route = null;
        logic$wireSelection.clear();
        logic$wireMarquee = false;
        logic$clearWireDragState();
    }

    @Inject(method = "openNestedChip", at = @At("RETURN"))
    private void logic$clearOnNested(EditorNode node, CallbackInfo ci) {
        logic$route = null;
        logic$wireSelection.clear();
        logic$wireMarquee = false;
        logic$clearWireDragState();
    }

    @Inject(method = "navigateBack", at = @At("RETURN"))
    private void logic$clearOnBack(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) return;
        logic$route = null;
        logic$wireSelection.clear();
        logic$wireMarquee = false;
        logic$clearWireDragState();
    }

    /* ----------------------------- overlay ----------------------------- */

    @Inject(method = "extractWidgetRenderState", at = @At(value = "INVOKE", target = "Lcom/foreverspark/logicsim/client/screen/CircuitCanvasWidget;drawMarquee(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V", shift = At.Shift.BEFORE))
    private void logic$drawWireUi(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (logic$route != null) logic$drawRoutePreview(graphics, mouseX, mouseY);
        if (selectedWire != null && document.wires.contains(selectedWire)) logic$drawRouteHandles(graphics, selectedWire);
    }

    @Unique
    private void logic$drawRoutePreview(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        EditorWireRouting.Point current = logic$point(logic$route.start());
        for (EditorWireRouting.Point corner : logic$route.corners()) {
            logic$drawOrthogonal(graphics, current, corner, 0xFF6CA9FF, 2);
            logic$drawHandle(graphics, corner, 0xFF8FC5FF);
            current = corner;
        }
        LogicPinHit target = logic$pinAt(mouseX, mouseY);
        EditorWireRouting.Point end = target == null
                ? new EditorWireRouting.Point(EditorGrid.snap(worldX(mouseX)), EditorGrid.snap(worldY(mouseY)))
                : logic$point(target);
        int color = 0xFF6CA9FF;
        if (target != null && target.input() != logic$route.start().input()) {
            color = target.spec().width() == logic$route.start().spec().width() ? 0xFF55D96B : 0xFFE05252;
        }
        logic$drawOrthogonal(graphics, current, end, color, 2);
    }

    @Unique
    private void logic$drawRouteHandles(GuiGraphicsExtractor graphics, WireConnection wire) {
        EditorWireRouting.Point start = logic$wireStart(wire);
        EditorWireRouting.Point end = logic$wireEnd(wire);
        List<RoutePoint> route = EditorWireRouting.visibleRoute(wire, start, end);
        for (RoutePoint point : route) logic$drawHandle(graphics, new EditorWireRouting.Point(point.x(), point.y()), 0xFF79C4FF);
    }

    @Unique
    private void logic$drawHandle(GuiGraphicsExtractor graphics, EditorWireRouting.Point point, int color) {
        int x = screenX(point.x());
        int y = screenY(point.y());
        graphics.fill(x - 4, y - 4, x + 5, y + 5, 0xFF12171D);
        graphics.outline(x - 5, y - 5, 11, 11, color);
    }

    @Unique
    private void logic$drawOrthogonal(
            GuiGraphicsExtractor graphics, EditorWireRouting.Point a, EditorWireRouting.Point b, int color, int thickness) {
        int x1 = screenX(a.x()), y1 = screenY(a.y());
        int x2 = screenX(b.x()), y2 = screenY(b.y());
        if (Math.abs(a.y() - b.y()) < 0.001) {
            graphics.fill(Math.min(x1, x2), y1 - thickness / 2, Math.max(x1, x2) + 1, y1 + (thickness + 1) / 2, color);
        } else if (Math.abs(a.x() - b.x()) < 0.001) {
            graphics.fill(x1 - thickness / 2, Math.min(y1, y2), x1 + (thickness + 1) / 2, Math.max(y1, y2) + 1, color);
        } else {
            graphics.fill(Math.min(x1, x2), y1 - thickness / 2, Math.max(x1, x2) + 1, y1 + (thickness + 1) / 2, color);
            graphics.fill(x2 - thickness / 2, Math.min(y1, y2), x2 + (thickness + 1) / 2, Math.max(y1, y2) + 1, color);
        }
    }

    /* ----------------------------- geometry ----------------------------- */

    @Unique
    private LogicPinHit logic$pinAt(double mouseX, double mouseY) {
        for (int n = document.nodes.size() - 1; n >= 0; n--) {
            EditorNode node = document.nodes.get(n);
            List<PortSpec> outputs = safeOutputs(node);
            for (int port = 0; port < outputs.size(); port++) {
                EditorWireRouting.Point point = logic$outputPoint(node, port);
                if (EditorPinGeometry.contains(mouseX - screenX(point.x()), mouseY - screenY(point.y()), outputs.get(port).width())) {
                    return new LogicPinHit(node, port, outputs.get(port), false);
                }
            }
        }
        for (int n = document.nodes.size() - 1; n >= 0; n--) {
            EditorNode node = document.nodes.get(n);
            List<PortSpec> inputs = safeInputs(node);
            for (int port = 0; port < inputs.size(); port++) {
                EditorWireRouting.Point point = logic$inputPoint(node, port);
                if (EditorPinGeometry.contains(mouseX - screenX(point.x()), mouseY - screenY(point.y()), inputs.get(port).width())) {
                    return new LogicPinHit(node, port, inputs.get(port), true);
                }
            }
        }
        return null;
    }

    @Unique
    private LogicRoutePointHit logic$routePointAt(
            WireConnection wire, EditorWireRouting.Point start, EditorWireRouting.Point end,
            double mouseX, double mouseY) {
        List<RoutePoint> route = EditorWireRouting.visibleRoute(wire, start, end);
        for (int index = route.size() - 1; index >= 0; index--) {
            RoutePoint point = route.get(index);
            double dx = mouseX - screenX(point.x());
            double dy = mouseY - screenY(point.y());
            if (dx * dx + dy * dy <= 81.0) return new LogicRoutePointHit(index);
        }
        return null;
    }

    @Unique
    private LogicSegmentHit logic$segmentAt(
            WireConnection wire, EditorWireRouting.Point start, EditorWireRouting.Point end,
            double mouseX, double mouseY) {
        if (!Double.isFinite(mouseX) || !Double.isFinite(mouseY)) return null;
        for (EditorWireRouting.Segment segment : EditorWireRouting.segments(wire, start, end, true)) {
            if (wire.segmentLayer(segment.index()) != logic$currentLayer()) continue;
            double distance = logic$distanceToSegment(
                    mouseX, mouseY,
                    screenX(segment.a().x()), screenY(segment.a().y()),
                    screenX(segment.b().x()), screenY(segment.b().y()));
            if (distance <= 7.0) return new LogicSegmentHit(segment.index());
        }
        return null;
    }

    @Unique
    private boolean logic$wireCrossesBox(WireConnection wire, double left, double right, double top, double bottom) {
        EditorWireRouting.Point start = logic$wireStart(wire);
        EditorWireRouting.Point end = logic$wireEnd(wire);
        WireLayer layer = logic$currentLayer();
        for (EditorWireRouting.Segment segment : EditorWireRouting.segments(wire, start, end, true)) {
            if (wire.segmentLayer(segment.index()) != layer) continue;
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
    private EditorWireRouting.Point logic$point(LogicPinHit hit) {
        return hit.input() ? logic$inputPoint(hit.node(), hit.port()) : logic$outputPoint(hit.node(), hit.port());
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
    private static double logic$distanceToSegment(double px, double py, double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        if (dx == 0.0 && dy == 0.0) return Math.hypot(px - x1, py - y1);
        double t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy);
        t = Math.max(0.0, Math.min(1.0, t));
        return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
    }

    /* ----------------------------- small helpers ----------------------------- */

    @Unique
    private void logic$leavePinSelectionMode() {
        ((CircuitCanvasWidget) (Object) this).selectAllNodes();
        selectedNodeIds.clear();
        selectedNodeId = null;
    }

    @Unique
    private void logic$historyCheckpoint(String label) {
        Object self = this;
        if (self instanceof EditorHistoryAccess history) history.logic$checkpoint(label);
    }

    @Unique
    private void logic$historyCommit() {
        Object self = this;
        if (self instanceof EditorHistoryAccess history) history.logic$commitHistory();
    }

    @Unique
    private void logic$clearWireDragState() {
        logic$pendingRoutePointDrag = null;
        logic$pendingSegmentDrag = null;
        logic$draggingRoutePoint = null;
        logic$draggingSegment = null;
        if (logic$dragHistoryActive) logic$historyCommit();
        logic$dragHistoryActive = false;
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
    private String logic$wireSelectionStatus() {
        logic$pruneWireSelection();
        int count = logic$wireSelection.size();
        if (count == 0) return "No wires selected";
        if (count == 1) return "1 wire selected — drag to edit; Alt+Shift adds/removes traces; Del deletes";
        return count + " wires selected — Alt+Shift adds/removes traces; Del deletes all";
    }

    @Unique
    private String logic$pinLabel(LogicPinHit hit) {
        String name = hit.spec().name() == null || hit.spec().name().isBlank()
                ? (hit.input() ? "IN" : "OUT") : hit.spec().name();
        return hit.node().displayName() + "." + name;
    }

    @Unique private static boolean logic$same(EditorWireRouting.Point a, EditorWireRouting.Point b) {
        return Math.abs(a.x() - b.x()) < 0.001 && Math.abs(a.y() - b.y()) < 0.001;
    }

    @Unique private static String logic$message(RuntimeException exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    @Unique private record LogicRoute(LogicPinHit start, ArrayList<EditorWireRouting.Point> corners) {}
    @Unique private record LogicPinHit(EditorNode node, int port, PortSpec spec, boolean input) {}
    @Unique private record LogicRoutePointHit(int routeIndex) {}
    @Unique private record LogicSegmentHit(int directIndex) {}
}
