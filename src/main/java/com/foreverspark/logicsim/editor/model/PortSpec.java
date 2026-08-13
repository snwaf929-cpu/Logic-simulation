package com.foreverspark.logicsim.editor.model;

public record PortSpec(String name, PortDirection direction, int width) {
    public PortSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Port name cannot be blank");
        }
        if (width <= 0 || width > 64) {
            throw new IllegalArgumentException("Port width must be between 1 and 64");
        }
    }
}
