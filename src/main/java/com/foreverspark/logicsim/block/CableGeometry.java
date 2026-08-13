package com.foreverspark.logicsim.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

final class CableGeometry {
    private static final VoxelShape[] SIGNAL = build(false);
    private static final VoxelShape[] BUS = build(true);

    private CableGeometry() {}

    static VoxelShape shape(BlockState state, boolean bus) {
        return (bus ? BUS : SIGNAL)[index(state)];
    }

    private static int index(BlockState state) {
        int value = 0;
        if (state.getValue(CableBlock.NORTH)) value |= 1;
        if (state.getValue(CableBlock.SOUTH)) value |= 2;
        if (state.getValue(CableBlock.WEST)) value |= 4;
        if (state.getValue(CableBlock.EAST)) value |= 8;
        if (state.getValue(CableBlock.UP)) value |= 16;
        if (state.getValue(CableBlock.DOWN)) value |= 32;
        return value;
    }

    private static VoxelShape[] build(boolean bus) {
        VoxelShape[] result = new VoxelShape[64];
        VoxelShape core = bus ? Block.box(5, 5, 5, 11, 11, 11) : Block.box(6, 6, 6, 10, 10, 10);
        VoxelShape[] arms = bus
                ? new VoxelShape[]{
                    Block.box(6, 6, 0, 10, 10, 8), Block.box(6, 6, 8, 10, 10, 16),
                    Block.box(0, 6, 6, 8, 10, 10), Block.box(8, 6, 6, 16, 10, 10),
                    Block.box(6, 8, 6, 10, 16, 10), Block.box(6, 0, 6, 10, 8, 10)
                }
                : new VoxelShape[]{
                    Block.box(7, 7, 0, 9, 9, 8), Block.box(7, 7, 8, 9, 9, 16),
                    Block.box(0, 7, 7, 8, 9, 9), Block.box(8, 7, 7, 16, 9, 9),
                    Block.box(7, 8, 7, 9, 16, 9), Block.box(7, 0, 7, 9, 8, 9)
                };
        for (int mask = 0; mask < 64; mask++) {
            VoxelShape shape = core;
            for (int bit = 0; bit < 6; bit++) if ((mask & (1 << bit)) != 0) shape = Shapes.or(shape, arms[bit]);
            result[mask] = shape;
        }
        return result;
    }
}
