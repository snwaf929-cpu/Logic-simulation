package com.foreverspark.logicsim.mixin.client;

import net.minecraft.client.gui.screens.Screen;

import java.util.List;

/** Small bridge used by editor-screen mixins without exposing implementation details publicly. */
public interface CanvasConfigAccess {
    boolean logic$editSelectedSources(Screen parent);

    CanvasSessionState logic$captureSessionState();

    void logic$restoreSessionState(CanvasSessionState state);

    record CanvasSessionState(double panX, double panY, double zoom, List<Integer> selectedNodeIds) {}
}
