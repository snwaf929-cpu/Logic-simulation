package com.foreverspark.logicsim.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Vanilla world-power fallback for an un-cabled one-bit input socket. */
public final class WorldPowerInput {
    private WorldPowerInput() {}

    public static boolean shouldDrive(CircuitPortBlockEntity socket, Level level, BlockPos pos) {
        if (socket == null || level == null || pos == null) return false;
        if (socket.direction() != com.foreverspark.logicsim.editor.model.PortDirection.INPUT || socket.width() != 1) return false;
        for (Direction direction : Direction.values()) {
            BlockState neighbor = level.getBlockState(pos.relative(direction));
            if (neighbor.getBlock() instanceof CableBlock cable && cable.bitWidth() == 1) return false;
        }
        return true;
    }

    public static long value(Level level, BlockPos pos) {
        return level.hasNeighborSignal(pos) ? 1L : 0L;
    }
}
