package com.foreverspark.logicsim.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public final class ProgrammableCircuitBlock extends BaseEntityBlock {
    public ProgrammableCircuitBlock(Properties properties) { super(properties); }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return simpleCodec(ProgrammableCircuitBlock::new); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new CircuitBlockEntity(pos, state); }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        // This ticker only maintains lifecycle/world I/O. Circuit clocks run in CircuitSimulationWorker.
        return createTickerHelper(type, ModBlockEntities.CIRCUIT, CircuitBlockEntity::tick);
    }
}
