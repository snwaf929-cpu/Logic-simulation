package com.foreverspark.logicsim.editor.model;

import java.util.Locale;

/** Stable external interface exported by a reusable BOARD template. */
public record BoardSocketSpec(
        String interfaceId,
        String name,
        PortDirection direction,
        int width,
        int order,
        int nodeId
) {
    public BoardSocketSpec {
        interfaceId = interfaceId == null ? "" : interfaceId.trim();
        name = name == null ? "" : name.trim();
        direction = direction == null ? PortDirection.INPUT : direction;
        width = Math.max(1, Math.min(64, width));
        order = Math.max(0, order);
    }

    public String nameKey() {
        return name.toUpperCase(Locale.ROOT);
    }

    public boolean sameSignature(BoardSocketSpec other) {
        return other != null
                && direction == other.direction
                && width == other.width
                && name.equalsIgnoreCase(other.name);
    }

    public String signature() {
        return name + "  " + direction + "  [" + width + "]";
    }
}
