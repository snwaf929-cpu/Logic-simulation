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

    /**
     * Empty-hand right click = explain the connected screen.
     * Shift + right click = change pixels per display block and report the new total resolution.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide()) {
            if (player.isShiftKeyDown()) {
                int current = level.getBlockEntity(pos) instanceof DisplayBlockEntity display
                        ? display.pixelWidth()
                        : DisplayBlockEntity.DEFAULT_PIXEL_WIDTH;
                int next = DisplayBlockEntity.nextPixelWidth(current);
                DisplayBlockEntity.setWallPixelWidth(level, pos, state, next);
                logic$showScreenInfo(level, pos, state, player, true);
            } else {
                logic$showScreenInfo(level, pos, state, player, false);
            }
        }
        return InteractionResult.SUCCESS;
    }

    private static void logic$showScreenInfo(Level level, BlockPos pos, BlockState state, Player player, boolean changed) {
        DisplayBlockEntity.WallInfo info = DisplayBlockEntity.wallInfo(level, pos, state);
        if (info == null) return;

        if (changed) {
            player.sendSystemMessage(Component.literal(
                    "Screen changed: " + info.pixelsPerTile() + "x" + info.pixelsPerTile() + " pixels per block"
                            + " -> " + info.pixelWidth() + "x" + info.pixelHeight() + " total pixels."
            ));
            player.sendSystemMessage(Component.literal(
                    "Cable does NOT change with resolution: always use one Bus Cable [64] on any side/back display block."
            ));
            return;
        }

        player.sendSystemMessage(Component.literal(
                "Screen: " + info.pixelWidth() + "x" + info.pixelHeight() + " total pixels"
                        + " | " + info.columns() + "x" + info.rows() + " display blocks"
                        + " | " + info.pixelsPerTile() + "x" + info.pixelsPerTile() + " pixels/block"
        ));
        player.sendSystemMessage(Component.literal(
                "Connection: one Bus Cable [64] to any side/back display block. Shift+Right Click changes pixels/block."
        ));
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, ModBlockEntities.DISPLAY, DisplayBlockEntity::tick);
    }
}
