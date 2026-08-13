package com.foreverspark.logicsim.client.device;

import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.ChipVisualSettings;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;

/** Built-in peripherals exposed through the same port model as reusable chips. */
public final class BuiltinDevices {
    public static final String DISPLAY = "DISPLAY";
    public static final int DISPLAY_WIDTH = 32;
    public static final int DISPLAY_HEIGHT = 18;
    private static final ChipDefinition DISPLAY_DEFINITION = makeDisplay();

    private BuiltinDevices() {}

    public static ChipDefinition find(String name) {
        if (name != null && DISPLAY.equalsIgnoreCase(name.trim())) return DISPLAY_DEFINITION;
        return null;
    }

    private static ChipDefinition makeDisplay() {
        CircuitDocument circuit = new CircuitDocument();
        addInput(circuit, "X", 16, 0);
        addInput(circuit, "Y", 16, 36);
        addInput(circuit, "COLOR", 16, 72);
        addInput(circuit, "WRITE", 1, 108);
        addInput(circuit, "CLEAR", 1, 144);
        ChipVisualSettings visual = new ChipVisualSettings(180.0, 120.0, 18.0);
        ChipDefinition definition = new ChipDefinition(DISPLAY, circuit, visual);
        definition.color = 0xFF34495E;
        return definition;
    }

    private static void addInput(CircuitDocument circuit, String label, int width, double y) {
        EditorNode node = circuit.addNode(NodeKind.INPUT, 0, y);
        node.label = label;
        node.width = width;
    }
}
