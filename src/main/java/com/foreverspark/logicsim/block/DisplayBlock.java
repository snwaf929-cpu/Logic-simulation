package com.foreverspark.logicsim.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

public final class DisplayBlock extends BaseEntityBlock {
    public static final Property<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    public DisplayBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(DisplayBlock::new);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DisplayBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!player.isShiftKeyDown()) return InteractionResult.PASS;

        int current = level.getBlockEntity(pos) instanceof DisplayBlockEntity display
                ? display.pixelWidth()
                : DisplayBlockEntity.DEFAULT_PIXEL_WIDTH;
        int next = DisplayBlockEntity.nextPixelWidth(current);

        if (!level.isClientSide()) {
            DisplayBlockEntity.setWallPixelWidth(level, pos, state, next);
            DisplayBlockEntity.WallInfo info = DisplayBlockEntity.wallInfo(level, pos, state);
            if (info != null) {
                player.sendSystemMessage(Component.literal(
                        "Display: " + info.pixelWidth() + "x" + info.pixelHeight()
                                + " px  |  " + info.columns() + "x" + info.rows() + " tiles"
                                + "  |  " + info.pixelsPerTile() + "x" + info.pixelsPerTile() + " px/tile"
                                + "  |  DATA" + info.dataBusWidth()
                ));
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, ModBlockEntities.DISPLAY, DisplayBlockEntity::tick);
    }
}
