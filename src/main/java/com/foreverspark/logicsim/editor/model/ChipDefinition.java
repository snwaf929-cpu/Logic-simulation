package com.foreverspark.logicsim.editor.model;

import java.util.ArrayList;
import java.util.List;

public final class ChipDefinition {
    public int formatVersion = 1;
    public String name = "";
    public CircuitDocument circuit = new CircuitDocument();

    public ChipDefinition() {
    }

    public ChipDefinition(String name, CircuitDocument circuit) {
        this.name = name;
        this.circuit = circuit;
    }

    public List<PortSpec> inputPorts() {
        if (circuit == null) {
            return List.of();
        }
        List<PortSpec> ports = new ArrayList<>();
        int index = 0;
        for (EditorNode node : circuit.inputNodes()) {
            String portName = node.label == null || node.label.isBlank() ? "IN" + index : node.label;
            ports.add(new PortSpec(portName, PortDirection.INPUT, node.width));
            index++;
        }
        return List.copyOf(ports);
    }

    public List<PortSpec> outputPorts() {
        if (circuit == null) {
            return List.of();
        }
        List<PortSpec> ports = new ArrayList<>();
        int index = 0;
        for (EditorNode node : circuit.outputNodes()) {
            String portName = node.label == null || node.label.isBlank() ? "OUT" + index : node.label;
            ports.add(new PortSpec(portName, PortDirection.OUTPUT, node.width));
            index++;
        }
        return List.copyOf(ports);
    }
}
