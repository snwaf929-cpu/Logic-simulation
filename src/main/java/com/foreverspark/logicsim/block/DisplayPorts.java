package com.foreverspark.logicsim.block;

import com.foreverspark.logicsim.interconnect.CableKind;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/** Physical display-wall data port. One 64-bit bus on any non-front face controls the whole wall. */
public final class DisplayPorts {
    public enum Port { DATA, NONE }

    private DisplayPorts() {}

    public static Direction front(BlockState state) {
        return state.hasProperty(DisplayBlock.FACING) ? state.getValue(DisplayBlock.FACING) : Direction.NORTH;
    }

    public static Direction left(BlockState state) { return left(front(state)); }
    public static Direction right(BlockState state) { return left(state).getOpposite(); }
    public static Direction back(BlockState state) { return front(state).getOpposite(); }

    public static Port portAt(BlockState state, Direction face) {
        return face == front(state) ? Port.NONE : Port.DATA;
    }

    public static int widthAt(BlockState state, Direction face) {
        return portAt(state, face) == Port.DATA ? 64 : 0;
    }

    public static boolean accepts(BlockState state, Direction face, CableKind kind, int width) {
        return portAt(state, face) == Port.DATA && kind == CableKind.BUS && width == 64;
    }

    private static Direction left(Direction front) {
        return switch (front) {
            case NORTH -> Direction.WEST;
            case SOUTH -> Direction.EAST;
            case WEST -> Direction.SOUTH;
            case EAST -> Direction.NORTH;
            default -> Direction.WEST;
        };
    }
}
