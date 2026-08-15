package com.foreverspark.logicsim.editor.model;

import java.util.ArrayList;
import java.util.List;

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
    /** Arbitrary named output ranges for BUS_SLICE. */
    public List<BusSliceOutput> slices = new ArrayList<>();

    /**
     * Phase 4 BOARD socket metadata. Electrically a socket remains an ordinary BUS routing node,
     * so this adds no primitive logic. The direction describes which side of a template boundary is public.
     */
    public boolean boardSocket = false;
    public String interfaceId = "";
    public PortDirection socketDirection = PortDirection.INPUT;
    public int interfaceOrder = 0;

    /** Non-zero only for nodes cloned from a reusable BOARD template instance. */
    public int templateInstanceId = 0;
    public String templateName = "";

    public EditorNode() {}

    public EditorNode(int id, NodeKind kind, double x, double y) {
        this.id = id;
        this.kind = kind;
        this.x = x;
        this.y = y;
        if (kind == NodeKind.SPLITTER || kind == NodeKind.MERGER || kind == NodeKind.BUS || kind == NodeKind.PROBE) this.width = 8;
        if (kind == NodeKind.BUS_SLICE) {
            this.width = 16;
            this.slices.add(new BusSliceOutput("HIGH", 8, 8));
            this.slices.add(new BusSliceOutput("LOW", 0, 8));
        }
        if (kind == NodeKind.NET_LABEL) {
            this.width = 1;
            // A fresh NET must not silently join every other untouched NET label. Duplicating an
            // existing NET still preserves its name, which is the intentional way to create aliases.
            this.label = "NET" + id;
        }
    }

    public boolean isBoardSocket() {
        return boardSocket && kind == NodeKind.BUS;
    }

    public void configureBoardSocket(String name, PortDirection direction, int order) {
        kind = NodeKind.BUS;
        boardSocket = true;
        label = name == null || name.isBlank() ? "SOCKET" + id : name.trim();
        socketDirection = direction == null ? PortDirection.INPUT : direction;
        interfaceOrder = Math.max(0, order);
        if (interfaceId == null || interfaceId.isBlank()) interfaceId = "socket-" + id;
    }

    public int laneCount() {
        int lane = normalizedLaneWidth();
        return Math.max(1, width / lane);
    }

    public int normalizedLaneWidth() {
        if (kind != NodeKind.SPLITTER && kind != NodeKind.MERGER) return 1;
        int lane = laneWidth;
        if (lane <= 0 || lane > width || width % lane != 0) return 1;
        return lane;
    }

    public String laneSummary() {
        if (kind != NodeKind.SPLITTER && kind != NodeKind.MERGER) return width + " bit";
        int lane = normalizedLaneWidth();
        return width + " bit = " + laneCount() + " x " + lane + " bit";
    }

    public List<BusSliceOutput> normalizedSlices() {
        if (kind != NodeKind.BUS_SLICE) return List.of();
        if (slices == null) slices = new ArrayList<>();
        if (slices.isEmpty()) slices.add(new BusSliceOutput("OUT0", 0, Math.min(width, 8)));
        for (int i = 0; i < slices.size(); i++) {
            BusSliceOutput slice = slices.get(i);
            if (slice == null) {
                slice = new BusSliceOutput("OUT" + i, 0, 1);
                slices.set(i, slice);
            }
            slice.normalize(width, i);
        }
        return List.copyOf(slices);
    }

    public String displayName() {
        if (isBoardSocket()) return "SOCKET " + ((label == null || label.isBlank()) ? interfaceId : label.trim());
        if (kind == NodeKind.CUSTOM_CHIP && chipName != null && !chipName.isBlank()) return chipName;
        if (randomSource && kind == NodeKind.CONSTANT) return "RANDOM " + randomChancePercent + "%";
        if (clockSource && kind == NodeKind.CONSTANT) return "CLOCK " + formatFrequency(clockFrequencyHz);
        if (kind == NodeKind.NET_LABEL) return "NET " + ((label == null || label.isBlank()) ? "UNNAMED" : label.trim());
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
            case BUS_SLICE -> "BUS SLICE " + width;
            case NET_LABEL -> "NET";
            case CUSTOM_CHIP -> "CUSTOM CHIP";
        };
    }

    public static String formatFrequency(long hz) {
        if (hz >= 1_000_000L && hz % 1_000_000L == 0L) return (hz / 1_000_000L) + " MHz";
        if (hz >= 1_000L && hz % 1_000L == 0L) return (hz / 1_000L) + " kHz";
        return hz + " Hz";
    }
}
