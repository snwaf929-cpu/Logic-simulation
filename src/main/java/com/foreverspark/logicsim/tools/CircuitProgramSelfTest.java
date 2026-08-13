package com.foreverspark.logicsim.tools;

import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.interconnect.CircuitProgram;
import com.foreverspark.logicsim.interconnect.CircuitProgramRuntime;

import java.util.Map;

public final class CircuitProgramSelfTest {
    private CircuitProgramSelfTest() {}

    public static void main(String[] args) {
        testExternalInputRoundTrip();
        testClockRoundTrip();
        System.out.println("World circuit program + CLOCK self-test: PASS");
    }

    private static void testExternalInputRoundTrip() {
        CircuitDocument document = new CircuitDocument();
        EditorNode input = document.addNode(NodeKind.INPUT, 0, 0);
        input.label = "IN";
        EditorNode nand = document.addNode(NodeKind.NAND, 80, 0);
        EditorNode output = document.addNode(NodeKind.OUTPUT, 180, 0);
        output.label = "OUT";
        document.connect(input.id, 0, nand.id, 0);
        document.connect(input.id, 0, nand.id, 1);
        document.connect(nand.id, 0, output.id, 0);

        CircuitProgram source = new CircuitProgram(new ChipDefinition("WORLD_NOT", document), Map.of());
        CircuitProgramRuntime runtime = new CircuitProgramRuntime(CircuitProgram.fromJson(source.toJson()));
        runtime.driveInput("IN", 0);
        check(runtime.outputValue("OUT") == 1L, "NOT(0)=1 after program JSON round trip");
        runtime.driveInput("IN", 1);
        check(runtime.outputValue("OUT") == 0L, "NOT(1)=0 after program JSON round trip");
    }

    private static void testClockRoundTrip() {
        CircuitDocument document = new CircuitDocument();
        EditorNode clock = document.addNode(NodeKind.CONSTANT, 0, 0);
        clock.clockSource = true;
        clock.clockFrequencyHz = 1L;
        clock.width = 1;
        clock.constantValue = 0L;
        EditorNode nand = document.addNode(NodeKind.NAND, 80, 0);
        EditorNode output = document.addNode(NodeKind.OUTPUT, 180, 0);
        output.label = "OUT";
        document.connect(clock.id, 0, nand.id, 0);
        document.connect(clock.id, 0, nand.id, 1);
        document.connect(nand.id, 0, output.id, 0);

        CircuitProgram source = new CircuitProgram(new ChipDefinition("WORLD_CLOCK", document), Map.of());
        CircuitProgramRuntime runtime = new CircuitProgramRuntime(CircuitProgram.fromJson(source.toJson()));
        check(runtime.timing().clocks().size() == 1, "program CLOCK survives JSON round trip");
        check(runtime.outputValue("OUT") == 1L, "program CLOCK starts low");
        int[] callbacks = {0};
        runtime.advanceClocksNanos(500_000_000L, 10L, () -> callbacks[0]++);
        check(runtime.outputValue("OUT") == 0L, "first world CLOCK edge reaches circuit output");
        check(callbacks[0] == 1, "settled world CLOCK edge callback fires once");
        runtime.advanceClocksNanos(500_000_000L, 10L, () -> callbacks[0]++);
        check(runtime.outputValue("OUT") == 1L, "second world CLOCK edge reaches circuit output");
        check(callbacks[0] == 2, "settled edge callbacks are not dropped");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
