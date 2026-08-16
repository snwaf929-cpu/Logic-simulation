package com.foreverspark.logicsim.editor.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Canonical signature for fields that can change compiled/electrical behavior or physical-device binding.
 * Pure CAD presentation (node X/Y, locks, route points, PCB layers/vias) is deliberately excluded so moving a
 * schematic around or rerouting copper does not restart a running Circuit Block.
 */
public final class CircuitHardwareSignature {
    private CircuitHardwareSignature() {}

    public static String of(CircuitDocument board) {
        if (board == null) return "";
        board.normalize();
        StringBuilder out = new StringBuilder(Math.max(96, board.nodes.size() * 96 + board.wires.size() * 24));
        out.append("SW").append(board.simulationWorkers).append(';');

        List<EditorNode> nodes = new ArrayList<>(board.nodes);
        nodes.sort(Comparator.comparingInt(node -> node.id));
        for (EditorNode node : nodes) {
            out.append('N').append(node.id).append('|').append(node.kind).append('|')
                    .append(node.width).append('|').append(node.laneWidth).append('|')
                    .append(node.chipPortOrder).append('|');
            appendString(out, node.label);
            appendString(out, node.chipName);
            out.append(node.constantValue).append('|')
                    .append(node.inputDefaultValue).append('|')
                    .append(node.clockSource).append('|').append(node.clockFrequencyHz).append('|')
                    .append(node.randomSource).append('|').append(node.randomChancePercent).append('|');

            if (node.kind == NodeKind.BUS_SLICE) {
                List<BusSliceOutput> slices = node.normalizedSlices();
                out.append("SL").append(slices.size()).append('|');
                for (BusSliceOutput slice : slices) {
                    appendString(out, slice.name);
                    out.append(slice.startBit).append(':').append(slice.width).append('|');
                }
            }

            out.append("BS").append(node.boardSocket).append('|');
            if (node.boardSocket) {
                appendString(out, node.interfaceId);
                out.append(node.socketDirection).append('|').append(node.interfaceOrder).append('|');
            }

            if (node.kind == NodeKind.EXTERNAL_DEVICE) {
                out.append("DEV").append(node.externalDeviceType).append('|');
                appendString(out, node.externalDeviceId);
                appendString(out, node.externalDeviceWorld);
                out.append(node.externalDeviceX).append(',').append(node.externalDeviceY).append(',').append(node.externalDeviceZ).append('|');
            }
            out.append(';');
        }

        List<WireConnection> wires = new ArrayList<>(board.wires);
        wires.sort(Comparator
                .comparingInt(WireConnection::sourceNodeId)
                .thenComparingInt(WireConnection::sourcePort)
                .thenComparingInt(WireConnection::targetNodeId)
                .thenComparingInt(WireConnection::targetPort));
        for (WireConnection wire : wires) {
            out.append('W').append(wire.sourceNodeId()).append(':').append(wire.sourcePort())
                    .append('>').append(wire.targetNodeId()).append(':').append(wire.targetPort()).append(';');
        }
        return out.toString();
    }

    private static void appendString(StringBuilder out, String value) {
        String safe = value == null ? "" : value;
        out.append(safe.length()).append(':').append(safe).append('|');
    }
}
