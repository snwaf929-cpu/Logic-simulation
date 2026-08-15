package com.foreverspark.logicsim.editor.model;

import java.util.List;
import java.util.Locale;

/**
 * Physical peripherals that may appear on a BOARD schematic after world discovery.
 * These are endpoints, not logic primitives: CPU/GPU/RAM/ROM deliberately do not exist here.
 */
public enum ExternalDeviceType {
    DISPLAY(
            "DISPLAY",
            List.of(new PortSpec("DATA64", PortDirection.INPUT, 64)),
            List.of()
    ),
    UIB(
            "USER INPUT BRIDGE",
            List.of(new PortSpec("CONTROL", PortDirection.INPUT, 16)),
            List.of(
                    new PortSpec("KEYBOARD", PortDirection.OUTPUT, 64),
                    new PortSpec("MOUSE", PortDirection.OUTPUT, 64),
                    new PortSpec("IRQ", PortDirection.OUTPUT, 1)
            )
    ),
    INTERNET(
            "INTERNET",
            List.of(
                    new PortSpec("TX", PortDirection.INPUT, 64),
                    new PortSpec("CONTROL", PortDirection.INPUT, 16)
            ),
            List.of(
                    new PortSpec("RX", PortDirection.OUTPUT, 64),
                    new PortSpec("STATUS", PortDirection.OUTPUT, 16),
                    new PortSpec("IRQ", PortDirection.OUTPUT, 1)
            )
    ),
    STORAGE(
            "STORAGE",
            List.of(new PortSpec("COMMAND", PortDirection.INPUT, 64)),
            List.of(
                    new PortSpec("RESPONSE", PortDirection.OUTPUT, 64),
                    new PortSpec("IRQ", PortDirection.OUTPUT, 1)
            )
    );

    private final String label;
    private final List<PortSpec> inputs;
    private final List<PortSpec> outputs;

    ExternalDeviceType(String label, List<PortSpec> inputs, List<PortSpec> outputs) {
        this.label = label;
        this.inputs = List.copyOf(inputs);
        this.outputs = List.copyOf(outputs);
    }

    public String label() { return label; }
    public List<PortSpec> inputs() { return inputs; }
    public List<PortSpec> outputs() { return outputs; }

    public static ExternalDeviceType parse(String value) {
        if (value == null || value.isBlank()) return DISPLAY;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (ExternalDeviceType type : values()) {
            if (type.name().equals(normalized) || type.label.equalsIgnoreCase(value.trim())) return type;
        }
        throw new IllegalArgumentException("Unknown external device type: " + value);
    }
}
