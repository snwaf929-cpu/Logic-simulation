package com.foreverspark.logicsim.interconnect;

import com.foreverspark.logicsim.block.CableBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;

public final class CableRun {
    private CableRun() {}

    public static Set<BlockPos> collect(BlockGetter level, BlockPos start, int maxSegments) {
        if (level == null || start == null) return Set.of();
        if (maxSegments <= 0) throw new IllegalArgumentException("maxSegments must be positive");

        BlockState startState = level.getBlockState(start);
        if (!(startState.getBlock() instanceof CableBlock startCable)) return Set.of();

        LinkedHashSet<BlockPos> visited = new LinkedHashSet<>();
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        pending.add(start.immutable());
        while (!pending.isEmpty() && visited.size() < maxSegments) {
            BlockPos pos = pending.removeFirst();
            if (!visited.add(pos)) continue;
            for (Direction direction : Direction.values()) {
                BlockPos next = pos.relative(direction);
                if (visited.contains(next)) continue;
                BlockState state = level.getBlockState(next);
                if (state.getBlock() instanceof CableBlock cable && startCable.compatibleWith(cable)) {
                    pending.addLast(next.immutable());
                }
            }
        }
        return Set.copyOf(visited);
    }
}
