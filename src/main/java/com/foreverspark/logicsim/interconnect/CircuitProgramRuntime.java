package com.foreverspark.logicsim.interconnect;

import com.foreverspark.logicsim.core.Signal;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.PortDirection;
import com.foreverspark.logicsim.editor.model.PortSpec;
import com.foreverspark.logicsim.editor.runtime.CircuitTimingController;
import com.foreverspark.logicsim.editor.runtime.CompiledCircuit;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CircuitProgramRuntime {
    private final CircuitProgram program;
    private final CompiledCircuit compiled;
    private final CircuitTimingController timing;
    private final Map<String, BoundaryPort> inputs = new LinkedHashMap<>();
    private final Map<String, BoundaryPort> outputs = new LinkedHashMap<>();
    private final Map<String, BoundaryPort> exactInputs = new HashMap<>();
    private final Map<String, BoundaryPort> exactOutputs = new HashMap<>();
    private final List<PortSpec> inputPortSpecs;
    private final List<PortSpec> outputPortSpecs;
    private final BoundaryPort[] inputRuntimePorts;
    private final BoundaryPort[] outputRuntimePorts;
    private final boolean dirtyOutputTracking;
    private long forcedDirtyOutputMask;

    public CircuitProgramRuntime(CircuitProgram program) {
        if (program == null) throw new IllegalArgumentException("Circuit program is required");
        program.normalize();
        this.program = program;
        this.compiled = program.compile();
        indexBoundary();

        this.inputRuntimePorts = inputs.values().toArray(BoundaryPort[]::new);
        this.outputRuntimePorts = outputs.values().toArray(BoundaryPort[]::new);
        this.inputPortSpecs = java.util.Arrays.stream(inputRuntimePorts).map(BoundaryPort::spec).toList();
        this.outputPortSpecs = java.util.Arrays.stream(outputRuntimePorts).map(BoundaryPort::spec).toList();

        if (outputRuntimePorts.length <= 64) {
            dirtyOutputTracking = true;
            for (int index = 0; index < outputRuntimePorts.length; index++) {
                compiled.simulator().watchDirtyBit(index, outputRuntimePorts[index].valueSignalIds());
            }
            forcedDirtyOutputMask = outputRuntimePorts.length == 64
                    ? -1L
                    : (outputRuntimePorts.length == 0 ? 0L : (1L << outputRuntimePorts.length) - 1L);
        } else {
            dirtyOutputTracking = false;
            forcedDirtyOutputMask = -1L;
        }

        initializeBoundaryInputDefaults();
        this.timing = new CircuitTimingController(compiled, program.root.circuit, program);
        this.compiled.simulator().enableTurboMode();
    }

    public CircuitProgram program() { return program; }
    public CompiledCircuit compiled() { return compiled; }
    public CircuitTimingController timing() { return timing; }

    public long advanceClocksNanos(long elapsedNanos, long edgeBudgetPerClock) {
        return timing.advanceNanos(elapsedNanos, edgeBudgetPerClock);
    }

    public long advanceClocksNanos(long elapsedNanos, long edgeBudgetPerClock, Runnable afterSettledEdge) {
        return timing.advanceNanos(elapsedNanos, edgeBudgetPerClock, afterSettledEdge);
    }

    public void setClocksRunning(boolean running) {
        for (CircuitTimingController.ClockAddress address : timing.clocks()) timing.setRunning(address.scopePath(), address.nodeId(), running);
    }

    public List<PortSpec> inputPorts() { return inputPortSpecs; }
    public List<PortSpec> outputPorts() { return outputPortSpecs; }
    public int outputPortCount() { return outputRuntimePorts.length; }
    public PortSpec outputPort(int index) { return outputRuntimePorts[index].spec(); }

    public long consumeDirtyOutputMask() {
        if (!dirtyOutputTracking) return -1L;
        long result = forcedDirtyOutputMask | compiled.simulator().consumeDirtyWatchBits();
        forcedDirtyOutputMask = 0L;
        return result;
    }

    public PortSpec port(String name, PortDirection direction) {
        BoundaryPort port = table(direction).get(key(name));
        return port == null ? null : port.spec();
    }

    public void driveInput(String name, long value) {
        BoundaryPort port = directOrNormalized(exactInputs, inputs, name, "input");
        compiled.driveInputUnsigned(port.nodeId(), mask(value, port.spec().width()));
        timing.processRandomSources();
    }

    public long inputValue(String name) {
        BoundaryPort port = directOrNormalized(exactInputs, inputs, name, "input");
        return compiled.simulator().readUnsignedFast(port.valueSignalIds());
    }

    public long outputValue(String name) {
        BoundaryPort port = directOrNormalized(exactOutputs, outputs, name, "output");
        return compiled.simulator().readUnsignedFast(port.valueSignalIds());
    }

    /** Fastest physical-boundary read: no String/Map lookup or signal-id validation. */
    public long outputValue(int index) {
        return compiled.simulator().readUnsignedFast(outputRuntimePorts[index].valueSignalIds());
    }

    private void indexBoundary() {
        List<EditorNode> inputNodes = program.root.circuit.inputNodes();
        List<PortSpec> inputSpecs = program.root.inputPorts();
        for (int i = 0; i < inputNodes.size(); i++) {
            EditorNode node = inputNodes.get(i);
            Signal[] valueSignals = compiled.outputSignals(CompiledCircuit.ROOT_SCOPE, node.id, 0);
            putUnique(inputs, exactInputs, inputSpecs.get(i), node.id, valueSignals);
        }

        List<EditorNode> outputNodes = program.root.circuit.outputNodes();
        List<PortSpec> outputSpecs = program.root.outputPorts();
        for (int i = 0; i < outputNodes.size(); i++) {
            EditorNode node = outputNodes.get(i);
            Signal[] valueSignals = compiled.inputSignals(CompiledCircuit.ROOT_SCOPE, node.id, 0);
            putUnique(outputs, exactOutputs, outputSpecs.get(i), node.id, valueSignals);
        }
    }

    private void initializeBoundaryInputDefaults() {
        for (EditorNode node : program.root.circuit.inputNodes()) {
            compiled.driveInputUnsigned(node.id, mask(node.inputDefaultValue, node.width));
        }
    }

    private void putUnique(
            Map<String, BoundaryPort> normalizedTable,
            Map<String, BoundaryPort> exactTable,
            PortSpec spec,
            int nodeId,
            Signal[] valueSignals
    ) {
        if (valueSignals == null) throw new IllegalStateException("Compiled boundary port is unavailable: " + spec.name());
        String normalized = key(spec.name());
        int[] valueSignalIds = compiled.simulator().signalIds(valueSignals);
        BoundaryPort port = new BoundaryPort(spec, nodeId, valueSignalIds);
        if (normalizedTable.putIfAbsent(normalized, port) != null) {
            throw new IllegalArgumentException("Duplicate external port name: " + spec.name());
        }
        exactTable.put(spec.name(), port);
    }

    private Map<String, BoundaryPort> table(PortDirection direction) { return direction == PortDirection.INPUT ? inputs : outputs; }

    private static BoundaryPort directOrNormalized(
            Map<String, BoundaryPort> exact,
            Map<String, BoundaryPort> normalized,
            String name,
            String kind
    ) {
        BoundaryPort port = exact.get(name);
        if (port != null) return port;
        port = normalized.get(key(name));
        if (port == null) throw new IllegalArgumentException("Unknown circuit " + kind + " port: " + name);
        return port;
    }

    private static String key(String name) { return name == null ? "" : name.trim().toLowerCase(Locale.ROOT); }
    private static long mask(long value, int width) { return width >= 64 ? value : value & ((1L << width) - 1L); }
    private record BoundaryPort(PortSpec spec, int nodeId, int[] valueSignalIds) {}
}
