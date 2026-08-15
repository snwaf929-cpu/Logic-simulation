package com.foreverspark.logicsim.editor.model;

import java.util.ArrayList;
import java.util.List;

public final class NodePorts {
    private NodePorts() {}

    public static List<PortSpec> inputs(EditorNode node, ChipLookup chips) {
        return switch (node.kind) {
            case INPUT -> List.of();
            case CONSTANT -> node.randomSource
                    ? List.of(new PortSpec("TRIGGER", PortDirection.INPUT, 1))
                    : node.clockSource
                    ? List.of(new PortSpec("ENABLE", PortDirection.INPUT, 1))
                    : List.of();
            case OUTPUT -> List.of(new PortSpec("IN", PortDirection.INPUT, node.width));
            case NAND -> List.of(new PortSpec("A", PortDirection.INPUT, 1), new PortSpec("B", PortDirection.INPUT, 1));
            case PROBE, BUS, SPLITTER, BUS_SLICE -> List.of(new PortSpec("BUS", PortDirection.INPUT, node.width));
            case MERGER -> lanePorts(node, PortDirection.INPUT);
            case NET_LABEL -> List.of(new PortSpec("NET", PortDirection.INPUT, node.width));
            case CUSTOM_CHIP -> requireChip(node, chips).inputPorts();
            case EXTERNAL_DEVICE -> deviceType(node).inputs();
        };
    }

    public static List<PortSpec> outputs(EditorNode node, ChipLookup chips) {
        return switch (node.kind) {
            case INPUT -> List.of(new PortSpec("OUT", PortDirection.OUTPUT, node.width));
            case OUTPUT, PROBE -> List.of();
            case NAND -> List.of(new PortSpec("OUT", PortDirection.OUTPUT, 1));
            case CONSTANT -> List.of(new PortSpec("OUT", PortDirection.OUTPUT, node.width));
            case BUS -> List.of(new PortSpec("BUS", PortDirection.OUTPUT, node.width));
            case SPLITTER -> lanePorts(node, PortDirection.OUTPUT);
            case MERGER -> List.of(new PortSpec("BUS", PortDirection.OUTPUT, node.width));
            case BUS_SLICE -> slicePorts(node);
            case NET_LABEL -> List.of(new PortSpec("NET", PortDirection.OUTPUT, node.width));
            case CUSTOM_CHIP -> requireChip(node, chips).outputPorts();
            case EXTERNAL_DEVICE -> deviceType(node).outputs();
        };
    }

    private static ExternalDeviceType deviceType(EditorNode node) {
        if (node.externalDeviceType == null) node.externalDeviceType = ExternalDeviceType.DISPLAY;
        return node.externalDeviceType;
    }

    private static List<PortSpec> lanePorts(EditorNode node, PortDirection direction) {
        int laneWidth = node.normalizedLaneWidth();
        int count = Math.max(1, node.width / laneWidth);
        List<PortSpec> ports = new ArrayList<>(count);
        for (int lane = 0; lane < count; lane++) {
            int low = lane * laneWidth;
            int high = low + laneWidth - 1;
            String name = laneWidth == 1 ? "B" + low : "B" + low + "-" + high;
            ports.add(new PortSpec(name, direction, laneWidth));
        }
        return List.copyOf(ports);
    }

    private static List<PortSpec> slicePorts(EditorNode node) {
        List<BusSliceOutput> slices = node.normalizedSlices();
        List<PortSpec> ports = new ArrayList<>(slices.size());
        for (int i = 0; i < slices.size(); i++) {
            BusSliceOutput slice = slices.get(i);
            ports.add(new PortSpec(slice.name, PortDirection.OUTPUT, slice.width));
        }
        return List.copyOf(ports);
    }

    private static ChipDefinition requireChip(EditorNode node, ChipLookup chips) {
        if (node.chipName == null || node.chipName.isBlank()) throw new IllegalArgumentException("Custom chip node has no chip name");
        ChipDefinition definition = chips.find(node.chipName);
        if (definition == null) throw new IllegalArgumentException("Unknown custom chip: " + node.chipName);
        return definition;
    }
}
