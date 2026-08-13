package com.foreverspark.logicsim.interconnect;

import com.foreverspark.logicsim.editor.model.PortDirection;
import com.foreverspark.logicsim.editor.model.PortSpec;

/** Immutable binding between one world socket and one named device port. */
public record PhysicalPortBinding(String portName, PortDirection direction, int width) {
    public PhysicalPortBinding {
        if (portName == null || portName.isBlank()) throw new IllegalArgumentException("Port name is required");
        if (direction == null) throw new IllegalArgumentException("Port direction is required");
        cableKindFor(width).validateWidth(width);
    }

    public PhysicalPortBinding(PortSpec port) {
        this(port.name(), port.direction(), port.width());
    }

    public CableKind cableKind() {
        return cableKindFor(width);
    }

    public boolean accepts(CableKind kind, int cableWidth) {
        return kind == cableKind() && cableWidth == width;
    }

    private static CableKind cableKindFor(int width) {
        return width == 1 ? CableKind.SIGNAL : CableKind.BUS;
    }
}
