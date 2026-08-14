package com.foreverspark.logicsim.tools;

import com.foreverspark.logicsim.editor.model.ChipLookup;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.runtime.CircuitCompiler;
import com.foreverspark.logicsim.editor.runtime.CircuitTimingController;
import com.foreverspark.logicsim.editor.runtime.CompiledCircuit;

/** Deterministic regression checks for the edge-triggered RANDOM source. */
public final class RandomSourceSelfTest {
    private RandomSourceSelfTest() {}

    public static void main(String[] args) {
        manualRisingEdgeCheck();
        clockRisingEdgeCheck();
        pulseBatchEligibilityCheck();
        wiredEnablePulseBatchCheck();
        System.out.println("RandomSourceSelfTest OK");
    }

    private static void manualRisingEdgeCheck() {
        CircuitDocument document = new CircuitDocument();
        EditorNode trigger = document.addNode(NodeKind.INPUT, 0, 0);
        trigger.width = 1;
        EditorNode random = document.addNode(NodeKind.CONSTANT, 30, 0);
        random.randomSource = true;
        random.randomChancePercent = 100;
        random.width = 1;
        EditorNode output = document.addNode(NodeKind.OUTPUT, 60, 0);
        output.width = 1;
        document.connect(trigger.id, 0, random.id, 0);
        document.connect(random.id, 0, output.id, 0);

        CompiledCircuit compiled = CircuitCompiler.compile(document, ChipLookup.empty());
        compiled.driveInputUnsigned(trigger.id, 0L);
        CircuitTimingController timing = new CircuitTimingController(compiled, document, ChipLookup.empty());
        require(compiled.inputUnsigned(output.id, 0) == 0L, "RANDOM must start LOW");

        compiled.driveInputUnsigned(trigger.id, 1L);
        require(timing.processRandomSources() == 1, "First 0 -> 1 edge must fire once");
        require(compiled.inputUnsigned(output.id, 0) == 1L, "100% RANDOM must emit HIGH");

        timing.setRandomChancePercent(CompiledCircuit.ROOT_SCOPE, random.id, 0);
        require(timing.processRandomSources() == 0, "Holding TRIGGER HIGH must not re-roll");
        require(compiled.inputUnsigned(output.id, 0) == 1L, "Output must hold while TRIGGER remains HIGH");

        compiled.driveInputUnsigned(trigger.id, 0L);
        require(timing.processRandomSources() == 0, "Falling edge must not sample");
        compiled.driveInputUnsigned(trigger.id, 1L);
        require(timing.processRandomSources() == 1, "Second 0 -> 1 edge must fire once");
        require(compiled.inputUnsigned(output.id, 0) == 0L, "0% RANDOM must emit LOW");
    }

    private static void clockRisingEdgeCheck() {
        CircuitDocument document = new CircuitDocument();
        EditorNode clock = document.addNode(NodeKind.CONSTANT, 0, 0);
        clock.clockSource = true;
        clock.clockFrequencyHz = 20L;
        clock.width = 1;
        EditorNode random = document.addNode(NodeKind.CONSTANT, 30, 0);
        random.randomSource = true;
        random.randomChancePercent = 100;
        random.width = 1;
        EditorNode output = document.addNode(NodeKind.OUTPUT, 60, 0);
        output.width = 1;
        document.connect(clock.id, 0, random.id, 0);
        document.connect(random.id, 0, output.id, 0);

        CompiledCircuit compiled = CircuitCompiler.compile(document, ChipLookup.empty());
        CircuitTimingController timing = new CircuitTimingController(compiled, document, ChipLookup.empty());
        require(compiled.inputUnsigned(output.id, 0) == 0L, "Clock-driven RANDOM must start LOW");

        require(timing.stepEdges(CompiledCircuit.ROOT_SCOPE, clock.id, 1L) == 1L, "Clock rising edge missing");
        require(compiled.inputUnsigned(output.id, 0) == 1L, "Clock rising edge must trigger 100% RANDOM");

        timing.setRandomChancePercent(CompiledCircuit.ROOT_SCOPE, random.id, 0);
        timing.stepEdges(CompiledCircuit.ROOT_SCOPE, clock.id, 1L); // falling edge
        require(compiled.inputUnsigned(output.id, 0) == 1L, "Clock falling edge must not sample RANDOM");
        timing.stepEdges(CompiledCircuit.ROOT_SCOPE, clock.id, 1L); // next rising edge
        require(compiled.inputUnsigned(output.id, 0) == 0L, "Next clock rising edge must sample updated RANDOM chance");
    }

    private static void pulseBatchEligibilityCheck() {
        CircuitDocument document = new CircuitDocument();
        EditorNode clock = document.addNode(NodeKind.CONSTANT, 0, 0);
        clock.clockSource = true;
        clock.clockFrequencyHz = 5_000_000L;
        clock.width = 1;
        EditorNode random = document.addNode(NodeKind.CONSTANT, 30, 0);
        random.randomSource = true;
        random.randomChancePercent = 50;
        random.width = 1;
        EditorNode output = document.addNode(NodeKind.OUTPUT, 60, 0);
        output.width = 1;
        document.connect(clock.id, 0, random.id, 0);
        document.connect(random.id, 0, output.id, 0);

        CompiledCircuit compiled = CircuitCompiler.compile(document, ChipLookup.empty());
        CircuitTimingController timing = new CircuitTimingController(compiled, document, ChipLookup.empty());
        require(timing.pulseBatchEligible(CompiledCircuit.ROOT_SCOPE, clock.id),
                "Direct CLOCK -> RANDOM should qualify for pulse batching");
    }

    private static void wiredEnablePulseBatchCheck() {
        CircuitDocument document = new CircuitDocument();
        EditorNode enable = document.addNode(NodeKind.CONSTANT, -30, 0);
        enable.width = 1;
        enable.constantValue = 1L;
        EditorNode clock = document.addNode(NodeKind.CONSTANT, 0, 0);
        clock.clockSource = true;
        clock.clockFrequencyHz = 5_000_000L;
        clock.width = 1;
        EditorNode random = document.addNode(NodeKind.CONSTANT, 30, 0);
        random.randomSource = true;
        random.randomChancePercent = 50;
        random.width = 1;
        document.connect(enable.id, 0, clock.id, 0);
        document.connect(clock.id, 0, random.id, 0);

        CompiledCircuit compiled = CircuitCompiler.compile(document, ChipLookup.empty());
        CircuitTimingController timing = new CircuitTimingController(compiled, document, ChipLookup.empty());
        require(timing.enabled(CompiledCircuit.ROOT_SCOPE, clock.id), "Wired HIGH clock ENABLE should be active");
        require(timing.pulseBatchEligible(CompiledCircuit.ROOT_SCOPE, clock.id),
                "Wired HIGH ENABLE must not automatically disable direct pulse batching");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
