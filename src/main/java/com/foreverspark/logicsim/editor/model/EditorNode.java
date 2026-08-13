package com.foreverspark.logicsim.editor.model;

public final class EditorNode {
    public int id;
    public NodeKind kind;
    public double x;
    public double y;
    public int width = 1;
    public String label = "";
    public String chipName = "";
    public long constantValue = 0L;

    public EditorNode() {}

    public EditorNode(int id, NodeKind kind, double x, double y) {
        this.id = id;
        this.kind = kind;
        this.x = x;
        this.y = y;
        if (kind == NodeKind.SPLITTER || kind == NodeKind.MERGER || kind == NodeKind.BUS || kind == NodeKind.PROBE) this.width = 8;
        if (kind == NodeKind.CLOCK) this.width = 1;
    }

    public String displayName() {
        if (kind == NodeKind.CUSTOM_CHIP && chipName != null && !chipName.isBlank()) return chipName;
        if (label != null && !label.isBlank()) return label;
        return switch (kind) {
            case INPUT -> "INPUT " + id;
            case OUTPUT -> "OUTPUT " + id;
            case NAND -> "NAND";
            case CONSTANT -> "CONSTANT " + width;
            case CLOCK -> "CLOCK";
            case PROBE -> "PROBE " + width;
            case BUS -> "BUS " + width;
            case SPLITTER -> "SPLITTER " + width;
            case MERGER -> "MERGER " + width;
            case CUSTOM_CHIP -> "CUSTOM CHIP";
        };
    }
}
