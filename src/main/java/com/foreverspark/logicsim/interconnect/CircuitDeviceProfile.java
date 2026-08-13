package com.foreverspark.logicsim.interconnect;

import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.PortSpec;

import java.util.List;

/** Converts the named reusable-chip boundary into the exact ports exposed by a world Circuit Block. */
public final class CircuitDeviceProfile {
    private CircuitDeviceProfile() {}

    public static InterconnectDevice fromChip(String deviceId, ChipDefinition chip) {
        if (chip == null) throw new IllegalArgumentException("Chip definition is required");
        chip.normalize();
        List<PortSpec> inputs = chip.inputPorts();
        List<PortSpec> outputs = chip.outputPorts();
        return new InterconnectDevice(deviceId, inputs, outputs);
    }
}
