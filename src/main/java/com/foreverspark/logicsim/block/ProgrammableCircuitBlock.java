package com.foreverspark.logicsim.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class ProgrammableCircuitBlock extends BaseEntityBlock {
    public ProgrammableCircuitBlock(Properties properties) { super(properties); }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return simpleCodec(ProgrammableCircuitBlock::new); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new CircuitBlockEntity(pos, state); }
}
