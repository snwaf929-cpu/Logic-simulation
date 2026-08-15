package com.foreverspark.logicsim.display;

import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.ChipVisualSettings;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;

/** Shared SCREEN OUTPUT definition used by both the editor and runtime tests. */
public final class ScreenOutputDeviceDefinition {
    public static final String ID = "DISPLAY";
    public static final String LABEL = "SCREEN OUTPUT";
    public static final int COLOR = 0xFF3E8FA0;

    private ScreenOutputDeviceDefinition() {}

    public static ChipVisualSettings visual() {
        // Five ports at 18-unit spacing need about 102 units of height. The previous 216x132 body
        // dominated small circuits and made the display symbol feel unrelated to the other chips.
        return new ChipVisualSettings(138.0, 102.0, 18.0);
    }

    /**
     * Packs friendly pixel controls into DATA[64]:
     *  0..15 COLOR RGB565, 16..31 X, 32..47 Y, 48 DRAW, 49 CLEAR.
     */
    public static ChipDefinition create() {
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

        ChipDefinition definition = new ChipDefinition(ID, circuit, visual());
        definition.color = COLOR;
        return definition;
    }

    private static EditorNode addInput(CircuitDocument circuit, String label, int width, double y) {
        EditorNode node = circuit.addNode(NodeKind.INPUT, 0, y);
        node.label = label;
        node.width = width;
        return node;
    }
}
