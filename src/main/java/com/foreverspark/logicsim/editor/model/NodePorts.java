package com.foreverspark.logicsim.editor.model;

import java.util.ArrayList;
import java.util.List;

public final class NodePorts {
    private NodePorts() {
    }

    public static List<PortSpec> inputs(EditorNode node, ChipLookup chips) {
        return switch (node.kind) {
            case INPUT, CONSTANT -> List.of();
            case OUTPUT -> List.of(new PortSpec("IN", PortDirection.INPUT, node.width));
            case NAND -> List.of(
                    new PortSpec("A", PortDirection.INPUT, 1),
                    new PortSpec("B", PortDirection.INPUT, 1)
            );
            case PROBE, BUS, SPLITTER -> List.of(new PortSpec("BUS", PortDirection.INPUT, node.width));
            case MERGER -> bitPorts("B", PortDirection.INPUT, node.width);
            case CUSTOM_CHIP -> requireChip(node, chips).inputPorts();
        };
    }

    public static List<PortSpec> outputs(EditorNode node, ChipLookup chips) {
        return switch (node.kind) {
            case INPUT -> List.of(new PortSpec("OUT", PortDirection.OUTPUT, node.width));
            case OUTPUT, PROBE -> List.of();
            case NAND -> List.of(new PortSpec("OUT", PortDirection.OUTPUT, 1));
            case CONSTANT -> List.of(new PortSpec("OUT", PortDirection.OUTPUT, node.width));
            case BUS -> List.of(new PortSpec("BUS", PortDirection.OUTPUT, node.width));
            case SPLITTER -> bitPorts("B", PortDirection.OUTPUT, node.width);
            case MERGER -> List.of(new PortSpec("BUS", PortDirection.OUTPUT, node.width));
            case CUSTOM_CHIP -> requireChip(node, chips).outputPorts();
        };
    }

    private static List<PortSpec> bitPorts(String prefix, PortDirection direction, int width) {
        List<PortSpec> ports = new ArrayList<>(width);
        for (int bit = 0; bit < width; bit++) {
            ports.add(new PortSpec(prefix + bit, direction, 1));
        }
        return List.copyOf(ports);
    }

    private static ChipDefinition requireChip(EditorNode node, ChipLookup chips) {
        if (node.chipName == null || node.chipName.isBlank()) {
            throw new IllegalArgumentException("Custom chip node has no chip name");
        }
        ChipDefinition definition = chips.find(node.chipName);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown custom chip: " + node.chipName);
        }
        return definition;
    }
}
