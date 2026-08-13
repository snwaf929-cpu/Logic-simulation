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
    private final int bitWidth;

    public CableBlock(CableKind cableKind, int bitWidth, BlockBehaviour.Properties properties) {
        super(properties);
        cableKind.validateWidth(bitWidth);
        this.cableKind = cableKind;
        this.bitWidth = bitWidth;
    }

    public CableKind cableKind() { return cableKind; }
    public int bitWidth() { return bitWidth; }
    public boolean compatibleWith(CableBlock other) {
        return other != null && cableKind == other.cableKind && bitWidth == other.bitWidth;
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
