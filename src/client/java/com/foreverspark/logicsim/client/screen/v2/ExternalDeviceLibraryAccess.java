package com.foreverspark.logicsim.client.screen.v2;

import com.foreverspark.logicsim.editor.model.ExternalDeviceDescriptor;

import java.util.List;

/** Live model supplied to the sidebar's DEVICES section. */
public interface ExternalDeviceLibraryAccess {
    void logic$setAvailableDevices(List<ExternalDeviceDescriptor> devices);
    void logic$setDeviceLibraryEnabled(boolean enabled);
}
