package com.foreverspark.logicsim.client.screen.v2;

import com.foreverspark.logicsim.editor.model.BusSliceOutput;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.RoutePoint;
import com.foreverspark.logicsim.editor.model.WireConnection;

import java.util.ArrayList;

/** Dependency-free deep copies used by editor undo/redo and regression tests. */
public final class EditorDocumentSnapshot {
    private EditorDocumentSnapshot() {}

    public static CircuitDocument copy(CircuitDocument source) {
        CircuitDocument result = new CircuitDocument();
        if (source == null) return result;
        result.formatVersion = source.formatVersion;
        result.nextNodeId = source.nextNodeId;
        result.nextTemplateInstanceId = source.nextTemplateInstanceId;
        result.simulationWorkers = source.simulationWorkers;
        result.nodes = new ArrayList<>(source.nodes.size());
        for (EditorNode node : source.nodes) result.nodes.add(copyNode(node));
        result.wires = new ArrayList<>(source.wires.size());
        for (WireConnection wire : source.wires) result.wires.add(copyWire(wire));
        result.normalize();
        return result;
    }

    public static boolean same(CircuitDocument a, CircuitDocument b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.formatVersion != b.formatVersion || a.nextNodeId != b.nextNodeId || a.nextTemplateInstanceId != b.nextTemplateInstanceId
                || a.simulationWorkers != b.simulationWorkers) return false;
        if (a.nodes.size() != b.nodes.size() || a.wires.size() != b.wires.size()) return false;
        for (int i = 0; i < a.nodes.size(); i++) if (!sameNode(a.nodes.get(i), b.nodes.get(i))) return false;
        for (int i = 0; i < a.wires.size(); i++) if (!a.wires.get(i).equals(b.wires.get(i))) return false;
        return true;
    }

    private static EditorNode copyNode(EditorNode source) {
        EditorNode node = new EditorNode();
        node.id = source.id;
        node.kind = source.kind;
        node.x = source.x;
        node.y = source.y;
        node.width = source.width;
        node.laneWidth = source.laneWidth;
        node.label = source.label == null ? "" : source.label;
        node.chipPortOrder = source.chipPortOrder;
        node.chipName = source.chipName == null ? "" : source.chipName;
        node.constantValue = source.constantValue;
        node.inputDefaultValue = source.inputDefaultValue;
        node.clockSource = source.clockSource;
        node.clockFrequencyHz = source.clockFrequencyHz;
        node.randomSource = source.randomSource;
        node.randomChancePercent = source.randomChancePercent;
        node.locked = source.locked;
        node.boardSocket = source.boardSocket;
        node.interfaceId = source.interfaceId == null ? "" : source.interfaceId;
        node.socketDirection = source.socketDirection;
        node.interfaceOrder = source.interfaceOrder;
        node.templateInstanceId = source.templateInstanceId;
        node.templateName = source.templateName == null ? "" : source.templateName;
        node.externalDeviceType = source.externalDeviceType;
        node.externalDeviceState = source.externalDeviceState;
        node.externalDeviceId = source.externalDeviceId == null ? "" : source.externalDeviceId;
        node.externalDeviceWorld = source.externalDeviceWorld == null ? "" : source.externalDeviceWorld;
        node.externalDeviceX = source.externalDeviceX;
        node.externalDeviceY = source.externalDeviceY;
        node.externalDeviceZ = source.externalDeviceZ;
        node.slices = new ArrayList<>();
        if (source.slices != null) for (BusSliceOutput slice : source.slices) if (slice != null) node.slices.add(slice.copy());
        return node;
    }

    private static WireConnection copyWire(WireConnection source) {
        WireConnection wire = new WireConnection(source.sourceNodeId(), source.sourcePort(), source.targetNodeId(), source.targetPort());
        ArrayList<RoutePoint> route = new ArrayList<>();
        for (RoutePoint point : source.routePoints()) route.add(new RoutePoint(point.x(), point.y()));
        wire.setRoutePoints(route);
        RoutePoint branch = source.branchStart();
        if (branch != null) wire.setBranchStart(new RoutePoint(branch.x(), branch.y()));
        wire.setLayer(source.layer());
        wire.setViaRouteIndices(source.viaRouteIndices());
        return wire;
    }

    private static boolean sameNode(EditorNode a, EditorNode b) {
        return a.id == b.id && a.kind == b.kind && Double.compare(a.x, b.x) == 0 && Double.compare(a.y, b.y) == 0
                && a.width == b.width && a.laneWidth == b.laneWidth && safe(a.label).equals(safe(b.label))
                && a.chipPortOrder == b.chipPortOrder
                && safe(a.chipName).equals(safe(b.chipName)) && a.constantValue == b.constantValue
                && a.inputDefaultValue == b.inputDefaultValue && a.clockSource == b.clockSource
                && a.clockFrequencyHz == b.clockFrequencyHz && a.randomSource == b.randomSource
                && a.randomChancePercent == b.randomChancePercent && a.locked == b.locked && a.boardSocket == b.boardSocket
                && safe(a.interfaceId).equals(safe(b.interfaceId)) && a.socketDirection == b.socketDirection
                && a.interfaceOrder == b.interfaceOrder && a.templateInstanceId == b.templateInstanceId
                && safe(a.templateName).equals(safe(b.templateName))
                && a.externalDeviceType == b.externalDeviceType && a.externalDeviceState == b.externalDeviceState
                && safe(a.externalDeviceId).equals(safe(b.externalDeviceId))
                && safe(a.externalDeviceWorld).equals(safe(b.externalDeviceWorld))
                && a.externalDeviceX == b.externalDeviceX && a.externalDeviceY == b.externalDeviceY && a.externalDeviceZ == b.externalDeviceZ
                && slicesEqual(a, b);
    }

    private static boolean slicesEqual(EditorNode a, EditorNode b) {
        var as = a.slices == null ? java.util.List.<BusSliceOutput>of() : a.slices;
        var bs = b.slices == null ? java.util.List.<BusSliceOutput>of() : b.slices;
        return as.equals(bs);
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
