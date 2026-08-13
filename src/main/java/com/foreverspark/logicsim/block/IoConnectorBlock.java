package com.foreverspark.logicsim.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Compact physical socket used between a device port and a typed cable. */
public final class IoConnectorBlock extends Block {
    private static final VoxelShape SHAPE = box(3, 3, 3, 13, 13, 13);

    public IoConnectorBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }
}
