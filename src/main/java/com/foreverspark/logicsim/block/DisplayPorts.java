package com.foreverspark.logicsim.block;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/** Physical port layout relative to the monitor's front/pixel face. */
public final class DisplayPorts {
    public enum Port { X, Y, COLOR, WRITE, CLEAR, NONE }

    private DisplayPorts() {}

    public static Direction front(BlockState state) {
        return state.hasProperty(DisplayBlock.FACING) ? state.getValue(DisplayBlock.FACING) : Direction.NORTH;
    }

    public static Direction left(BlockState state) {
        return left(front(state));
    }

    public static Direction right(BlockState state) {
        return left(state).getOpposite();
    }

    public static Direction back(BlockState state) {
        return front(state).getOpposite();
    }

    public static Port portAt(BlockState state, Direction face) {
        if (face == front(state)) return Port.NONE;
        if (face == back(state)) return Port.COLOR;
        if (face == left(state)) return Port.X;
        if (face == right(state)) return Port.Y;
        if (face == Direction.UP) return Port.WRITE;
        if (face == Direction.DOWN) return Port.CLEAR;
        return Port.NONE;
    }

    public static int widthAt(BlockState state, Direction face) {
        return switch (portAt(state, face)) {
            case X, Y, COLOR -> 16;
            case WRITE, CLEAR -> 1;
            case NONE -> 0;
        };
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
