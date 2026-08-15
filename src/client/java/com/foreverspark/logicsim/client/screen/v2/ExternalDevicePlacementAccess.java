package com.foreverspark.logicsim.client.screen.v2;

import com.foreverspark.logicsim.editor.model.ExternalDeviceDescriptor;

/** Explicit BOARD placement of a discovered physical peripheral. */
public interface ExternalDevicePlacementAccess {
    boolean logic$beginExternalDevicePlacement(ExternalDeviceDescriptor descriptor);
    boolean logic$externalDevicePlacementPending();
}
