package com.foreverspark.logicsim.interconnect;

/** Validated directed connection from one output port to one input port. */
public record CableConnection(
        DevicePortAddress source,
        DevicePortAddress target,
        CableKind kind,
        int width
) {
    public CableConnection {
        if (source == null || target == null || kind == null) {
            throw new IllegalArgumentException("Cable endpoints and kind are required");
        }
        kind.validateWidth(width);
    }
}
