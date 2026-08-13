package com.foreverspark.logicsim.block;

import com.foreverspark.logicsim.interconnect.CableKind;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Early physical cable block. The block already knows whether it represents a one-bit SIGNAL
 * or a multi-bit BUS; device-port binding and automatic topology discovery are the next layer.
 */
public final class CableBlock extends Block {
    private static final VoxelShape SIGNAL_SHAPE = Shapes.or(
            Block.box(6, 6, 6, 10, 10, 10),
            Block.box(0, 7, 7, 16, 9, 9),
            Block.box(7, 0, 7, 9, 16, 9),
            Block.box(7, 7, 0, 9, 9, 16)
    );

    private static final VoxelShape BUS_SHAPE = Shapes.or(
            Block.box(5, 5, 5, 11, 11, 11),
            Block.box(0, 6, 6, 16, 10, 10),
            Block.box(6, 0, 6, 10, 16, 10),
            Block.box(6, 6, 0, 10, 10, 16)
    );

    private final CableKind cableKind;

    public CableBlock(CableKind cableKind, BlockBehaviour.Properties properties) {
        super(properties);
        this.cableKind = cableKind;
    }

    public CableKind cableKind() {
        return cableKind;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return cableKind == CableKind.SIGNAL ? SIGNAL_SHAPE : BUS_SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getShape(state, level, pos, context);
    }
}
