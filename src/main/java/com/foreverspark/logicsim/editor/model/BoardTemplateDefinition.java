package com.foreverspark.logicsim.editor.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reusable physical/layout BOARD module. A template never becomes a primitive gate: insertion clones
 * its real circuit nodes/wires into the destination board and records only grouping metadata.
 */
public final class BoardTemplateDefinition {
    public int formatVersion = 1;
    public String name = "";
    public CircuitDocument circuit = new CircuitDocument();

    public BoardTemplateDefinition() {}

    public BoardTemplateDefinition(String name, CircuitDocument circuit) {
        this.name = name == null ? "" : name.trim();
        this.circuit = circuit == null ? new CircuitDocument() : circuit;
        normalize();
    }

    public void normalize() {
        if (name == null) name = "";
        name = name.trim();
        if (circuit == null) circuit = new CircuitDocument();
        circuit.normalize();
        formatVersion = Math.max(1, formatVersion);
        validateInterfaces();
    }

    public List<BoardSocketSpec> sockets() {
        List<BoardSocketSpec> result = new ArrayList<>();
        for (EditorNode node : circuit.nodes) {
            if (!node.isBoardSocket()) continue;
            result.add(new BoardSocketSpec(
                    node.interfaceId,
                    node.label,
                    node.socketDirection,
                    node.width,
                    node.interfaceOrder,
                    node.id
            ));
        }
        result.sort(Comparator.comparingInt(BoardSocketSpec::order).thenComparingInt(BoardSocketSpec::nodeId));
        return List.copyOf(result);
    }

    /** Enforces deterministic, stable socket identity/order and valid template boundary topology. */
    public void validateInterfaces() {
        Set<String> ids = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        Set<Integer> nodeIds = new HashSet<>();
        for (EditorNode node : circuit.nodes) nodeIds.add(node.id);

        for (BoardSocketSpec socket : sockets()) {
            if (socket.interfaceId().isBlank()) {
                throw new IllegalArgumentException("BOARD template socket " + socket.nodeId() + " has no interface identity");
            }
            String identity = socket.interfaceId().toLowerCase(java.util.Locale.ROOT);
            if (!ids.add(identity)) {
                throw new IllegalArgumentException("Duplicate BOARD socket interface identity: " + socket.interfaceId());
            }
            if (!orders.add(socket.order())) {
                throw new IllegalArgumentException("Duplicate BOARD socket order: " + socket.order());
            }
            if (socket.name().isBlank()) {
                throw new IllegalArgumentException("BOARD template socket " + socket.interfaceId() + " has no name");
            }

            for (WireConnection wire : circuit.wires) {
                boolean incoming = wire.targetNodeId() == socket.nodeId();
                boolean outgoing = wire.sourceNodeId() == socket.nodeId();
                if (socket.direction() == PortDirection.INPUT && incoming && nodeIds.contains(wire.sourceNodeId())) {
                    throw new IllegalArgumentException("INPUT socket " + socket.name() + " must be driven from outside the template, not internally");
                }
                if (socket.direction() == PortDirection.OUTPUT && outgoing && nodeIds.contains(wire.targetNodeId())) {
                    throw new IllegalArgumentException("OUTPUT socket " + socket.name() + " must drive outside the template, not internally");
                }
            }
        }
    }
}
