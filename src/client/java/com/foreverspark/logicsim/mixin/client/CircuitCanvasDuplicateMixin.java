package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.v2.EditorGrid;
import com.foreverspark.logicsim.client.screen.v2.EditorHistoryAccess;
import com.foreverspark.logicsim.editor.model.BusSliceOutput;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.PortDirection;
import com.foreverspark.logicsim.editor.model.RoutePoint;
import com.foreverspark.logicsim.editor.model.WireConnection;
import com.foreverspark.logicsim.editor.model.WireLayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Deep-copy support for smart, grid-aligned editor duplication. */
@Mixin(value = CircuitCanvasWidget.class, priority = 1600)
public abstract class CircuitCanvasDuplicateMixin {
    @Unique private static final double LOGIC_PASTE_OFFSET = EditorGrid.MAJOR_STEP;

    @Shadow private CircuitDocument document;
    @Shadow @Final private LinkedHashSet<Integer> selectedNodeIds;
    @Shadow private WireConnection selectedWire;
    @Shadow private boolean wireEditMode;
    @Shadow @Final private Consumer<String> status;
    @Shadow @Final private Map<Integer, Long> inputStates;
    @Shadow private void setSelectedNodes(Iterable<Integer> nodeIds) { throw new AssertionError(); }
    @Shadow private void recompile() { throw new AssertionError(); }
    @Shadow private double nodeHeight(EditorNode node) { throw new AssertionError(); }

    @Unique private LogicClipboard logic$clipboard;
    @Unique private int logic$pasteSerial;

    @Inject(method = "copySelection", at = @At("HEAD"), cancellable = true)
    private void logic$copySelection(CallbackInfoReturnable<Boolean> cir) {
        if (selectedNodeIds.isEmpty()) {
            status.accept("Ctrl+C copies selected components; select one or more nodes first");
            cir.setReturnValue(false);
            return;
        }
        logic$clipboard = logic$captureSelection();
        logic$pasteSerial = 0;
        status.accept("Copied " + selectedNodeIds.size() + " node" + (selectedNodeIds.size() == 1 ? "" : "s")
                + (logic$clipboard.wires().isEmpty() ? "" : " with " + logic$clipboard.wires().size()
                + " internal wire" + (logic$clipboard.wires().size() == 1 ? "" : "s")));
        cir.setReturnValue(true);
    }

    @Inject(method = "pasteClipboard", at = @At("HEAD"), cancellable = true)
    private void logic$pasteClipboard(CallbackInfoReturnable<Boolean> cir) {
        if (logic$clipboard == null || logic$clipboard.nodes().isEmpty()) {
            status.accept("Clipboard is empty — select component(s) and press Ctrl+C first");
            cir.setReturnValue(false);
            return;
        }
        logic$duplicateHistoryCheckpoint("Paste");
        logic$pasteSerial++;
        double offset = LOGIC_PASTE_OFFSET * logic$pasteSerial;
        logic$pasteSelection(logic$clipboard, offset, offset);
        logic$duplicateHistoryCommit();
        status.accept("Pasted " + selectedNodeIds.size() + " node" + (selectedNodeIds.size() == 1 ? "" : "s"));
        cir.setReturnValue(true);
    }

    @Inject(method = "duplicateSelection", at = @At("HEAD"), cancellable = true)
    private void logic$duplicateSelection(CallbackInfoReturnable<Boolean> cir) {
        if (selectedNodeIds.isEmpty()) {
            status.accept("Ctrl+D duplicates selected components; select one or more nodes first");
            cir.setReturnValue(false);
            return;
        }
        logic$duplicateHistoryCheckpoint("Duplicate");
        LogicClipboard copy = logic$captureSelection();
        double verticalStep = EditorGrid.snapUp(copy.height()) + EditorGrid.duplicateGap();
        logic$pasteSelection(copy, 0.0, verticalStep);
        logic$duplicateHistoryCommit();
        status.accept("Duplicated " + selectedNodeIds.size() + " node" + (selectedNodeIds.size() == 1 ? "" : "s")
                + " below on the editor grid");
        cir.setReturnValue(true);
    }

