package com.foreverspark.logicsim.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class CircuitPortBlockEntity extends BlockEntity {
    public CircuitPortBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CIRCUIT_PORT, pos, state);
    }
}
