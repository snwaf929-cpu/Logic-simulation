package com.foreverspark.logicsim.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class ExternalPortBlockEntity extends BlockEntity {
    public ExternalPortBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.EXTERNAL_PORT, pos, state);
    }
}