    @Unique
    private LogicClipboard logic$captureSelection() {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (EditorNode node : document.nodes) {
            if (!selectedNodeIds.contains(node.id)) continue;
            minX = Math.min(minX, node.x);
            minY = Math.min(minY, node.y);
            maxY = Math.max(maxY, node.y + nodeHeight(node));
        }
        if (!Double.isFinite(minX)) minX = 0.0;
        if (!Double.isFinite(minY)) minY = 0.0;
        if (!Double.isFinite(maxY)) maxY = minY;

        List<NodeSnapshot> nodes = new ArrayList<>();
        for (EditorNode node : document.nodes) {
            if (!selectedNodeIds.contains(node.id)) continue;
            boolean hasInputState = inputStates.containsKey(node.id);
            long inputState = hasInputState ? inputStates.get(node.id) : 0L;
            nodes.add(NodeSnapshot.from(node, minX, minY, hasInputState, inputState));
        }

        List<WireSnapshot> wires = new ArrayList<>();
        for (WireConnection wire : document.wires) {
            if (!selectedNodeIds.contains(wire.sourceNodeId()) || !selectedNodeIds.contains(wire.targetNodeId())) continue;
            List<RoutePoint> route = new ArrayList<>();
            for (RoutePoint point : wire.routePoints()) route.add(new RoutePoint(point.x() - minX, point.y() - minY));
            RoutePoint branch = wire.branchStart();
            RoutePoint relativeBranch = branch == null ? null : new RoutePoint(branch.x() - minX, branch.y() - minY);
            wires.add(new WireSnapshot(
                    wire.sourceNodeId(), wire.sourcePort(), wire.targetNodeId(), wire.targetPort(),
                    List.copyOf(route), relativeBranch, wire.layer(), List.copyOf(wire.viaRouteIndices())));
        }
        return new LogicClipboard(minX, minY, Math.max(0.0, maxY - minY), List.copyOf(nodes), List.copyOf(wires));
    }

    @Unique
    private void logic$pasteSelection(LogicClipboard copy, double offsetX, double offsetY) {
        Map<Integer, Integer> ids = new LinkedHashMap<>();
        LinkedHashSet<Integer> pastedIds = new LinkedHashSet<>();
        double originX = EditorGrid.snap(copy.originX() + offsetX);
        double originY = EditorGrid.snap(copy.originY() + offsetY);

        for (NodeSnapshot snapshot : copy.nodes()) {
            EditorNode node = document.addNode(snapshot.kind(), EditorGrid.snap(originX + snapshot.relativeX()), EditorGrid.snap(originY + snapshot.relativeY()));
            snapshot.apply(node);
            if (snapshot.boardSocket()) {
                // Clipboard duplication is intentionally NOT a second instance of the same BOARD template.
                // It becomes authored board content, so a copied socket receives a fresh stable identity/order.
                node.templateInstanceId = 0;
                node.templateName = "";
                node.interfaceId = (snapshot.interfaceId().isBlank() ? "socket" : snapshot.interfaceId()) + "-copy-" + node.id;
                node.interfaceOrder = logic$nextAuthoredSocketOrder(node.id);
            }
            if (snapshot.hasInputState()) inputStates.put(node.id, snapshot.inputState());
            ids.put(snapshot.originalId(), node.id);
            pastedIds.add(node.id);
        }

        for (WireSnapshot wireSnapshot : copy.wires()) {
            Integer sourceId = ids.get(wireSnapshot.sourceNodeId());
            Integer targetId = ids.get(wireSnapshot.targetNodeId());
            if (sourceId == null || targetId == null) continue;
            document.connect(sourceId, wireSnapshot.sourcePort(), targetId, wireSnapshot.targetPort());
            WireConnection pastedWire = document.wires.getLast();
            pastedWire.routePoints().clear();
            for (RoutePoint point : wireSnapshot.routePoints()) {
                pastedWire.routePoints().add(new RoutePoint(EditorGrid.snap(originX + point.x()), EditorGrid.snap(originY + point.y())));
            }
            RoutePoint branch = wireSnapshot.branchStart();
            if (branch != null) pastedWire.setBranchStart(new RoutePoint(EditorGrid.snap(originX + branch.x()), EditorGrid.snap(originY + branch.y())));
            pastedWire.setLayer(wireSnapshot.layer());
            pastedWire.setViaRouteIndices(wireSnapshot.viaRouteIndices());
        }

        setSelectedNodes(pastedIds);
        selectedWire = null;
        wireEditMode = false;
        recompile();
    }

