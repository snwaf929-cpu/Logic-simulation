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
        System.out.println("World circuit program self-test: PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
