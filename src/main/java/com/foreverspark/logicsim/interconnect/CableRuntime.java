package com.foreverspark.logicsim.interconnect;

import com.foreverspark.logicsim.block.CableBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

public final class CableRuntime {
    private static final int MAX_SEGMENTS = 8192;
    private static final Map<Level, Map<BlockPos, Long>> VALUES = new WeakHashMap<>();

    private CableRuntime() {}

    public static synchronized void setValue(Level level, BlockPos start, long value) {
        if (level == null || start == null) return;
        BlockState state = level.getBlockState(start);
        if (!(state.getBlock() instanceof CableBlock cable)) return;
        long normalized = value & mask(cable.bitWidth());
        Map<BlockPos, Long> values = VALUES.computeIfAbsent(level, ignored -> new HashMap<>());
        for (BlockPos pos : CableRun.collect(level, start, MAX_SEGMENTS)) values.put(pos.immutable(), normalized);
    }

    public static synchronized long value(Level level, BlockPos pos) {
        Map<BlockPos, Long> values = VALUES.get(level);
        return values == null ? 0L : values.getOrDefault(pos, 0L);
    }

    private static long mask(int width) {
        return width >= 64 ? -1L : (1L << width) - 1L;
    }
}
