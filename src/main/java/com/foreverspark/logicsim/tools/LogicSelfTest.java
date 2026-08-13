package com.foreverspark.logicsim.tools;

import com.foreverspark.logicsim.core.Bus;
import com.foreverspark.logicsim.core.CircuitSimulator;
import com.foreverspark.logicsim.core.LogicCircuit;
import com.foreverspark.logicsim.core.LogicValue;
import com.foreverspark.logicsim.core.Signal;
import com.foreverspark.logicsim.core.TraceRecorder;
import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.ChipVisualSettings;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.PortDirection;
import com.foreverspark.logicsim.editor.model.PortSpec;
import com.foreverspark.logicsim.editor.model.RoutePoint;
import com.foreverspark.logicsim.editor.model.WireConnection;
import com.foreverspark.logicsim.editor.runtime.CircuitCompileException;
import com.foreverspark.logicsim.editor.runtime.CircuitCompiler;
import com.foreverspark.logicsim.editor.runtime.CompiledCircuit;
import com.foreverspark.logicsim.interconnect.CableConnection;
import com.foreverspark.logicsim.interconnect.CableKind;
import com.foreverspark.logicsim.interconnect.DevicePortAddress;
import com.foreverspark.logicsim.interconnect.InterconnectDevice;
import com.foreverspark.logicsim.interconnect.InterconnectGraph;

import java.util.List;
import java.util.Map;

public final class LogicSelfTest {
    private LogicSelfTest() {}

    public static void main(String[] args) {
        testNandTruthTable();
        testNotFromNand();
        testAndFromNandOnly();
        testBusSplitMerge();
        testTraceRingBuffer();
        testFreeformNotCompiler();
        testFreeformBusSplitMerge();
        testCustomChipFlattening();
        testLiveHierarchicalScope();
        testWidthMismatchRejected();
        testRoutedWireDoesNotChangeLogic();
        testChipVisualSettingsBounds();
        testInterconnectValidation();
        System.out.println("Logic core + live hierarchy + routed UX metadata + interconnect self-test: PASS");
    }

    private static void testNandTruthTable() {
        check(LogicValue.nand(LogicValue.LOW, LogicValue.LOW) == LogicValue.HIGH, "NAND 0,0");
        check(LogicValue.nand(LogicValue.LOW, LogicValue.HIGH) == LogicValue.HIGH, "NAND 0,1");
        check(LogicValue.nand(LogicValue.HIGH, LogicValue.LOW) == LogicValue.HIGH, "NAND 1,0");
        check(LogicValue.nand(LogicValue.HIGH, LogicValue.HIGH) == LogicValue.LOW, "NAND 1,1");
        check(LogicValue.nand(LogicValue.UNKNOWN, LogicValue.HIGH) == LogicValue.UNKNOWN, "NAND X,1");
        check(LogicValue.nand(LogicValue.UNKNOWN, LogicValue.LOW) == LogicValue.HIGH, "NAND X,0");
    }

    private static void testNotFromNand() {
        LogicCircuit circuit = new LogicCircuit();
        Signal input = circuit.signal("NOT/A");
        Signal output = circuit.signal("NOT/OUT");
        circuit.nand("NOT/NAND0", input, input, output);

        CircuitSimulator simulator = new CircuitSimulator(circuit);
        simulator.scheduleAll();
        simulator.runUntilStable(100);

        simulator.drive(input, LogicValue.LOW);
        simulator.runUntilStable(100);
        check(output.value() == LogicValue.HIGH, "NOT 0");

        simulator.drive(input, LogicValue.HIGH);
        simulator.runUntilStable(100);
        check(output.value() == LogicValue.LOW, "NOT 1");
    }

    private static void testAndFromNandOnly() {
        LogicCircuit circuit = new LogicCircuit();
        Signal a = circuit.signal("AND/A");
        Signal b = circuit.signal("AND/B");
        Signal nandOut = circuit.signal("AND/NAND_OUT");
        Signal out = circuit.signal("AND/OUT");
        circuit.nand("AND/NAND0", a, b, nandOut);
        circuit.nand("AND/NAND1", nandOut, nandOut, out);

        CircuitSimulator simulator = new CircuitSimulator(circuit);
        simulator.scheduleAll();
        simulator.runUntilStable(100);

        for (int av = 0; av <= 1; av++) {
            for (int bv = 0; bv <= 1; bv++) {
                simulator.drive(a, LogicValue.fromBoolean(av == 1));
                simulator.drive(b, LogicValue.fromBoolean(bv == 1));
                simulator.runUntilStable(100);
                boolean expected = av == 1 && bv == 1;
                check(out.value().asBoolean() == expected, "AND " + av + "," + bv);
            }
        }
    }

