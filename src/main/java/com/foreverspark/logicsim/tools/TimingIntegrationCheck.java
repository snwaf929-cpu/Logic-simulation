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
        CompiledCircuit compiled = CircuitCompiler.compile(document, ChipLookup.empty());
        new CircuitTimingController(compiled, document, ChipLookup.empty());
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
