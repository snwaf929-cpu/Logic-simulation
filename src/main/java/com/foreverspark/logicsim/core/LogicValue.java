package com.foreverspark.logicsim.core;

/** Three-state digital logic used by the accurate simulator. */
public enum LogicValue {
    LOW,
    HIGH,
    UNKNOWN;

    public static LogicValue nand(LogicValue a, LogicValue b) {
        if (a == LOW || b == LOW) {
            return HIGH;
        }
        if (a == HIGH && b == HIGH) {
            return LOW;
        }
        return UNKNOWN;
    }

    public static LogicValue fromBoolean(boolean value) {
        return value ? HIGH : LOW;
    }

    public boolean asBoolean() {
        return switch (this) {
            case LOW -> false;
            case HIGH -> true;
            case UNKNOWN -> throw new IllegalStateException("UNKNOWN has no boolean value");
        };
    }
}
