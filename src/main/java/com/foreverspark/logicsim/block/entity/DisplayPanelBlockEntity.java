package com.foreverspark.logicsim.block.entity;

import com.foreverspark.logicsim.display.DisplayController;
import com.foreverspark.logicsim.display.DisplayFramebuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** One physical prototype panel containing a 16x16 RGB565 framebuffer. */
public final class DisplayPanelBlockEntity extends BlockEntity {
    public static final int PIXEL_WIDTH = 16;
    public static final int PIXEL_HEIGHT = 16;

    private final DisplayFramebuffer framebuffer = new DisplayFramebuffer(PIXEL_WIDTH, PIXEL_HEIGHT);
    private final DisplayController controller = new DisplayController(framebuffer);

    public DisplayPanelBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DISPLAY_PANEL, pos, state);
    }

    public DisplayFramebuffer framebuffer() {
        return framebuffer;
    }

    public DisplayController controller() {
        return controller;
    }

    public void setPixel(int x, int y, int rgb565) {
        if (framebuffer.trySetPixel(x, y, rgb565)) setChanged();
    }

    public void clear(int rgb565) {
        framebuffer.clear(rgb565);
        setChanged();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        short[] pixels = framebuffer.snapshotRgb565();
        for (int i = 0; i < pixels.length; i++) output.putInt("p" + i, pixels[i] & 0xFFFF);
        super.saveAdditional(output);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        for (int y = 0; y < PIXEL_HEIGHT; y++) {
            for (int x = 0; x < PIXEL_WIDTH; x++) {
                int index = y * PIXEL_WIDTH + x;
                framebuffer.trySetPixel(x, y, input.getIntOr("p" + index, 0));
            }
        }
        framebuffer.consumeDirtyRect();
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void setChanged() {
        super.setChanged();
        if (level == null) return;
        BlockState state = getBlockState();
        level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_ALL);
    }
}
