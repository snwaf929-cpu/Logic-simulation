package com.foreverspark.logicsim.interconnect;

import com.foreverspark.logicsim.editor.model.PortDirection;
import com.foreverspark.logicsim.editor.model.PortSpec;

import java.util.List;

/** Exposed typed ports of a world-space logic device. */
public record InterconnectDevice(String id, List<PortSpec> inputs, List<PortSpec> outputs) {
    public InterconnectDevice {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Device id cannot be blank");
        }
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        for (PortSpec port : inputs) {
            if (port.direction() != PortDirection.INPUT) {
                throw new IllegalArgumentException("Input list contains a non-input port: " + port.name());
            }
        }
        for (PortSpec port : outputs) {
            if (port.direction() != PortDirection.OUTPUT) {
                throw new IllegalArgumentException("Output list contains a non-output port: " + port.name());
            }
        }
    }

    public PortSpec port(DevicePortAddress address) {
        if (!id.equals(address.deviceId())) {
            throw new IllegalArgumentException("Port belongs to another device: " + address.deviceId());
        }
        List<PortSpec> ports = address.direction() == PortDirection.INPUT ? inputs : outputs;
        if (address.portIndex() >= ports.size()) {
            throw new IllegalArgumentException("Port index out of range for " + id + ": " + address.portIndex());
        }
        return ports.get(address.portIndex());
    }
}
