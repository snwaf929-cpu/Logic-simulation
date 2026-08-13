package com.foreverspark.logicsim.interconnect;

import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.PortDirection;
import com.foreverspark.logicsim.editor.model.PortSpec;
import com.foreverspark.logicsim.editor.runtime.CompiledCircuit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Live runtime boundary used by a programmed world Circuit Block. */
public final class CircuitProgramRuntime {
    private final CircuitProgram program;
    private final CompiledCircuit compiled;
    private final Map<String, BoundaryPort> inputs = new LinkedHashMap<>();
    private final Map<String, BoundaryPort> outputs = new LinkedHashMap<>();

    public CircuitProgramRuntime(CircuitProgram program) {
        if (program == null) throw new IllegalArgumentException("Circuit program is required");
        program.normalize();
        this.program = program;
        this.compiled = program.compile();
        indexBoundary();
    }

    public CircuitProgram program() { return program; }
    public CompiledCircuit compiled() { return compiled; }

    public List<PortSpec> inputPorts() {
        return inputs.values().stream().map(BoundaryPort::spec).toList();
    }

    public List<PortSpec> outputPorts() {
        return outputs.values().stream().map(BoundaryPort::spec).toList();
    }

    public PortSpec port(String name, PortDirection direction) {
        BoundaryPort port = table(direction).get(key(name));
        return port == null ? null : port.spec();
    }

    public void driveInput(String name, long value) {
        BoundaryPort port = require(inputs, name, "input");
        compiled.driveInputUnsigned(port.nodeId(), mask(value, port.spec().width()));
    }

    public long inputValue(String name) {
        BoundaryPort port = require(inputs, name, "input");
        return compiled.outputUnsigned(port.nodeId(), 0);
    }

    public long outputValue(String name) {
        BoundaryPort port = require(outputs, name, "output");
        return compiled.inputUnsigned(port.nodeId(), 0);
    }

    private void indexBoundary() {
        List<EditorNode> inputNodes = program.root.circuit.inputNodes();
        List<PortSpec> inputSpecs = program.root.inputPorts();
        for (int i = 0; i < inputNodes.size(); i++) putUnique(inputs, inputSpecs.get(i), inputNodes.get(i).id);

        List<EditorNode> outputNodes = program.root.circuit.outputNodes();
        List<PortSpec> outputSpecs = program.root.outputPorts();
        for (int i = 0; i < outputNodes.size(); i++) putUnique(outputs, outputSpecs.get(i), outputNodes.get(i).id);
    }

    private void putUnique(Map<String, BoundaryPort> table, PortSpec spec, int nodeId) {
        String key = key(spec.name());
        if (table.putIfAbsent(key, new BoundaryPort(spec, nodeId)) != null) {
            throw new IllegalArgumentException("Duplicate external port name: " + spec.name());
        }
    }

    private Map<String, BoundaryPort> table(PortDirection direction) {
        return direction == PortDirection.INPUT ? inputs : outputs;
    }

    private static BoundaryPort require(Map<String, BoundaryPort> table, String name, String kind) {
        BoundaryPort port = table.get(key(name));
        if (port == null) throw new IllegalArgumentException("Unknown circuit " + kind + " port: " + name);
        return port;
    }

    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static long mask(long value, int width) {
        return width >= 64 ? value : value & ((1L << width) - 1L);
    }

    private record BoundaryPort(PortSpec spec, int nodeId) {}
}
