package com.foreverspark.logicsim.client.screen.v2;

import com.foreverspark.logicsim.editor.model.WireLayer;

/** Canvas bridge for PCB front/back layer controls. */
public interface PcbLayerAccess {
    WireLayer logic$currentPcbLayer();
    void logic$flipPcbBoardSide();
    boolean logic$assignSelectedWireToCurrentLayer();
    boolean logic$toggleViaOnSelectedWire();
}
