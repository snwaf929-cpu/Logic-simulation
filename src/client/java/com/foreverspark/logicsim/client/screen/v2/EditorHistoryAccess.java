package com.foreverspark.logicsim.client.screen.v2;

/** Implemented onto CircuitCanvasWidget by the Editor V2 interaction mixin. */
public interface EditorHistoryAccess {
    void logic$checkpoint(String label);
    boolean logic$undo();
    boolean logic$redo();
}
