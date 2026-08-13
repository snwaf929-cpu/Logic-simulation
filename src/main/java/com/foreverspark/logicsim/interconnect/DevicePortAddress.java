package com.foreverspark.logicsim.interconnect;

import com.foreverspark.logicsim.editor.model.PortDirection;

/** Stable address of one exposed device port in an interconnect graph. */
public record DevicePortAddress(String deviceId, PortDirection direction, int portIndex) {
    public DevicePortAddress {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("Device id cannot be blank");
        }
        if (direction == null) {
            throw new IllegalArgumentException("Port direction is required");
        }
        if (portIndex < 0) {
            throw new IllegalArgumentException("Port index cannot be negative");
        }
    }
}
