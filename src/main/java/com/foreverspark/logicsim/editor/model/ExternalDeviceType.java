package com.foreverspark.logicsim.editor.model;

import java.util.List;

/** Physical devices that may be discovered from a circuit board's connected cable graph. */
public enum ExternalDeviceType {
    DISPLAY(
            "DISPLAY",
            List.of(
                    new PortSpec("X", PortDirection.INPUT, 16),
                    new PortSpec("Y", PortDirection.INPUT, 16),
                    new PortSpec("COLOR", PortDirection.INPUT, 16),
                    new PortSpec("WRITE", PortDirection.INPUT, 1),
                    new PortSpec("RESET", PortDirection.INPUT, 1)
            ),
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

    public static ExternalDeviceType fromId(String id) {
        if (id == null || id.isBlank()) return DISPLAY;
        String normalized = id.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized.equals("USER_INPUT") || normalized.equals("USER_INPUT_BRIDGE")) return UIB;
        for (ExternalDeviceType type : values()) {
            if (type.name().equals(normalized)) return type;
        }
        return DISPLAY;
    }
}
