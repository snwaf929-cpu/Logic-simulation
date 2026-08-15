package com.foreverspark.logicsim.client.screen.v2;

import com.foreverspark.logicsim.editor.model.WireConnection;

import java.util.List;

/** Multi-wire selection state shared with PCB rendering and editor commands. */
public interface EditorWireSelectionAccess {
    boolean logic$isWireSelected(WireConnection wire);
    List<WireConnection> logic$selectedWires();
}
