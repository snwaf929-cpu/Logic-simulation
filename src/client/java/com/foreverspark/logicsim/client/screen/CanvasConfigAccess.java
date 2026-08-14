package com.foreverspark.logicsim.client.screen;

import net.minecraft.client.gui.screens.Screen;

import java.util.List;

/**
 * Runtime-safe bridge implemented onto CircuitCanvasWidget by the source-config mixin.
 *
 * This interface intentionally lives outside the configured Mixin package. Classes inside a
 * declared mixin package are owned by Mixin and cannot be referenced as ordinary runtime types.
 */
public interface CanvasConfigAccess {
    boolean logic$editSelectedSources(Screen parent);

    CanvasSessionState logic$captureSessionState();

    void logic$restoreSessionState(CanvasSessionState state);

    record CanvasSessionState(double panX, double panY, double zoom, List<Integer> selectedNodeIds) {}
}