    @Unique
    private int logic$nextAuthoredSocketOrder(int ignoreNodeId) {
        int max = -1;
        for (EditorNode node : document.nodes) {
            if (node.id == ignoreNodeId || !node.isBoardSocket() || node.templateInstanceId > 0) continue;
            max = Math.max(max, node.interfaceOrder);
        }
        return max + 1;
    }

    // Keep bridge helper JVM signatures distinct from EditorHistoryAccess methods on the same
    // transformed CircuitCanvasWidget class.
    @Unique private void logic$duplicateHistoryCheckpoint(String label) {
        Object self = this;
        if (self instanceof EditorHistoryAccess history) history.logic$checkpoint(label);
    }

    @Unique private void logic$duplicateHistoryCommit() {
        Object self = this;
        if (self instanceof EditorHistoryAccess history) history.logic$commitHistory();
    }

    @Unique private record LogicClipboard(double originX, double originY, double height, List<NodeSnapshot> nodes, List<WireSnapshot> wires) {}

    @Unique
    private record NodeSnapshot(
            int originalId,
            NodeKind kind,
            double editorBodyWidth,
            double editorBodyHeight,
            int width,
            int laneWidth,
            String label,
            String chipName,
            long constantValue,
            long inputDefaultValue,
            boolean clockSource,
            long clockFrequencyHz,
            boolean randomSource,
            int randomChancePercent,
            List<BusSliceOutput> slices,
            boolean boardSocket,
            String interfaceId,
            PortDirection socketDirection,
            int interfaceOrder,
            boolean hasInputState,
            long inputState,
            double relativeX,
            double relativeY
    ) {
        static NodeSnapshot from(EditorNode node, double originX, double originY, boolean hasInputState, long inputState) {
            List<BusSliceOutput> sliceCopy = new ArrayList<>();
            if (node.slices != null) for (BusSliceOutput slice : node.slices) if (slice != null) sliceCopy.add(slice.copy());
            return new NodeSnapshot(
                    node.id, node.kind, node.editorBodyWidth, node.editorBodyHeight, node.width, node.laneWidth,
                    node.label == null ? "" : node.label,
                    node.chipName == null ? "" : node.chipName,
                    node.constantValue, node.inputDefaultValue,
                    node.clockSource, node.clockFrequencyHz,
                    node.randomSource, node.randomChancePercent,
                    List.copyOf(sliceCopy), node.boardSocket,
                    node.interfaceId == null ? "" : node.interfaceId,
                    node.socketDirection == null ? PortDirection.INPUT : node.socketDirection,
                    node.interfaceOrder,
                    hasInputState, inputState,
                    node.x - originX, node.y - originY
            );
        }

        void apply(EditorNode node) {
            node.editorBodyWidth = editorBodyWidth;
            node.editorBodyHeight = editorBodyHeight;
            node.width = width;
            node.laneWidth = laneWidth;
            node.label = label;
            node.chipName = chipName;
            node.constantValue = constantValue;
            node.inputDefaultValue = inputDefaultValue;
            node.clockSource = clockSource;
            node.clockFrequencyHz = clockFrequencyHz;
            node.randomSource = randomSource;
            node.randomChancePercent = randomChancePercent;
            node.boardSocket = boardSocket;
            node.interfaceId = interfaceId;
            node.socketDirection = socketDirection;
            node.interfaceOrder = interfaceOrder;
            node.templateInstanceId = 0;
            node.templateName = "";
            node.slices = new ArrayList<>();
            for (BusSliceOutput slice : slices) node.slices.add(slice.copy());
        }
    }

    @Unique
    private record WireSnapshot(
            int sourceNodeId,
            int sourcePort,
            int targetNodeId,
            int targetPort,
            List<RoutePoint> routePoints,
            RoutePoint branchStart,
            WireLayer layer,
            List<Integer> viaRouteIndices
    ) {}
}
