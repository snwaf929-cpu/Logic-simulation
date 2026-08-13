package com.foreverspark.logicsim.client;

import net.minecraft.core.BlockPos;

public final class ClientCircuitTarget {
    private static BlockPos target;

    private ClientCircuitTarget() {}

    public static void set(BlockPos pos) {
        target = pos == null ? null : pos.immutable();
    }

    public static BlockPos get() {
        return target;
    }

    public static void clear() {
        target = null;
    }
}
