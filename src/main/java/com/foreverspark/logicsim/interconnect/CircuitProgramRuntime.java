package com.foreverspark.logicsim.interconnect;

import com.foreverspark.logicsim.core.Signal;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.ExternalDeviceType;
import com.foreverspark.logicsim.editor.model.PortDirection;
import com.foreverspark.logicsim.editor.model.PortSpec;
import com.foreverspark.logicsim.editor.runtime.CircuitTimingController;
import com.foreverspark.logicsim.editor.runtime.CompiledCircuit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class CircuitProgramRuntime {
    private static final int LOSSLESS_STREAM_WIDTH = 64;
    /** DATA64 bits 0..55 carry opcode/Y/X/RGB. Bits 56..63 are sequence metadata and may be coalesced. */
    private static final int LOSSLESS_STREAM_SEMANTIC_BITS = 56;
    /** All physical-device sink pins share one callback-watch bit. Output #63 may share the same bit safely. */
    private static final int DEVICE_DIRTY_WATCH_BIT = 63;
    private static final long DEVICE_DIRTY_WATCH_MASK = 1L << DEVICE_DIRTY_WATCH_BIT;

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
    private final DeviceBinding[] externalDevices;
    private final CircuitTimingController.DirectRandomBoundaryPlan[] directRandomBoundaryPlans;
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
        this.externalDevices = indexExternalDevices();

        if (outputRuntimePorts.length <= 64) {
            dirtyOutputTracking = true;
            for (int index = 0; index < outputRuntimePorts.length; index++) {
                compiled.simulator().watchDirtyBit(index, outputRuntimePorts[index].valueSignalIds());
            }
            if (externalDevices.length > 0) {
                for (DeviceBinding device : externalDevices) {
                    for (DeviceInputPort port : device.inputs()) {
                        compiled.simulator().watchDirtyBit(DEVICE_DIRTY_WATCH_BIT, port.valueSignalIds());
                    }
                }
            }
            long outputMask = outputRuntimePorts.length == 64
                    ? -1L
                    : (outputRuntimePorts.length == 0 ? 0L : (1L << outputRuntimePorts.length) - 1L);
            forcedDirtyOutputMask = externalDevices.length > 0
                    ? outputMask | DEVICE_DIRTY_WATCH_MASK
                    : outputMask;
        } else {
            // >64 root outputs already require an unfiltered callback. Do not enable a device-only dirty watch here,
            // otherwise ordinary output changes could be suppressed by CircuitTimingController's callback guard.
            dirtyOutputTracking = false;
            forcedDirtyOutputMask = -1L;
        }

        initializeBoundaryInputDefaults();
        this.timing = new CircuitTimingController(compiled, program.root.circuit, program);
        // Ordinary physical outputs are sampled/coalesced to their newest level. A DATA64 display stream is different:
        // opcode/Y/X/RGB transitions contribute framebuffer intent and therefore must retain exact history. The top
        // sequence byte is transport metadata only; framebuffer coalescing and the once-per-tick cable snapshot do not
        // need each intermediate sequence transition, so a clock living only in bits 56..63 must not disable batching.
        this.timing.configureLosslessBoundarySignals(losslessBoundarySignalIds());
        this.compiled.simulator().enableTurboMode();

        // Compile a structural direct-RANDOM plan for every boundary once. Most ports produce null. The important
        // stress-test shape (one CLOCK -> <=64 RANDOM sources -> one 64-bit display bus, no NANDs) gets a packed plan
        // that can synthesize boundary words without touching every simulator signal on every virtual cycle.
        this.directRandomBoundaryPlans = new CircuitTimingController.DirectRandomBoundaryPlan[outputRuntimePorts.length];
        for (int index = 0; index < outputRuntimePorts.length; index++) {
            if (outputRuntimePorts[index].spec().width() > 64) continue;
            directRandomBoundaryPlans[index] = timing.compileDirectRandomBoundaryPlan(outputRuntimePorts[index].valueSignalIds());
        }
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

    public boolean directRandomBoundaryBatchEligible(int outputIndex) {
        return outputIndex >= 0
                && outputIndex < directRandomBoundaryPlans.length
                && directRandomBoundaryPlans[outputIndex] != null;
    }

    public int directRandomBoundaryRandomLanes(int outputIndex) {
        if (!directRandomBoundaryBatchEligible(outputIndex)) return 0;
        return directRandomBoundaryPlans[outputIndex].randomLaneCount();
    }

    /**
     * Packed direct-RANDOM boundary execution. Returns -1 if the cached structural plan became stale so callers can
     * immediately fall back to the ordinary edge engine without losing queued virtual time.
     */
    public long advanceDirectRandomBoundaryNanos(
            long elapsedNanos,
            long edgeBudget,
            int outputIndex,
            CircuitTimingController.LongBatchConsumer sink
    ) {
        if (!directRandomBoundaryBatchEligible(outputIndex)) return -1L;
        return timing.advanceDirectRandomBoundaryNanos(
                directRandomBoundaryPlans[outputIndex],
                elapsedNanos,
                edgeBudget,
                sink
        );
    }

    /**
     * Realtime DATA64 specialization. RANDOM still advances for every rising edge, while provably irrelevant display
     * commands are rejected before boundary scatter/scratch/framebuffer work.
     */
    public long advanceDirectRandomDisplayBoundaryNanos(
            long elapsedNanos,
            long edgeBudget,
            int outputIndex,
            int displayWidth,
            int displayHeight,
            CircuitTimingController.LongBatchConsumer sink
    ) {
        if (!directRandomBoundaryBatchEligible(outputIndex)) return -1L;
        return timing.advanceDirectRandomDisplayBoundaryNanos(
                directRandomBoundaryPlans[outputIndex],
                elapsedNanos,
                edgeBudget,
                displayWidth,
                displayHeight,
                sink
        );
    }

    public void setClocksRunning(boolean running) {
        for (CircuitTimingController.ClockAddress address : timing.clocks()) timing.setRunning(address.scopePath(), address.nodeId(), running);
    }

    public List<PortSpec> inputPorts() { return inputPortSpecs; }
    public List<PortSpec> outputPorts() { return outputPortSpecs; }
    public int outputPortCount() { return outputRuntimePorts.length; }
    public PortSpec outputPort(int index) { return outputRuntimePorts[index].spec(); }

    public int externalDeviceCount() { return externalDevices.length; }
    public String externalDeviceId(int index) { return externalDevices[index].deviceId(); }
    public ExternalDeviceType externalDeviceType(int index) { return externalDevices[index].type(); }
    public int externalDeviceInputCount(int index) { return externalDevices[index].inputs().length; }
    public PortSpec externalDeviceInputPort(int deviceIndex, int portIndex) {
        return externalDevices[deviceIndex].inputs()[portIndex].spec();
    }

    /** Fast physical-device sink read; stable signal-id arrays are compiled once with the BOARD. */
    public long externalDeviceInputValue(int deviceIndex, int portIndex) {
        return compiled.simulator().readUnsignedFast(externalDevices[deviceIndex].inputs()[portIndex].valueSignalIds());
    }

    /** Dirty mask returned by consumeDirtyOutputMask() also carries the shared physical-device sink bit. */
    public boolean externalDeviceInputsDirty(long dirtyMask) {
        if (externalDevices.length == 0) return false;
        return !dirtyOutputTracking || (dirtyMask & DEVICE_DIRTY_WATCH_MASK) != 0L;
    }

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

    private DeviceBinding[] indexExternalDevices() {
        ArrayList<DeviceBinding> bindings = new ArrayList<>();
        for (EditorNode node : program.root.circuit.externalDeviceNodes()) {
            if (node == null || node.externalDeviceType == null) continue;
            String id = node.externalDeviceId == null ? "" : node.externalDeviceId.trim();
            if (id.isEmpty()) continue;

            List<PortSpec> specs = node.externalDeviceType.inputs();
            DeviceInputPort[] ports = new DeviceInputPort[specs.size()];
            for (int portIndex = 0; portIndex < specs.size(); portIndex++) {
                Signal[] signals = compiled.inputSignals(CompiledCircuit.ROOT_SCOPE, node.id, portIndex);
                if (signals == null) {
                    throw new IllegalStateException("Compiled physical DEVICE input is unavailable: "
                            + node.externalDeviceType.name() + "/" + specs.get(portIndex).name());
                }
                ports[portIndex] = new DeviceInputPort(
                        specs.get(portIndex),
                        compiled.simulator().signalIds(signals)
                );
            }
            bindings.add(new DeviceBinding(id, node.externalDeviceType, ports));
        }
        return bindings.toArray(DeviceBinding[]::new);
    }

    private void initializeBoundaryInputDefaults() {
        for (EditorNode node : program.root.circuit.inputNodes()) {
            compiled.driveInputUnsigned(node.id, mask(node.inputDefaultValue, node.width));
        }
    }

    private Set<Integer> losslessBoundarySignalIds() {
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        for (BoundaryPort port : outputRuntimePorts) {
            if (port.spec().width() != LOSSLESS_STREAM_WIDTH) continue;
            int[] signalIds = port.valueSignalIds();
            int semanticBits = Math.min(LOSSLESS_STREAM_SEMANTIC_BITS, signalIds.length);
            for (int bit = 0; bit < semanticBits; bit++) ids.add(signalIds[bit]);
        }
        return Set.copyOf(ids);
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
    private record DeviceInputPort(PortSpec spec, int[] valueSignalIds) {}
    private record DeviceBinding(String deviceId, ExternalDeviceType type, DeviceInputPort[] inputs) {}
}
