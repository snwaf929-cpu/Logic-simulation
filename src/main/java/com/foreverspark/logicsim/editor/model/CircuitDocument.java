package com.foreverspark.logicsim.editor.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class CircuitDocument {
    public int formatVersion = 2;
    public int nextNodeId = 1;
    public List<EditorNode> nodes = new ArrayList<>();
    public List<WireConnection> wires = new ArrayList<>();

    public CircuitDocument() {
    }

    public EditorNode addNode(NodeKind kind, double x, double y) {
        EditorNode node = new EditorNode(nextNodeId++, kind, x, y);
        nodes.add(node);
        return node;
    }

    public EditorNode addCustomChip(String chipName, double x, double y) {
        EditorNode node = addNode(NodeKind.CUSTOM_CHIP, x, y);
        node.chipName = chipName == null ? "" : chipName;
        return node;
    }

    public EditorNode node(int id) {
        return nodes.stream()
                .filter(node -> node.id == id)
                .findFirst()
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

    public void removeWire(WireConnection wire) {
        wires.remove(wire);
    }

    public void removeWiresForNode(int nodeId) {
        wires.removeIf(wire -> wire.sourceNodeId() == nodeId || wire.targetNodeId() == nodeId);
    }

    public int connectionCount(int nodeId) {
        int count = 0;
        for (WireConnection wire : wires) {
            if (wire.sourceNodeId() == nodeId || wire.targetNodeId() == nodeId) {
                count++;
            }
        }
        return count;
    }

    public List<EditorNode> inputNodes() {
        return nodes.stream()
                .filter(node -> node.kind == NodeKind.INPUT)
                .sorted(Comparator.comparingInt(node -> node.id))
                .toList();
    }

    public List<EditorNode> outputNodes() {
        return nodes.stream()
                .filter(node -> node.kind == NodeKind.OUTPUT)
                .sorted(Comparator.comparingInt(node -> node.id))
                .toList();
    }

    public void normalize() {
        if (nodes == null) {
            nodes = new ArrayList<>();
        }
        if (wires == null) {
            wires = new ArrayList<>();
        }
        wires.removeIf(wire -> wire == null);
        for (WireConnection wire : wires) {
            wire.normalize();
        }

        int maxId = nodes.stream().mapToInt(node -> node.id).max().orElse(0);
        nextNodeId = Math.max(nextNodeId, maxId + 1);
        for (EditorNode node : nodes) {
            if (node.kind == null) {
                node.kind = NodeKind.NAND;
            }
            if (node.width <= 0 || node.width > 64) {
                node.width = 1;
            }
            if (node.label == null) {
                node.label = "";
            }
            if (node.chipName == null) {
                node.chipName = "";
            }
            if (node.kind == NodeKind.INPUT && node.width < 64) {
                node.inputDefaultValue &= (1L << node.width) - 1L;
            }
        }
    }
}
