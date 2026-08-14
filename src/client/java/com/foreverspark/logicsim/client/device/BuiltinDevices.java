package com.foreverspark.logicsim.client.device;

import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.ChipVisualSettings;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;

/** Built-in peripherals exposed through the same port model as reusable chips. */
public final class BuiltinDevices {
    /** Internal stable ID kept as DISPLAY so older editor documents continue to load. */
    public static final String DISPLAY = "DISPLAY";
    public static final String DISPLAY_LABEL = "SCREEN OUTPUT";
    public static final int DISPLAY_COLOR = 0xFF3E8FA0;

    private static final ChipDefinition DISPLAY_DEFINITION = makeDisplayOutput();

    private BuiltinDevices() {}

    public static boolean isDisplay(String name) {
        return name != null && DISPLAY.equalsIgnoreCase(name.trim());
    }

    /** Read-only definition used by the live editor/compiler. */
    public static ChipDefinition find(String name) {
        return isDisplay(name) ? DISPLAY_DEFINITION : null;
    }

    /** Fresh copy used when the built-in is embedded into a CircuitProgram dependency graph. */
    public static ChipDefinition copy(String name) {
        return isDisplay(name) ? makeDisplayOutput() : null;
    }

    public static ChipVisualSettings displayVisual() {
        return new ChipVisualSettings(216.0, 132.0, 18.0);
    }

    /**
     * SCREEN OUTPUT converts easy-to-understand pixel controls into the physical screen's
     * fixed 64-bit DATA protocol:
     *   bits  0..15  COLOR RGB565
     *   bits 16..31  X pixel coordinate
     *   bits 32..47  Y pixel coordinate
     *   bit      48  DRAW (opcode 1)
     *   bit      49  CLEAR (opcode 2)
     *   bits 50..63  0
     *
     * DRAW and CLEAR should not be high together. Pull DRAW low then high again to resend
     * the exact same pixel command.
     */
    private static ChipDefinition makeDisplayOutput() {
        CircuitDocument circuit = new CircuitDocument();

        EditorNode x = addInput(circuit, "X", 16, 0);
        EditorNode y = addInput(circuit, "Y", 16, 36);
        EditorNode color = addInput(circuit, "COLOR", 16, 72);
        EditorNode draw = addInput(circuit, "DRAW", 1, 108);
        EditorNode clear = addInput(circuit, "CLEAR", 1, 144);

        EditorNode splitX = circuit.addNode(NodeKind.SPLITTER, 90, 0);
        splitX.width = 16;
        EditorNode splitY = circuit.addNode(NodeKind.SPLITTER, 90, 36);
        splitY.width = 16;
        EditorNode splitColor = circuit.addNode(NodeKind.SPLITTER, 90, 72);
        splitColor.width = 16;
        EditorNode merge = circuit.addNode(NodeKind.MERGER, 210, 54);
        merge.width = 64;
        EditorNode data = circuit.addNode(NodeKind.OUTPUT, 360, 54);
        data.width = 64;
        data.label = "DATA";

        circuit.connect(x.id, 0, splitX.id, 0);
        circuit.connect(y.id, 0, splitY.id, 0);
        circuit.connect(color.id, 0, splitColor.id, 0);

        for (int bit = 0; bit < 16; bit++) {
            circuit.connect(splitColor.id, bit, merge.id, bit);
            circuit.connect(splitX.id, bit, merge.id, 16 + bit);
            circuit.connect(splitY.id, bit, merge.id, 32 + bit);
        }
        circuit.connect(draw.id, 0, merge.id, 48);
        circuit.connect(clear.id, 0, merge.id, 49);
        circuit.connect(merge.id, 0, data.id, 0);

        ChipDefinition definition = new ChipDefinition(DISPLAY, circuit, displayVisual());
        definition.color = DISPLAY_COLOR;
        return definition;
    }

    private static EditorNode addInput(CircuitDocument circuit, String label, int width, double y) {
        EditorNode node = circuit.addNode(NodeKind.INPUT, 0, y);
        node.label = label;
        node.width = width;
        return node;
    }
}
