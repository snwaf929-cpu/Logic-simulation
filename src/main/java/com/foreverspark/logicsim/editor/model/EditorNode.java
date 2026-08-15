package com.foreverspark.logicsim.editor.model;

public final class EditorNode {
    public int id;
    public NodeKind kind;
    public double x;
    public double y;
    public int width = 1;
    /**
     * SPLITTER/MERGER lane width. Example: width=64, laneWidth=32 means two 32-bit lanes.
     * Legacy boards omit this field and therefore keep the original 1-bit-per-lane behavior.
     */
    public int laneWidth = 1;
    public String label = "";
    public String chipName = "";
    public long constantValue = 0L;
    /** Saved manual/default value for root INPUT nodes in a physical Circuit Block. */
    public long inputDefaultValue = 0L;
    /** Infrastructure source subtype: CONSTANT=false, virtual CLOCK=true. */
    public boolean clockSource = false;
    public long clockFrequencyHz = 1_000_000L;
    /** Infrastructure source subtype: edge-triggered RANDOM source. */
    public boolean randomSource = false;
    /** Probability that a RANDOM source emits HIGH on each 0 -> 1 trigger edge. */
    public int randomChancePercent = 50;

    public EditorNode() {}

    public EditorNode(int id, NodeKind kind, double x, double y) {
        this.id = id;
        this.kind = kind;
        this.x = x;
        this.y = y;
        if (kind == NodeKind.SPLITTER || kind == NodeKind.MERGER || kind == NodeKind.BUS || kind == NodeKind.PROBE) this.width = 8;
    }

    public int laneCount() {
        int lane = normalizedLaneWidth();
        return Math.max(1, width / lane);
    }

    public int normalizedLaneWidth() {
        if (kind != NodeKind.SPLITTER && kind != NodeKind.MERGER) return 1;
        int lane = laneWidth;
        if (lane <= 0 || lane > width || width % lane != 0 || (lane & (lane - 1)) != 0) return 1;
        return lane;
    }

    public String laneSummary() {
        if (kind != NodeKind.SPLITTER && kind != NodeKind.MERGER) return width + " bit";
        int lane = normalizedLaneWidth();
        return width + " bit = " + laneCount() + " x " + lane + " bit";
    }

    public String displayName() {
        if (kind == NodeKind.CUSTOM_CHIP && chipName != null && !chipName.isBlank()) return chipName;
        if (randomSource && kind == NodeKind.CONSTANT) return "RANDOM " + randomChancePercent + "%";
        if (clockSource && kind == NodeKind.CONSTANT) return "CLOCK " + formatFrequency(clockFrequencyHz);
        if (label != null && !label.isBlank()) return label;
        return switch (kind) {
            case INPUT -> "INPUT " + id;
            case OUTPUT -> "OUTPUT " + id;
            case NAND -> "NAND";
            case CONSTANT -> "CONSTANT " + width;
            case PROBE -> "PROBE " + width;
            case BUS -> "BUS " + width;
            case SPLITTER -> "SPLIT " + width + " -> " + laneCount() + "x" + normalizedLaneWidth();
            case MERGER -> "MERGE " + laneCount() + "x" + normalizedLaneWidth() + " -> " + width;
            case CUSTOM_CHIP -> "CUSTOM CHIP";
        };
    }

    public static String formatFrequency(long hz) {
        if (hz >= 1_000_000L && hz % 1_000_000L == 0L) return (hz / 1_000_000L) + " MHz";
        if (hz >= 1_000L && hz % 1_000L == 0L) return (hz / 1_000L) + " kHz";
        return hz + " Hz";
    }
}
