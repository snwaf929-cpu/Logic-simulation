package com.foreverspark.logicsim.interconnect;

import com.foreverspark.logicsim.block.CableBlock;
import com.foreverspark.logicsim.block.DisplayBlock;
import com.foreverspark.logicsim.block.IoConnectorBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class CableAttachments {
    private CableAttachments() {}

    public static List<Attachment> find(BlockGetter level, Set<BlockPos> cableRun) {
        ArrayList<Attachment> result = new ArrayList<>();
        for (BlockPos cablePos : cableRun) {
            BlockState cableState = level.getBlockState(cablePos);
            if (!(cableState.getBlock() instanceof CableBlock cable)) continue;
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = cablePos.relative(direction);
                if (cableRun.contains(neighbor)) continue;
                BlockState state = level.getBlockState(neighbor);
                Direction deviceFace = direction.getOpposite();
                if (state.getBlock() instanceof IoConnectorBlock) {
                    result.add(new Attachment(cablePos, neighbor.immutable(), deviceFace, Kind.CIRCUIT_SOCKET));
                } else if (state.getBlock() instanceof DisplayBlock && displayWidth(deviceFace) == cable.bitWidth()) {
                    result.add(new Attachment(cablePos, neighbor.immutable(), deviceFace, Kind.DISPLAY));
                }
            }
        }
        return List.copyOf(result);
    }

    private static int displayWidth(Direction face) {
        return switch (face) {
            case SOUTH, WEST, EAST -> 16;
            case UP, DOWN -> 1;
            case NORTH -> 0;
        };
    }

    public enum Kind { CIRCUIT_SOCKET, DISPLAY }
    public record Attachment(BlockPos cablePos, BlockPos devicePos, Direction deviceFace, Kind kind) {}
}
