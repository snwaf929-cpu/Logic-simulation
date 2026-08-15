package com.foreverspark.logicsim.editor.model;

public enum NodeKind {
    INPUT,
    OUTPUT,
    NAND,
    CONSTANT,
    PROBE,
    BUS,
    SPLITTER,
    MERGER,
    BUS_SLICE,
    NET_LABEL,
    CUSTOM_CHIP,
    /** Physical world endpoint discovered from the Circuit Block cable topology. */
    EXTERNAL_DEVICE
}
