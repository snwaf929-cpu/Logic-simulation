package com.foreverspark.logicsim.tools;

import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.runtime.CircuitCompileException;
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
        testGroupedSplitMerge64();
        testSixteenIndividualBitsIntoBusAndBack();
        testFloatingInputsDefaultLow();
        testClockNormalizationKeeps100MHz();
        testStructuralBusLoopRejectedWithoutStackOverflow();
        testNandFeedbackStillCompiles();
        testNestedCustomChipNandFeedbackCompiles();
        System.out.println("CPU editor infrastructure + grouped bus lanes + floating-low + 100 MHz CLOCK normalization + cycle safety self-test: PASS");
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
        check(compiled.inputUnsigned(output.id, 0) == 0xA5L, "legacy 1-bit CONSTANT -> SPLITTER -> MERGER");
    }

    /** 64-bit bus -> four 16-bit outputs -> four 16-bit inputs -> 64-bit bus. */
    private static void testGroupedSplitMerge64() {
        CircuitDocument document = new CircuitDocument();
        EditorNode input = document.addNode(NodeKind.INPUT, 0, 0);
        input.width = 64;

        EditorNode splitter = document.addNode(NodeKind.SPLITTER, 100, 0);
        splitter.width = 64;
        splitter.laneWidth = 16;

        EditorNode merger = document.addNode(NodeKind.MERGER, 280, 0);
        merger.width = 64;
        merger.laneWidth = 16;

        EditorNode[] laneOutputs = new EditorNode[4];
        for (int lane = 0; lane < 4; lane++) {
            laneOutputs[lane] = document.addNode(NodeKind.OUTPUT, 200, lane * 50.0);
            laneOutputs[lane].width = 16;
            document.connect(splitter.id, lane, laneOutputs[lane].id, 0);
            document.connect(splitter.id, lane, merger.id, lane);
        }

        EditorNode output = document.addNode(NodeKind.OUTPUT, 420, 0);
        output.width = 64;
        document.connect(input.id, 0, splitter.id, 0);
        document.connect(merger.id, 0, output.id, 0);

        CompiledCircuit compiled = CircuitCompiler.compile(document, name -> null);
        long pattern = 0x0123456789ABCDEFL;
        compiled.driveInputUnsigned(input.id, pattern);

        check(compiled.inputUnsigned(laneOutputs[0].id, 0) == 0xCDEFL, "grouped lane 0 = bits 0..15");
        check(compiled.inputUnsigned(laneOutputs[1].id, 0) == 0x89ABL, "grouped lane 1 = bits 16..31");
        check(compiled.inputUnsigned(laneOutputs[2].id, 0) == 0x4567L, "grouped lane 2 = bits 32..47");
        check(compiled.inputUnsigned(laneOutputs[3].id, 0) == 0x0123L, "grouped lane 3 = bits 48..63");
        check(compiled.inputUnsigned(output.id, 0) == pattern, "four 16-bit lanes reassemble into original 64-bit bus");
    }

    /**
     * This is the exact workflow used to turn sixteen physical 1-bit lines into one 16-bit bus:
     * 16 inputs -> MERGER[16] -> BUS[16] -> SPLITTER[16] -> 16 outputs.
     */
    private static void testSixteenIndividualBitsIntoBusAndBack() {
        CircuitDocument document = new CircuitDocument();
        EditorNode[] bitsIn = new EditorNode[16];
        EditorNode[] bitsOut = new EditorNode[16];

        EditorNode merger = document.addNode(NodeKind.MERGER, 100, 0);
        merger.width = 16;
        EditorNode bus = document.addNode(NodeKind.BUS, 220, 0);
        bus.width = 16;
        EditorNode splitter = document.addNode(NodeKind.SPLITTER, 340, 0);
        splitter.width = 16;

        for (int bit = 0; bit < 16; bit++) {
            bitsIn[bit] = document.addNode(NodeKind.INPUT, 0, bit * 24.0);
            bitsOut[bit] = document.addNode(NodeKind.OUTPUT, 480, bit * 24.0);
            document.connect(bitsIn[bit].id, 0, merger.id, bit);
            document.connect(splitter.id, bit, bitsOut[bit].id, 0);
        }
        document.connect(merger.id, 0, bus.id, 0);
        document.connect(bus.id, 0, splitter.id, 0);

        CompiledCircuit compiled = CircuitCompiler.compile(document, name -> null);
        int pattern = 0xA55A;
        for (int bit = 0; bit < 16; bit++) {
            compiled.driveInputUnsigned(bitsIn[bit].id, (pattern >>> bit) & 1);
        }
        for (int bit = 0; bit < 16; bit++) {
            long expected = (pattern >>> bit) & 1;
            check(compiled.inputUnsigned(bitsOut[bit].id, 0) == expected, "16-bit packed bus bit " + bit);
        }
    }

    private static void testFloatingInputsDefaultLow() {
        CircuitDocument document = new CircuitDocument();
        EditorNode nand = document.addNode(NodeKind.NAND, 80, 0);
        EditorNode nandOutput = document.addNode(NodeKind.OUTPUT, 200, 0);
        document.connect(nand.id, 0, nandOutput.id, 0);

        EditorNode floatingBusOutput = document.addNode(NodeKind.OUTPUT, 200, 80);
        floatingBusOutput.width = 16;

        CompiledCircuit compiled = CircuitCompiler.compile(document, name -> null);
        check(compiled.inputUnsigned(nandOutput.id, 0) == 1L, "unconnected NAND inputs default to 0, so NAND output is 1");
        check(compiled.inputUnsigned(floatingBusOutput.id, 0) == 0L, "unconnected 16-bit input defaults to 0x0000");
    }

    private static void testClockNormalizationKeeps100MHz() {
        CircuitDocument document = new CircuitDocument();
        EditorNode clock = document.addNode(NodeKind.CONSTANT, 0, 0);
        clock.clockSource = true;
        clock.clockFrequencyHz = 100_000_000L;
        document.normalize();
        check(clock.clockFrequencyHz == 100_000_000L, "100 MHz CLOCK must survive document normalization");

        clock.clockFrequencyHz = 100_000_001L;
        document.normalize();
        check(clock.clockFrequencyHz == 100_000_000L, "CLOCK normalization must cap above-range values at 100 MHz");
    }

    private static void testStructuralBusLoopRejectedWithoutStackOverflow() {
        CircuitDocument document = new CircuitDocument();
        EditorNode a = document.addNode(NodeKind.BUS, 0, 0);
        EditorNode b = document.addNode(NodeKind.BUS, 120, 0);
        a.width = 16;
        b.width = 16;
        document.connect(a.id, 0, b.id, 0);
        document.connect(b.id, 0, a.id, 0);

        boolean rejected = false;
        try {
            CircuitCompiler.compile(document, name -> null);
        } catch (CircuitCompileException expected) {
            rejected = expected.getMessage() != null && expected.getMessage().contains("Structural wiring loop");
        }
        check(rejected, "routing-only BUS cycle must be rejected cleanly instead of stack overflowing");
    }

    private static void testNandFeedbackStillCompiles() {
        CircuitDocument document = new CircuitDocument();
        EditorNode setHigh = document.addNode(NodeKind.CONSTANT, 0, 0);
        EditorNode resetHigh = document.addNode(NodeKind.CONSTANT, 0, 80);
        setHigh.constantValue = 1;
        resetHigh.constantValue = 1;

        EditorNode q = document.addNode(NodeKind.NAND, 120, 0);
        EditorNode nq = document.addNode(NodeKind.NAND, 120, 80);
        EditorNode output = document.addNode(NodeKind.OUTPUT, 260, 0);

        document.connect(setHigh.id, 0, q.id, 0);
        document.connect(nq.id, 0, q.id, 1);
        document.connect(resetHigh.id, 0, nq.id, 0);
        document.connect(q.id, 0, nq.id, 1);
        document.connect(q.id, 0, output.id, 0);

        CircuitCompiler.compile(document, name -> null);
    }

    private static void testNestedCustomChipNandFeedbackCompiles() {
        CircuitDocument stateCircuit = new CircuitDocument();
        EditorNode d = stateCircuit.addNode(NodeKind.INPUT, 0, 0);
        d.label = "D";

        EditorNode one = stateCircuit.addNode(NodeKind.CONSTANT, 0, 80);
        one.constantValue = 1;

        EditorNode q = stateCircuit.addNode(NodeKind.NAND, 120, 0);
        EditorNode nq = stateCircuit.addNode(NodeKind.NAND, 120, 80);
        EditorNode out = stateCircuit.addNode(NodeKind.OUTPUT, 260, 0);
        out.label = "Q";

        stateCircuit.connect(one.id, 0, q.id, 0);
        stateCircuit.connect(nq.id, 0, q.id, 1);
        stateCircuit.connect(d.id, 0, nq.id, 0);
        stateCircuit.connect(q.id, 0, nq.id, 1);
        stateCircuit.connect(q.id, 0, out.id, 0);

        ChipDefinition stateful = new ChipDefinition("STATEFUL_NAND", stateCircuit);

        CircuitDocument board = new CircuitDocument();
        EditorNode state = board.addCustomChip("STATEFUL_NAND", 100, 0);
        EditorNode route = board.addNode(NodeKind.BUS, 260, 0);
        route.width = 1;
        board.connect(state.id, 0, route.id, 0);
        board.connect(route.id, 0, state.id, 0);

        CircuitCompiler.compile(board, name -> "STATEFUL_NAND".equals(name) ? stateful : null);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
