package com.foreverspark.logicsim.tools;

import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.runtime.CircuitCompiler;
import com.foreverspark.logicsim.editor.runtime.CompiledCircuit;

/** Regression tests for non-logic infrastructure used when assembling a CPU. */
public final class InfrastructureSelfTest {
    private InfrastructureSelfTest() {}

    public static void main(String[] args) {
        testBusPassThrough();
        testConstantBus();
        testProbeSink();
        testConstantSplitMerge();
        System.out.println("CPU editor infrastructure self-test: PASS");
    }

    private static void testBusPassThrough() {
        CircuitDocument document = new CircuitDocument();
        EditorNode input = document.addNode(NodeKind.INPUT, 0, 0);
        input.width = 16;
        EditorNode bus = document.addNode(NodeKind.BUS, 100, 0);
        bus.width = 16;
        EditorNode output = document.addNode(NodeKind.OUTPUT, 200, 0);
        output.width = 16;
        document.connect(input.id, 0, bus.id, 0);
        document.connect(bus.id, 0, output.id, 0);

        CompiledCircuit compiled = CircuitCompiler.compile(document, name -> null);
        compiled.driveInputUnsigned(input.id, 0xA55AL);
        check(compiled.inputUnsigned(output.id, 0) == 0xA55AL, "16-bit BUS pass-through");
    }

    private static void testConstantBus() {
        CircuitDocument document = new CircuitDocument();
        EditorNode constant = document.addNode(NodeKind.CONSTANT, 0, 0);
        constant.width = 16;
        constant.constantValue = 0xBEEFL;
        EditorNode output = document.addNode(NodeKind.OUTPUT, 160, 0);
        output.width = 16;
        document.connect(constant.id, 0, output.id, 0);

        CompiledCircuit compiled = CircuitCompiler.compile(document, name -> null);
        check(compiled.inputUnsigned(output.id, 0) == 0xBEEFL, "16-bit CONSTANT output");
    }

    private static void testProbeSink() {
        CircuitDocument document = new CircuitDocument();
        EditorNode input = document.addNode(NodeKind.INPUT, 0, 0);
        input.width = 8;
        EditorNode probe = document.addNode(NodeKind.PROBE, 160, 0);
        probe.width = 8;
        document.connect(input.id, 0, probe.id, 0);

        CompiledCircuit compiled = CircuitCompiler.compile(document, name -> null);
        compiled.driveInputUnsigned(input.id, 0x5AL);
        check(compiled.inputUnsigned(probe.id, 0) == 0x5AL, "PROBE reads bus without modifying it");
    }

    private static void testConstantSplitMerge() {
        CircuitDocument document = new CircuitDocument();
        EditorNode constant = document.addNode(NodeKind.CONSTANT, 0, 0);
        constant.width = 8;
        constant.constantValue = 0xA5L;
        EditorNode splitter = document.addNode(NodeKind.SPLITTER, 80, 0);
        splitter.width = 8;
        EditorNode merger = document.addNode(NodeKind.MERGER, 180, 0);
        merger.width = 8;
        EditorNode output = document.addNode(NodeKind.OUTPUT, 280, 0);
        output.width = 8;

        document.connect(constant.id, 0, splitter.id, 0);
        for (int bit = 0; bit < 8; bit++) document.connect(splitter.id, bit, merger.id, bit);
        document.connect(merger.id, 0, output.id, 0);

        CompiledCircuit compiled = CircuitCompiler.compile(document, name -> null);
        check(compiled.inputUnsigned(output.id, 0) == 0xA5L, "CONSTANT -> SPLITTER -> MERGER");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
