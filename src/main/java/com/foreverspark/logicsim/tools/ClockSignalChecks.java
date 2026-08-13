package com.foreverspark.logicsim.tools;

import com.foreverspark.logicsim.core.CircuitSimulator;
import com.foreverspark.logicsim.core.LogicCircuit;
import com.foreverspark.logicsim.core.LogicValue;
import com.foreverspark.logicsim.core.Signal;
import com.foreverspark.logicsim.core.TimingSignalDriver;
import com.foreverspark.logicsim.editor.model.ChipLookup;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.runtime.CircuitCompiler;
import com.foreverspark.logicsim.editor.runtime.CircuitTimingController;
import com.foreverspark.logicsim.editor.runtime.CompiledCircuit;

public final class ClockSignalChecks {
    private ClockSignalChecks() {}

    public static void main(String[] args) {
        LogicCircuit circuit = new LogicCircuit();
        Signal clock = circuit.signal("CLOCK", LogicValue.LOW);
        Signal inverted = circuit.signal("INVERTED");
        circuit.nand("NOT", clock, clock, inverted);
        CircuitSimulator simulator = new CircuitSimulator(circuit);
        simulator.scheduleAll();
        simulator.runUntilStable(100L);

        TimingSignalDriver driver = new TimingSignalDriver(10L, simulator, clock, 100L);
        require(inverted.value() == LogicValue.HIGH, "low clock settles through NAND");
        driver.stepEdges(1L);
        require(clock.value() == LogicValue.HIGH, "first edge drives clock high");
        require(inverted.value() == LogicValue.LOW, "NAND settles after rising edge");
        driver.stepEdges(1L);
        require(clock.value() == LogicValue.LOW, "second edge drives clock low");
        require(inverted.value() == LogicValue.HIGH, "NAND settles after falling edge");
        checkEnablePin();
        TimingIntegrationCheck.main(args);
        System.out.println("Virtual clock signal self-test: PASS");
    }

    private static void checkEnablePin() {
        CircuitDocument doc = new CircuitDocument();
        EditorNode enable = doc.addNode(NodeKind.INPUT, 0, 0);
        EditorNode source = doc.addNode(NodeKind.CONSTANT, 80, 0);
        source.clockSource = true;
        source.clockFrequencyHz = 10L;
        EditorNode output = doc.addNode(NodeKind.OUTPUT, 160, 0);
        doc.connect(enable.id, 0, source.id, 0);
        doc.connect(source.id, 0, output.id, 0);
        CompiledCircuit compiled = CircuitCompiler.compile(doc, ChipLookup.empty());
        CircuitTimingController timing = new CircuitTimingController(compiled, doc, ChipLookup.empty());

        compiled.driveInputUnsigned(enable.id, 0L);
        require(!timing.enabled(CompiledCircuit.ROOT_SCOPE, source.id), "low enable pauses clock");
        timing.advanceNanos(1_000_000_000L, 100L);
        require(compiled.inputUnsigned(output.id, 0) == 0L, "paused clock does not move");
        require(timing.pendingEdges(CompiledCircuit.ROOT_SCOPE, source.id) == 0L, "paused time creates no backlog");

        compiled.driveInputUnsigned(enable.id, 1L);
        timing.advanceNanos(50_000_000L, 10L);
        require(compiled.inputUnsigned(output.id, 0) == 1L, "enabled clock advances");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
