package com.foreverspark.logicsim.editor.model;

/** Network/editor snapshot of one currently discovered physical endpoint. */
public record ExternalDeviceDescriptor(
        String deviceId,
        ExternalDeviceType type,
        String world,
        int x,
        int y,
        int z
) {
    public ExternalDeviceDescriptor {
        if (deviceId == null || deviceId.isBlank()) throw new IllegalArgumentException("Device id is required");
        type = type == null ? ExternalDeviceType.DISPLAY : type;
        world = world == null ? "" : world;
        deviceId = deviceId.trim();
    }
}
