package com.foreverspark.logicsim.display;

import com.foreverspark.logicsim.editor.model.PortDirection;
import com.foreverspark.logicsim.editor.model.PortSpec;
import com.foreverspark.logicsim.interconnect.InterconnectDevice;

import java.util.List;

/** Stable typed hardware contract exposed by every pixel-addressable display controller. */
public final class DisplayPortLayout {
    public static final int X = 0;
    public static final int Y = 1;
    public static final int COLOR = 2;
    public static final int WRITE = 3;
    public static final int CLEAR = 4;

    public static final List<PortSpec> INPUTS = List.of(
            new PortSpec("X", PortDirection.INPUT, 16),
            new PortSpec("Y", PortDirection.INPUT, 16),
            new PortSpec("COLOR", PortDirection.INPUT, 16),
            new PortSpec("WRITE", PortDirection.INPUT, 1),
            new PortSpec("CLEAR", PortDirection.INPUT, 1)
    );

    private DisplayPortLayout() {}

    public static InterconnectDevice device(String id) {
        return new InterconnectDevice(id, INPUTS, List.of());
    }
}
