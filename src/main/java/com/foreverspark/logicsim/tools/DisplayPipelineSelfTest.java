package com.foreverspark.logicsim.tools;

import com.foreverspark.logicsim.display.DisplayCommandCodec;
import com.foreverspark.logicsim.display.DisplayFramebuffer;
import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.ExternalDeviceState;
import com.foreverspark.logicsim.editor.model.ExternalDeviceType;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.runtime.CircuitCompiler;
import com.foreverspark.logicsim.interconnect.CircuitProgram;
import com.foreverspark.logicsim.interconnect.CircuitProgramRuntime;

import java.util.Map;

/** Regression test for the real compiled BOARD -> physical DISPLAY DATA64 -> framebuffer path. */
public final class DisplayPipelineSelfTest {
    private DisplayPipelineSelfTest() {}

    public static void main(String[] args) {
        testCompiledBoardProducesPixelCommand();
        testPhysicalInputDefaultsPersist();
        System.out.println("Display compiled DATA64 pipeline self-test: PASS");
    }

    private static void testCompiledBoardProducesPixelCommand() {
        CircuitDocument board = new CircuitDocument();
        long expected = DisplayCommandCodec.pixel(1, 1, 0xFFFF);

        EditorNode data = constant(board, 64, expected, 0);
        EditorNode display = board.addNode(NodeKind.EXTERNAL_DEVICE, 180, 60);
        display.configureExternalDevice(
                ExternalDeviceType.DISPLAY,
                "display-pipeline-test",
                ExternalDeviceState.CONNECTED,
                "test",
                0,
                0,
                0
        );
        board.connect(data.id, 0, display.id, 0);

        var compiled = CircuitCompiler.compile(board, name -> null);
        long actual = compiled.inputUnsigned(display.id, 0);
        check(actual == expected,
                "compiled physical DISPLAY DATA64 mismatch: expected " + DisplayCommandCodec.hex(expected)
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

    private static void testPhysicalInputDefaultsPersist() {
        CircuitDocument board = new CircuitDocument();
        EditorNode input = board.addNode(NodeKind.INPUT, 0, 0);
        input.width = 1;
        input.label = "TEST_IN";
        input.inputDefaultValue = 1L;
        EditorNode output = board.addNode(NodeKind.OUTPUT, 120, 0);
        output.width = 1;
        output.label = "TEST_OUT";
        board.connect(input.id, 0, output.id, 0);

        CircuitProgram saved = new CircuitProgram(new ChipDefinition("INPUT_TEST", board), Map.of());
        CircuitProgramRuntime runtime = new CircuitProgramRuntime(CircuitProgram.fromJson(saved.toJson()));
        check(runtime.outputValue("TEST_OUT") == 1L, "saved ON input remains ON after program reload");
        runtime.driveInput("TEST_IN", 0L);
        check(runtime.outputValue("TEST_OUT") == 0L, "real external input can override the saved manual default");
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