    private static void testBusSplitMerge() {
        LogicCircuit circuit = new LogicCircuit();
        Bus data = Bus.create(circuit, "DATA", 16);
        Bus low = data.slice("LOW", 0, 8);
        Bus high = data.slice("HIGH", 8, 8);
        Bus merged = Bus.merge("MERGED", low, high);
        CircuitSimulator simulator = new CircuitSimulator(circuit);

        data.driveUnsigned(0xA55AL, simulator);
        check(data.readUnsigned() == 0xA55AL, "bus read");
        check(low.readUnsigned() == 0x5AL, "low byte split");
        check(high.readUnsigned() == 0xA5L, "high byte split");
        check(merged.readUnsigned() == 0xA55AL, "bus merge");
    }

    private static void testTraceRingBuffer() {
        LogicCircuit circuit = new LogicCircuit();
        Signal input = circuit.signal("TRACE/A", LogicValue.LOW);
        TraceRecorder trace = new TraceRecorder(3);
        CircuitSimulator simulator = new CircuitSimulator(circuit, trace);

        simulator.drive(input, LogicValue.HIGH);
        simulator.drive(input, LogicValue.LOW);
        simulator.drive(input, LogicValue.HIGH);
        simulator.drive(input, LogicValue.LOW);

        check(trace.size() == 3, "trace capacity");
        check(trace.snapshot().get(0).sequence() == 2L, "trace overwrites oldest event");
        check(trace.snapshot().get(2).sequence() == 4L, "trace keeps newest event");
    }

    private static void testFreeformNotCompiler() {
        CircuitDocument document = makeNotDocument();
        EditorNode input = document.inputNodes().getFirst();
        EditorNode output = document.outputNodes().getFirst();
        CompiledCircuit compiled = CircuitCompiler.compile(document, name -> null);

        compiled.driveInputUnsigned(input.id, 0);
        check(compiled.inputUnsigned(output.id, 0) == 1L, "freeform NOT 0");
        compiled.driveInputUnsigned(input.id, 1);
        check(compiled.inputUnsigned(output.id, 0) == 0L, "freeform NOT 1");
    }

    private static void testFreeformBusSplitMerge() {
        CircuitDocument document = new CircuitDocument();
        EditorNode input = document.addNode(NodeKind.INPUT, 0, 0);
        input.width = 8;
        EditorNode splitter = document.addNode(NodeKind.SPLITTER, 100, 0);
        splitter.width = 8;
        EditorNode merger = document.addNode(NodeKind.MERGER, 200, 0);
        merger.width = 8;
        EditorNode output = document.addNode(NodeKind.OUTPUT, 300, 0);
        output.width = 8;

        document.connect(input.id, 0, splitter.id, 0);
        for (int bit = 0; bit < 8; bit++) {
            document.connect(splitter.id, bit, merger.id, bit);
        }
        document.connect(merger.id, 0, output.id, 0);

        CompiledCircuit compiled = CircuitCompiler.compile(document, name -> null);
        compiled.driveInputUnsigned(input.id, 0xA5L);
        check(compiled.inputUnsigned(output.id, 0) == 0xA5L, "editor split/merge roundtrip");
    }

    private static void testCustomChipFlattening() {
        CircuitDocument notDocument = makeNotDocument();
        ChipDefinition notChip = new ChipDefinition("NOT", notDocument);
        Map<String, ChipDefinition> chips = Map.of("NOT", notChip);

        CircuitDocument parent = new CircuitDocument();
        EditorNode input = parent.addNode(NodeKind.INPUT, 0, 0);
        EditorNode custom = parent.addCustomChip("NOT", 100, 0);
        EditorNode output = parent.addNode(NodeKind.OUTPUT, 200, 0);
        parent.connect(input.id, 0, custom.id, 0);
        parent.connect(custom.id, 0, output.id, 0);

        CompiledCircuit compiled = CircuitCompiler.compile(parent, chips::get);
        compiled.driveInputUnsigned(input.id, 0);
        check(compiled.inputUnsigned(output.id, 0) == 1L, "custom NOT 0");
        compiled.driveInputUnsigned(input.id, 1);
        check(compiled.inputUnsigned(output.id, 0) == 0L, "custom NOT 1");
    }

