package com.foreverspark.logicsim.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import org.jetbrains.annotations.Nullable;

public final class DisplayBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public enum PortRole { FRONT, X, Y, COLOR, WRITE, CLEAR }

    public DisplayBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(DisplayBlock::new);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DisplayBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, ModBlockEntities.DISPLAY, DisplayBlockEntity::tick);
    }

    public static Direction worldFace(BlockState state, PortRole role) {
        Direction front = state.getValue(FACING);
        return switch (role) {
            case FRONT -> front;
            case COLOR -> front.getOpposite();
            case X -> front.getCounterClockWise();
            case Y -> front.getClockWise();
            case WRITE -> Direction.UP;
            case CLEAR -> Direction.DOWN;
        };
    }

    public static PortRole portRole(BlockState state, Direction worldFace) {
        Direction front = state.getValue(FACING);
        if (worldFace == front) return PortRole.FRONT;
        if (worldFace == front.getOpposite()) return PortRole.COLOR;
        if (worldFace == front.getCounterClockWise()) return PortRole.X;
        if (worldFace == front.getClockWise()) return PortRole.Y;
        if (worldFace == Direction.UP) return PortRole.WRITE;
        return PortRole.CLEAR;
    }

    public static int portWidth(BlockState state, Direction worldFace) {
        return switch (portRole(state, worldFace)) {
            case X, Y, COLOR -> 16;
            case WRITE, CLEAR -> 1;
            case FRONT -> 0;
        };
    }

    public static boolean acceptsCable(BlockState state, Direction worldFace, CableBlock cable) {
        if (cable == null) return false;
        int width = portWidth(state, worldFace);
        return width > 0 && cable.bitWidth() == width;
    }
}
