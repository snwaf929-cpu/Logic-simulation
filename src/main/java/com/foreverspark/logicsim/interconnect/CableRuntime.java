package com.foreverspark.logicsim.interconnect;

import com.foreverspark.logicsim.block.CableBlock;
import com.foreverspark.logicsim.block.DisplayBlock;
import com.foreverspark.logicsim.block.DisplayBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Event-driven world cable values. No cable BlockEntity and no dependence on Minecraft's 20 TPS. */
public final class CableRuntime {
    private static final int MAX_SEGMENTS = 8192;
    private static final Map<Level, Map<BlockPos, Long>> VALUES = new WeakHashMap<>();

    private CableRuntime() {}

    public static synchronized void setValue(Level level, BlockPos start, long value) {
        if (level == null || start == null) return;
        BlockState state = level.getBlockState(start);
        if (!(state.getBlock() instanceof CableBlock cable)) return;

        long normalized = value & mask(cable.bitWidth());
        Set<BlockPos> run = CableRun.collect(level, start, MAX_SEGMENTS);
        Map<BlockPos, Long> values = VALUES.computeIfAbsent(level, ignored -> new HashMap<>());
        for (BlockPos pos : run) values.put(pos.immutable(), normalized);
        notifyDisplays(level, run, cable, normalized);
    }

    public static synchronized long value(Level level, BlockPos pos) {
        Map<BlockPos, Long> values = VALUES.get(level);
        return values == null ? 0L : values.getOrDefault(pos, 0L);
    }

    private static void notifyDisplays(Level level, Set<BlockPos> run, CableBlock cable, long value) {
        for (BlockPos cablePos : run) {
            for (Direction direction : Direction.values()) {
                BlockPos neighborPos = cablePos.relative(direction);
                if (run.contains(neighborPos)) continue;
                BlockState neighbor = level.getBlockState(neighborPos);
                if (!(neighbor.getBlock() instanceof DisplayBlock)) continue;

                Direction displayFace = direction.getOpposite();
                if (displayPortWidth(displayFace) != cable.bitWidth()) continue;
                if (level.getBlockEntity(neighborPos) instanceof DisplayBlockEntity display) {
                    display.acceptCableValue(displayFace, value);
                }
            }
        }
    }

    private static int displayPortWidth(Direction face) {
        return switch (face) {
            case SOUTH, WEST, EAST -> 16;
            case UP, DOWN -> 1;
            case NORTH -> 0;
        };
    }

    private static long mask(int width) {
        return width >= 64 ? -1L : (1L << width) - 1L;
    }
}