    private static void testLiveHierarchicalScope() {
        CircuitDocument notDocument = makeNotDocument();
        EditorNode childInput = notDocument.inputNodes().getFirst();
        EditorNode childNand = notDocument.nodes.stream()
                .filter(node -> node.kind == NodeKind.NAND)
                .findFirst()
                .orElseThrow();
        EditorNode childOutput = notDocument.outputNodes().getFirst();
        ChipDefinition notChip = new ChipDefinition("NOT", notDocument);
        Map<String, ChipDefinition> chips = Map.of("NOT", notChip);

        CircuitDocument parent = new CircuitDocument();
        EditorNode parentInput = parent.addNode(NodeKind.INPUT, 0, 0);
        EditorNode custom = parent.addCustomChip("NOT", 100, 0);
        EditorNode parentOutput = parent.addNode(NodeKind.OUTPUT, 200, 0);
        parent.connect(parentInput.id, 0, custom.id, 0);
        parent.connect(custom.id, 0, parentOutput.id, 0);

        CompiledCircuit compiled = CircuitCompiler.compile(parent, chips::get);
        String childScope = CompiledCircuit.childScopePath(CompiledCircuit.ROOT_SCOPE, custom.id, "NOT");
        check(compiled.hasScope(childScope), "nested runtime scope exists");

        compiled.driveInputUnsigned(parentInput.id, 0);
        check(compiled.outputUnsigned(childScope, childInput.id, 0) == 0L, "live child input mirrors parent low");
        check(compiled.outputUnsigned(childScope, childNand.id, 0) == 1L, "live child NAND high for input 0");
        check(compiled.inputUnsigned(childScope, childOutput.id, 0) == 1L, "live child output high for input 0");
        check(compiled.inputUnsigned(parentOutput.id, 0) == 1L, "parent output matches child output high");

        compiled.driveInputUnsigned(parentInput.id, 1);
        check(compiled.outputUnsigned(childScope, childInput.id, 0) == 1L, "live child input mirrors parent high");
        check(compiled.outputUnsigned(childScope, childNand.id, 0) == 0L, "live child NAND low for input 1");
        check(compiled.inputUnsigned(childScope, childOutput.id, 0) == 0L, "live child output low for input 1");
        check(compiled.inputUnsigned(parentOutput.id, 0) == 0L, "parent output matches child output low");

        String renamedScope = CompiledCircuit.childScopePath(CompiledCircuit.ROOT_SCOPE, custom.id, "RENAMED_NOT");
        check(childScope.equals(renamedScope), "scope identity remains stable across chip rename");
    }

    private static void testWidthMismatchRejected() {
        CircuitDocument document = new CircuitDocument();
        EditorNode input = document.addNode(NodeKind.INPUT, 0, 0);
        input.width = 8;
        EditorNode output = document.addNode(NodeKind.OUTPUT, 100, 0);
        output.width = 1;
        document.connect(input.id, 0, output.id, 0);

        boolean rejected = false;
        try {
            CircuitCompiler.compile(document, name -> null);
        } catch (CircuitCompileException expected) {
            rejected = expected.getMessage().contains("Width mismatch");
        }
        check(rejected, "width mismatch must be rejected");
    }

    private static void testRoutedWireDoesNotChangeLogic() {
        CircuitDocument document = makeNotDocument();
        WireConnection routed = document.wires.getFirst();
        routed.setRoutePoints(List.of(
                new RoutePoint(30, 40),
                new RoutePoint(65, 40),
                new RoutePoint(65, -25),
                new RoutePoint(90, -25)
        ));
        document.normalize();

        check(routed.routePoints().size() == 4, "manual route points persisted in document model");

        EditorNode input = document.inputNodes().getFirst();
        EditorNode output = document.outputNodes().getFirst();
        CompiledCircuit compiled = CircuitCompiler.compile(document, name -> null);
        compiled.driveInputUnsigned(input.id, 0);
        check(compiled.inputUnsigned(output.id, 0) == 1L, "wire route must not affect NOT 0");
        compiled.driveInputUnsigned(input.id, 1);
        check(compiled.inputUnsigned(output.id, 0) == 0L, "wire route must not affect NOT 1");
    }

