package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.RoutePoint;
import com.foreverspark.logicsim.editor.model.WireConnection;
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

/** Deep-copy support for every persisted/configuration field on editor nodes. */
@Mixin(value = CircuitCanvasWidget.class, priority = 1600)
public abstract class CircuitCanvasDuplicateMixin {
    @Unique private static final double LOGIC_COPY_OFFSET = 24.0;
    @Unique private static final double LOGIC_NODE_GRID = 6.0;

    @Shadow private CircuitDocument document;
    @Shadow @Final private LinkedHashSet<Integer> selectedNodeIds;
    @Shadow private Integer selectedNodeId;
    @Shadow private WireConnection selectedWire;
    @Shadow private boolean wireEditMode;
    @Shadow @Final private Consumer<String> status;
    @Shadow @Final private Map<Integer, Long> inputStates;
    @Shadow private void setSelectedNodes(Iterable<Integer> nodeIds) { throw new AssertionError(); }
    @Shadow private void recompile() { throw new AssertionError(); }

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
        logic$pasteSerial++;
        logic$pasteSelection(logic$clipboard, LOGIC_COPY_OFFSET * logic$pasteSerial, LOGIC_COPY_OFFSET * logic$pasteSerial);
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
        LogicClipboard copy = logic$captureSelection();
        logic$pasteSelection(copy, LOGIC_COPY_OFFSET, LOGIC_COPY_OFFSET);
        status.accept("Duplicated " + selectedNodeIds.size() + " node" + (selectedNodeIds.size() == 1 ? "" : "s"));
        cir.setReturnValue(true);
    }

    @Unique
    private LogicClipboard logic$captureSelection() {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        for (EditorNode node : document.nodes) {
            if (!selectedNodeIds.contains(node.id)) continue;
            minX = Math.min(minX, node.x);
            minY = Math.min(minY, node.y);
        }
        if (!Double.isFinite(minX)) minX = 0.0;
        if (!Double.isFinite(minY)) minY = 0.0;

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
            for (RoutePoint point : wire.routePoints()) {
                route.add(new RoutePoint(point.x() - minX, point.y() - minY));
            }
            wires.add(new WireSnapshot(
                    wire.sourceNodeId(), wire.sourcePort(), wire.targetNodeId(), wire.targetPort(), List.copyOf(route)
            ));
        }
        return new LogicClipboard(minX, minY, List.copyOf(nodes), List.copyOf(wires));
    }

    @Unique
    private void logic$pasteSelection(LogicClipboard copy, double offsetX, double offsetY) {
        Map<Integer, Integer> ids = new LinkedHashMap<>();
        LinkedHashSet<Integer> pastedIds = new LinkedHashSet<>();
        double originX = copy.originX() + offsetX;
        double originY = copy.originY() + offsetY;

        for (NodeSnapshot snapshot : copy.nodes()) {
            EditorNode node = document.addNode(
                    snapshot.kind(),
                    logic$snap(originX + snapshot.relativeX()),
                    logic$snap(originY + snapshot.relativeY())
            );
            snapshot.apply(node);
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
                pastedWire.routePoints().add(new RoutePoint(originX + point.x(), originY + point.y()));
            }
        }

        setSelectedNodes(pastedIds);
        selectedWire = null;
        wireEditMode = false;
        recompile();
    }

    @Unique private static double logic$snap(double value) {
        return Math.round(value / LOGIC_NODE_GRID) * LOGIC_NODE_GRID;
    }

    @Unique
    private record LogicClipboard(double originX, double originY, List<NodeSnapshot> nodes, List<WireSnapshot> wires) {}

    @Unique
    private record NodeSnapshot(
            int originalId,
            NodeKind kind,
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
            boolean hasInputState,
            long inputState,
            double relativeX,
            double relativeY
    ) {
        static NodeSnapshot from(EditorNode node, double originX, double originY, boolean hasInputState, long inputState) {
            return new NodeSnapshot(
                    node.id,
                    node.kind,
                    node.width,
                    node.laneWidth,
                    node.label == null ? "" : node.label,
                    node.chipName == null ? "" : node.chipName,
                    node.constantValue,
                    node.inputDefaultValue,
                    node.clockSource,
                    node.clockFrequencyHz,
                    node.randomSource,
                    node.randomChancePercent,
                    hasInputState,
                    inputState,
                    node.x - originX,
                    node.y - originY
            );
        }

        void apply(EditorNode node) {
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
        }
    }

    @Unique
    private record WireSnapshot(
            int sourceNodeId,
            int sourcePort,
            int targetNodeId,
            int targetPort,
            List<RoutePoint> routePoints
    ) {}
}
