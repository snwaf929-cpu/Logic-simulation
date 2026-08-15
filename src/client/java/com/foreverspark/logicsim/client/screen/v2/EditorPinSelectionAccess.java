package com.foreverspark.logicsim.client.screen.v2;

/** Implemented onto CircuitCanvasWidget by the Editor V2 selection mixin. */
public interface EditorPinSelectionAccess {
    boolean logic$hasPinSelection();
    boolean logic$batchConnectSelectedPins();
}
