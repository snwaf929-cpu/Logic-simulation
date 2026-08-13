package com.foreverspark.logicsim.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Rebuilds only the cable arms that touch one I/O Connector after its binding changes. */
public final class CableVisualRefresh {
    private CableVisualRefresh() {}

    public static void aroundSocket(Level level, BlockPos socketPos, CircuitPortBlockEntity socket) {
        if (level == null || socketPos == null || socket == null) return;
        for (Direction fromSocket : Direction.values()) {
            BlockPos cablePos = socketPos.relative(fromSocket);
            BlockState state = level.getBlockState(cablePos);
            if (!(state.getBlock() instanceof CableBlock cable)) continue;
            Direction towardSocket = fromSocket.getOpposite();
            boolean connected = socket.accepts(cable);
            BlockState updated = state.setValue(CableBlock.connectionProperty(towardSocket), connected);
            if (!updated.equals(state)) level.setBlock(cablePos, updated, Block.UPDATE_ALL);
        }
    }
}