    private static void testChipVisualSettingsBounds() {
        ChipVisualSettings tooSmall = new ChipVisualSettings(-500, 1, 2);
        check(tooSmall.width == 72.0, "chip width lower bound");
        check(tooSmall.minHeight == 42.0, "chip height lower bound");
        check(tooSmall.portSpacing == 10.0, "chip pin spacing lower bound");

        ChipVisualSettings tooLarge = new ChipVisualSettings(9999, 9999, 9999);
        check(tooLarge.width == 260.0, "chip width upper bound");
        check(tooLarge.minHeight == 300.0, "chip height upper bound");
        check(tooLarge.portSpacing == 48.0, "chip pin spacing upper bound");
    }

    private static void testInterconnectValidation() {
        InterconnectGraph graph = new InterconnectGraph();
        graph.registerDevice(new InterconnectDevice(
                "CPU",
                List.of(),
                List.of(
                        new PortSpec("DATA", PortDirection.OUTPUT, 16),
                        new PortSpec("IRQ", PortDirection.OUTPUT, 1)
                )
        ));
        graph.registerDevice(new InterconnectDevice(
                "RAM",
                List.of(new PortSpec("DATA", PortDirection.INPUT, 16)),
                List.of()
        ));
        graph.registerDevice(new InterconnectDevice(
                "CTRL",
                List.of(new PortSpec("IRQ", PortDirection.INPUT, 1)),
                List.of()
        ));

        CableConnection bus = graph.connect(
                new DevicePortAddress("CPU", PortDirection.OUTPUT, 0),
                new DevicePortAddress("RAM", PortDirection.INPUT, 0),
                CableKind.BUS
        );
        check(bus.width() == 16 && bus.kind() == CableKind.BUS, "16-bit world bus connection");

        CableConnection signal = graph.connect(
                new DevicePortAddress("CPU", PortDirection.OUTPUT, 1),
                new DevicePortAddress("CTRL", PortDirection.INPUT, 0),
                CableKind.SIGNAL
        );
        check(signal.width() == 1 && signal.kind() == CableKind.SIGNAL, "1-bit world signal wire");

        boolean wrongCableRejected = false;
        try {
            graph.disconnect(bus);
            graph.connect(
                    new DevicePortAddress("CPU", PortDirection.OUTPUT, 0),
                    new DevicePortAddress("RAM", PortDirection.INPUT, 0),
                    CableKind.SIGNAL
            );
        } catch (IllegalArgumentException expected) {
            wrongCableRejected = expected.getMessage().contains("1-bit");
        }
        check(wrongCableRejected, "16-bit port must reject signal wire");

        graph.registerDevice(new InterconnectDevice(
                "BYTE_DEVICE",
                List.of(new PortSpec("DATA", PortDirection.INPUT, 8)),
                List.of()
        ));
        boolean mismatchRejected = false;
        try {
            graph.connect(
                    new DevicePortAddress("CPU", PortDirection.OUTPUT, 0),
                    new DevicePortAddress("BYTE_DEVICE", PortDirection.INPUT, 0),
                    CableKind.BUS
            );
        } catch (IllegalArgumentException expected) {
            mismatchRejected = expected.getMessage().contains("Width mismatch");
        }
        check(mismatchRejected, "world cable width mismatch must be rejected");
    }

    private static CircuitDocument makeNotDocument() {
        CircuitDocument document = new CircuitDocument();
        EditorNode input = document.addNode(NodeKind.INPUT, 0, 0);
        input.label = "A";
        EditorNode nand = document.addNode(NodeKind.NAND, 100, 0);
        EditorNode output = document.addNode(NodeKind.OUTPUT, 200, 0);
        output.label = "OUT";
        document.connect(input.id, 0, nand.id, 0);
        document.connect(input.id, 0, nand.id, 1);
        document.connect(nand.id, 0, output.id, 0);
        return document;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
