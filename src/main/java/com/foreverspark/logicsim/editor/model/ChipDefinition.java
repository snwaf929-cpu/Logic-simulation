package com.foreverspark.logicsim.editor.model;

import java.util.ArrayList;
import java.util.List;

public final class ChipDefinition {
    public int formatVersion = 3;
    public String name = "";
    public CircuitDocument circuit = new CircuitDocument();
    public ChipVisualSettings visual = new ChipVisualSettings();

    /**
     * Presentation metadata is duplicated into the chip file as a recovery source.
     * A value of 0 means "legacy/unspecified" and lets the library index supply the default.
     */
    public int color = 0;
    public String folder = "";

    public ChipDefinition() {
    }

    public ChipDefinition(String name, CircuitDocument circuit) {
        this(name, circuit, new ChipVisualSettings());
    }

    public ChipDefinition(String name, CircuitDocument circuit, ChipVisualSettings visual) {
        this.name = name;
        this.circuit = circuit;
        this.visual = visual == null ? new ChipVisualSettings() : visual;
        this.visual.normalize();
    }

    public void normalize() {
        if (name == null) name = "";
        if (circuit == null) circuit = new CircuitDocument();
        circuit.normalize();
        if (visual == null) visual = new ChipVisualSettings();
        visual.normalize();
        if (folder == null) folder = "";
        if (color != 0) color = 0xFF000000 | (color & 0x00FFFFFF);
        formatVersion = Math.max(formatVersion, 3);
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
