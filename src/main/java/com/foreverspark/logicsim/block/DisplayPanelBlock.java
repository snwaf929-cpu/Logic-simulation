package com.foreverspark.logicsim.block;

import com.foreverspark.logicsim.block.entity.DisplayPanelBlockEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 16x16 addressable RGB565 display prototype.
 * Right-clicking the north face toggles a pixel for quick manual testing; sneaking clears it.
 */
public final class DisplayPanelBlock extends BaseEntityBlock {
    public DisplayPanelBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(DisplayPanelBlock::new);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DisplayPanelBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            BlockHitResult hit
    ) {
        if (!(level.getBlockEntity(pos) instanceof DisplayPanelBlockEntity panel)) {
            return super.useWithoutItem(state, level, pos, player, hit);
        }

        if (!level.isClientSide()) {
            if (player.isShiftKeyDown()) {
                panel.clear(0x0000);
            } else if (hit.getDirection() == Direction.NORTH) {
                Vec3 location = hit.getLocation();
                double localX = location.x - pos.getX();
                double localY = location.y - pos.getY();
                int pixelX = Math.max(0, Math.min(15, (int) Math.floor(localX * 16.0)));
                int pixelY = Math.max(0, Math.min(15, 15 - (int) Math.floor(localY * 16.0)));
                int current = panel.framebuffer().getPixel(pixelX, pixelY);
                panel.setPixel(pixelX, pixelY, current == 0 ? 0xFFFF : 0x0000);
            }
        }
        return InteractionResult.SUCCESS;
    }
}
