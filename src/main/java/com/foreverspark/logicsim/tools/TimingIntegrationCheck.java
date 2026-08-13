package com.foreverspark.logicsim.tools;

import com.foreverspark.logicsim.editor.model.ChipLookup;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.runtime.CircuitCompiler;
import com.foreverspark.logicsim.editor.runtime.CircuitTimingController;
import com.foreverspark.logicsim.editor.runtime.CompiledCircuit;

public final class TimingIntegrationCheck {
    private TimingIntegrationCheck() {}

    public static void main(String[] args) {
        CircuitDocument document = inverterClockDocument();
        EditorNode clock = document.nodes.getFirst();
        EditorNode output = document.outputNodes().getFirst();
        CompiledCircuit compiled = CircuitCompiler.compile(document, ChipLookup.empty());
        CircuitTimingController timing = new CircuitTimingController(compiled, document, ChipLookup.empty());
        TimingIntegrationAssertions.require(timing.hasClock(CompiledCircuit.ROOT_SCOPE, clock.id), "root clock discovered");
        TimingIntegrationAssertions.require(timing.frequencyHz(CompiledCircuit.ROOT_SCOPE, clock.id) == 5_000_000L, "5 MHz preserved");
        TimingIntegrationAssertions.require(compiled.inputUnsigned(output.id, 0) == 1L, "clock starts low");
        timing.stepEdges(CompiledCircuit.ROOT_SCOPE, clock.id, 1L);
        TimingIntegrationAssertions.require(compiled.inputUnsigned(output.id, 0) == 0L, "rising edge settles NAND");
        System.out.println("Timing integration check: PASS");
    }

    private static CircuitDocument inverterClockDocument() {
        CircuitDocument document = new CircuitDocument();
        EditorNode clock = document.addNode(NodeKind.CONSTANT, 0, 0);
        clock.clockSource = true;
        clock.clockFrequencyHz = 5_000_000L;
        clock.width = 1;
        clock.constantValue = 0L;
        EditorNode nand = document.addNode(NodeKind.NAND, 80, 0);
        EditorNode output = document.addNode(NodeKind.OUTPUT, 180, 0);
        document.connect(clock.id, 0, nand.id, 0);
        document.connect(clock.id, 0, nand.id, 1);
        document.connect(nand.id, 0, output.id, 0);
        return document;
    }
}
