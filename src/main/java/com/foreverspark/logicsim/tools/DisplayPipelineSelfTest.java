package com.foreverspark.logicsim.tools;

import com.foreverspark.logicsim.display.DisplayCommandCodec;
import com.foreverspark.logicsim.display.DisplayFramebuffer;
import com.foreverspark.logicsim.display.ScreenOutputDeviceDefinition;
import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.interconnect.CircuitProgram;
import com.foreverspark.logicsim.interconnect.CircuitProgramRuntime;

import java.util.LinkedHashMap;
import java.util.Map;

/** Regression test for the real compiled BOARD -> SCREEN OUTPUT -> DATA64 -> framebuffer path. */
public final class DisplayPipelineSelfTest {
    private DisplayPipelineSelfTest() {}

    public static void main(String[] args) {
        testCompiledBoardProducesPixelCommand();
        testPhysicalInputsStartLow();
        System.out.println("Display compiled DATA64 pipeline self-test: PASS");
    }

    private static void testCompiledBoardProducesPixelCommand() {
        CircuitDocument board = new CircuitDocument();
        EditorNode x = constant(board, 16, 1L, 0);
        EditorNode y = constant(board, 16, 1L, 40);
        EditorNode color = constant(board, 16, 0xFFFFL, 80);
        EditorNode draw = constant(board, 1, 1L, 120);
        EditorNode clear = constant(board, 1, 0L, 160);
        EditorNode screen = board.addCustomChip(ScreenOutputDeviceDefinition.ID, 180, 60);
        EditorNode output = board.addNode(NodeKind.OUTPUT, 450, 60);
        output.width = 64;
        output.label = "SCREEN_DATA";

        board.connect(x.id, 0, screen.id, 0);
        board.connect(y.id, 0, screen.id, 1);
        board.connect(color.id, 0, screen.id, 2);
        board.connect(draw.id, 0, screen.id, 3);
        board.connect(clear.id, 0, screen.id, 4);
        board.connect(screen.id, 0, output.id, 0);

        ChipDefinition screenDefinition = ScreenOutputDeviceDefinition.create();
        Map<String, ChipDefinition> dependencies = new LinkedHashMap<>();
        dependencies.put(screenDefinition.name, screenDefinition);
        CircuitProgramRuntime runtime = new CircuitProgramRuntime(
                new CircuitProgram(new ChipDefinition("BOARD", board), dependencies)
        );

        long actual = runtime.outputValue("SCREEN_DATA");
        long expected = DisplayCommandCodec.pixel(1, 1, 0xFFFF);
        check(actual == expected,
                "compiled SCREEN OUTPUT DATA64 mismatch: expected " + DisplayCommandCodec.hex(expected)
                        + " but got " + DisplayCommandCodec.hex(actual));

        DisplayCommandCodec.Command decoded = DisplayCommandCodec.decode(actual);
        check(decoded.isPixel(), "DATA64 decodes as DRAW/PIXEL opcode");
        check(decoded.x() == 1 && decoded.y() == 1, "DATA64 preserves X/Y coordinates");
        check(decoded.rgb565() == 0xFFFF, "DATA64 preserves RGB565 color");

        DisplayFramebuffer framebuffer = new DisplayFramebuffer(32, 32);
        check(framebuffer.writePixel(decoded.x(), decoded.y(), decoded.rgb565()), "decoded pixel reaches framebuffer");
        check(framebuffer.pixelRgb565(1, 1) == 0xFFFF, "framebuffer pixel is really non-black");
        check(framebuffer.pixelArgb(1, 1) == 0xFFFFFFFF, "white RGB565 really renders as white ARGB");
    }

    private static void testPhysicalInputsStartLow() {
        CircuitDocument board = new CircuitDocument();
        EditorNode input = board.addNode(NodeKind.INPUT, 0, 0);
        input.width = 1;
        input.label = "TEST_IN";
        EditorNode output = board.addNode(NodeKind.OUTPUT, 120, 0);
        output.width = 1;
        output.label = "TEST_OUT";
        board.connect(input.id, 0, output.id, 0);

        CircuitProgramRuntime runtime = new CircuitProgramRuntime(
                new CircuitProgram(new ChipDefinition("INPUT_TEST", board), Map.of())
        );
        check(runtime.outputValue("TEST_OUT") == 0L, "undriven physical INPUT starts LOW");
        runtime.driveInput("TEST_IN", 1L);
        check(runtime.outputValue("TEST_OUT") == 1L, "physical INPUT responds to real external drive");
    }

    private static EditorNode constant(CircuitDocument board, int width, long value, double y) {
        EditorNode node = board.addNode(NodeKind.CONSTANT, 0, y);
        node.width = width;
        node.constantValue = value;
        return node;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
