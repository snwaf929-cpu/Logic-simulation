package com.foreverspark.logicsim.tools;

import com.foreverspark.logicsim.display.DisplayCommandCodec;
import com.foreverspark.logicsim.display.DisplayFramebuffer;
import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.ExternalDeviceState;
import com.foreverspark.logicsim.editor.model.ExternalDeviceType;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.PortSpec;
import com.foreverspark.logicsim.editor.runtime.CircuitCompiler;
import com.foreverspark.logicsim.interconnect.CircuitProgram;
import com.foreverspark.logicsim.interconnect.CircuitProgramRuntime;

import java.util.List;
import java.util.Map;

/** Regression test for the V2.1A DISPLAY editor pins and the internal physical DATA64 framebuffer protocol. */
public final class DisplayPipelineSelfTest {
    private DisplayPipelineSelfTest() {}

    public static void main(String[] args) {
        testSplitDisplayPinsCompile();
        testPhysicalData64CodecStillWorks();
        testPhysicalInputDefaultsPersist();
        System.out.println("Display V2.1A split-pin + DATA64 physical pipeline self-test: PASS");
    }

    private static void testSplitDisplayPinsCompile() {
        List<PortSpec> ports = ExternalDeviceType.DISPLAY.inputs();
        check(ports.size() == 5, "DISPLAY must expose exactly five schematic inputs");
        checkPort(ports.get(0), "X", 16);
        checkPort(ports.get(1), "Y", 16);
        checkPort(ports.get(2), "COLOR", 16);
        checkPort(ports.get(3), "WRITE", 1);
        checkPort(ports.get(4), "RESET", 1);

        CircuitDocument board = new CircuitDocument();
        EditorNode x = constant(board, 16, 1, 0);
        EditorNode y = constant(board, 16, 1, 20);
        EditorNode color = constant(board, 16, 0xFFFF, 40);
        EditorNode write = constant(board, 1, 1, 60);
        EditorNode reset = constant(board, 1, 0, 80);

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
        board.connect(x.id, 0, display.id, 0);
        board.connect(y.id, 0, display.id, 1);
        board.connect(color.id, 0, display.id, 2);
        board.connect(write.id, 0, display.id, 3);
        board.connect(reset.id, 0, display.id, 4);

        var compiled = CircuitCompiler.compile(board, name -> null);
        check(compiled.inputUnsigned(display.id, 0) == 1L, "DISPLAY X[16] receives the compiled X value");
        check(compiled.inputUnsigned(display.id, 1) == 1L, "DISPLAY Y[16] receives the compiled Y value");
        check(compiled.inputUnsigned(display.id, 2) == 0xFFFFL, "DISPLAY COLOR[16] receives RGB565");
        check(compiled.inputUnsigned(display.id, 3) == 1L, "DISPLAY WRITE[1] receives the write strobe");
        check(compiled.inputUnsigned(display.id, 4) == 0L, "DISPLAY RESET[1] receives the reset strobe");
    }

    private static void testPhysicalData64CodecStillWorks() {
        long command = DisplayCommandCodec.pixel(1, 1, 0xFFFF);
        DisplayCommandCodec.Command decoded = DisplayCommandCodec.decode(command);
        check(decoded.isPixel(), "internal DATA64 command decodes as DRAW/PIXEL opcode");
        check(decoded.x() == 1 && decoded.y() == 1, "internal DATA64 preserves X/Y coordinates");
        check(decoded.rgb565() == 0xFFFF, "internal DATA64 preserves RGB565 color");

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

    private static void checkPort(PortSpec actual, String name, int width) {
        check(actual.name().equals(name) && actual.width() == width,
                "DISPLAY port must be " + name + "[" + width + "] but was " + actual.name() + "[" + actual.width() + "]");
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
