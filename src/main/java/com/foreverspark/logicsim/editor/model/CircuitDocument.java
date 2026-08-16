package com.foreverspark.logicsim.editor.model;

import com.foreverspark.logicsim.block.CircuitWorkerPolicy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class CircuitDocument {
    /** Optional Gson fields keep older BOARD/CHIP documents readable without a gated decoder. */
    public int formatVersion = 3;
    public int nextNodeId = 1;
    /** Stable monotonically increasing id for cloned BOARD template groups. */
    public int nextTemplateInstanceId = 1;
    /**
     * Per-physical-BOARD simulation parallelism request. 0=AUTO, 1=single-worker, N=at most N workers.
     * The runtime always clamps this against CircuitWorkerPolicy.systemMaximum() on the host machine.
     */
    public int simulationWorkers = CircuitWorkerPolicy.DEFAULT;
    public List<EditorNode> nodes = new ArrayList<>();
    public List<WireConnection> wires = new ArrayList<>();

    public CircuitDocument() {}

    public EditorNode addNode(NodeKind kind, double x, double y) {
        EditorNode node = new EditorNode(nextNodeId++, kind, x, y);
        if (kind == NodeKind.INPUT || kind == NodeKind.OUTPUT) node.chipPortOrder = nextChipPortOrder(kind);
        nodes.add(node);
        return node;
    }

    public EditorNode addCustomChip(String chipName, double x, double y) {
        EditorNode node = addNode(NodeKind.CUSTOM_CHIP, x, y);
        node.chipName = chipName == null ? "" : chipName;
        return node;
    }

    public EditorNode node(int id) {
        return nodes.stream().filter(node -> node.id == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown node " + id));
    }

    public void connect(int sourceNodeId, int sourcePort, int targetNodeId, int targetPort) {
        wires.removeIf(wire -> wire.targetNodeId() == targetNodeId && wire.targetPort() == targetPort);
        wires.add(new WireConnection(sourceNodeId, sourcePort, targetNodeId, targetPort));
    }

    public void removeNode(int nodeId) {
        nodes.removeIf(node -> node.id == nodeId);
        wires.removeIf(wire -> wire.sourceNodeId() == nodeId || wire.targetNodeId() == nodeId);
    }

    public void removeWire(WireConnection wire) { wires.remove(wire); }
    public void removeWiresForNode(int nodeId) { wires.removeIf(wire -> wire.sourceNodeId() == nodeId || wire.targetNodeId() == nodeId); }

    public int connectionCount(int nodeId) {
        int count = 0;
        for (WireConnection wire : wires) if (wire.sourceNodeId() == nodeId || wire.targetNodeId() == nodeId) count++;
        return count;
    }

    /** Stable public custom-CHIP input order, independent of node creation order after migration. */
    public List<EditorNode> inputNodes() {
        return nodes.stream().filter(node -> node.kind == NodeKind.INPUT)
                .sorted(Comparator.comparingInt((EditorNode node) -> node.chipPortOrder).thenComparingInt(node -> node.id))
                .toList();
    }

    /** Stable public custom-CHIP output order, independent of node creation order after migration. */
    public List<EditorNode> outputNodes() {
        return nodes.stream().filter(node -> node.kind == NodeKind.OUTPUT)
                .sorted(Comparator.comparingInt((EditorNode node) -> node.chipPortOrder).thenComparingInt(node -> node.id))
                .toList();
    }

    public List<EditorNode> externalDeviceNodes() {
        return nodes.stream().filter(EditorNode::isExternalDevice).sorted(Comparator.comparingInt(node -> node.id)).toList();
    }

    public void normalize() {
        simulationWorkers = CircuitWorkerPolicy.normalizePersisted(simulationWorkers);
        if (nodes == null) nodes = new ArrayList<>();
        nodes.removeIf(node -> node == null);
        if (wires == null) wires = new ArrayList<>();
        wires.removeIf(wire -> wire == null);
        for (WireConnection wire : wires) wire.normalize();

        int maxId = nodes.stream().mapToInt(node -> node.id).max().orElse(0);
        int maxTemplateInstanceId = nodes.stream().mapToInt(node -> Math.max(0, node.templateInstanceId)).max().orElse(0);
        nextNodeId = Math.max(nextNodeId, maxId + 1);
        nextTemplateInstanceId = Math.max(nextTemplateInstanceId, maxTemplateInstanceId + 1);

        for (EditorNode node : nodes) {
            if (node.kind == null) node.kind = NodeKind.NAND;
            node.width = Math.max(1, Math.min(64, node.width));
            if (node.label == null) node.label = "";
            if (node.chipName == null) node.chipName = "";
            if (node.interfaceId == null) node.interfaceId = "";
            if (node.socketDirection == null) node.socketDirection = PortDirection.INPUT;
            node.interfaceOrder = Math.max(0, node.interfaceOrder);
            node.templateInstanceId = Math.max(0, node.templateInstanceId);
            if (node.templateName == null) node.templateName = "";
            node.templateName = node.templateName.trim();
            if (node.templateInstanceId == 0) node.templateName = "";

            // Migrate the old pseudo-chip SCREEN node into a real persistent physical endpoint placeholder.
            if (node.kind == NodeKind.CUSTOM_CHIP && "DISPLAY".equalsIgnoreCase(node.chipName.trim())) {
                node.configureExternalDevice(ExternalDeviceType.DISPLAY, "", ExternalDeviceState.UNKNOWN, "", 0, 0, 0);
            }

            if (node.boardSocket) {
                node.kind = NodeKind.BUS;
                node.label = node.label.isBlank() ? "SOCKET" + node.id : node.label.trim();
                node.interfaceId = node.interfaceId.isBlank() ? "socket-" + node.id : node.interfaceId.trim();
            } else {
                node.interfaceId = "";
                node.interfaceOrder = 0;
                node.socketDirection = PortDirection.INPUT;
            }

            if (node.kind == NodeKind.EXTERNAL_DEVICE) {
                node.boardSocket = false;
                if (node.externalDeviceType == null) node.externalDeviceType = ExternalDeviceType.DISPLAY;
                if (node.externalDeviceState == null) node.externalDeviceState = ExternalDeviceState.UNKNOWN;
                if (node.externalDeviceId == null) node.externalDeviceId = "";
                if (node.externalDeviceWorld == null) node.externalDeviceWorld = "";
                node.externalDeviceId = node.externalDeviceId.trim();
                node.externalDeviceWorld = node.externalDeviceWorld.trim();
                node.label = node.externalDeviceType.label();
                node.chipName = "";
            }

            if (node.kind == NodeKind.SPLITTER || node.kind == NodeKind.MERGER) {
                int lane = node.laneWidth;
                if (lane <= 0 || lane > node.width || node.width % lane != 0) node.laneWidth = 1;
            } else {
                node.laneWidth = 1;
            }

            if (node.kind == NodeKind.BUS_SLICE) node.normalizedSlices();
            else if (node.slices == null) node.slices = new ArrayList<>();

            if (node.kind == NodeKind.NET_LABEL) node.label = node.label == null || node.label.isBlank() ? "NET" + node.id : node.label.trim();

            if (node.kind == NodeKind.INPUT && node.width < 64) node.inputDefaultValue &= (1L << node.width) - 1L;
            if (node.kind == NodeKind.CONSTANT && node.width < 64) node.constantValue &= (1L << node.width) - 1L;
            if (node.kind == NodeKind.CONSTANT && node.randomSource) {
                node.clockSource = false;
                node.width = 1;
                node.constantValue = 0L;
                node.randomChancePercent = Math.max(0, Math.min(100, node.randomChancePercent));
            } else if (node.kind == NodeKind.CONSTANT && node.clockSource) {
                node.randomSource = false;
                node.width = 1;
                node.constantValue = 0L;
                node.clockFrequencyHz = Math.max(1L, Math.min(500_000_000L, node.clockFrequencyHz));
            }

            if (node.kind != NodeKind.INPUT && node.kind != NodeKind.OUTPUT) node.chipPortOrder = -1;
        }

        normalizeChipPortOrder(NodeKind.INPUT);
        normalizeChipPortOrder(NodeKind.OUTPUT);
        formatVersion = Math.max(formatVersion, 3);
    }

    /**
     * Legacy files had no explicit order. Unassigned ports sort after authored orders by id, then the full list is
     * compacted to 0..N-1 so future edits are stable and duplicate order values cannot corrupt a CHIP interface.
     */
    private void normalizeChipPortOrder(NodeKind kind) {
        List<EditorNode> ordered = nodes.stream()
                .filter(node -> node.kind == kind)
                .sorted(Comparator
                        .comparingInt((EditorNode node) -> node.chipPortOrder < 0 ? Integer.MAX_VALUE : node.chipPortOrder)
                        .thenComparingInt(node -> node.id))
                .toList();
        for (int index = 0; index < ordered.size(); index++) ordered.get(index).chipPortOrder = index;
    }

    /** New terminals receive a durable order immediately; only legacy deserialization uses -1 migration. */
    private int nextChipPortOrder(NodeKind kind) {
        int max = -1;
        for (EditorNode node : nodes) {
            if (node.kind == kind && node.chipPortOrder >= 0) max = Math.max(max, node.chipPortOrder);
        }
        return max + 1;
    }
}
