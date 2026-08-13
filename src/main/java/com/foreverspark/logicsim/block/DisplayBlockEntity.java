package com.foreverspark.logicsim.block;

import com.foreverspark.logicsim.display.DisplayFramebuffer;
import com.foreverspark.logicsim.interconnect.CableRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

public final class DisplayBlockEntity extends BlockEntity {
    public static final int MAX_WIDTH = 64;
    public static final int MAX_HEIGHT = 36;
    public static final int DEFAULT_PIXEL_WIDTH = 32;
    private static final int[] PIXEL_WIDTHS = {1, 2, 4, 8, 16, 32, 64};
    private static final int MAX_WALL_BLOCKS = 4096;

    private final DisplayFramebuffer framebuffer = new DisplayFramebuffer(MAX_WIDTH, MAX_HEIGHT);
    private int pixelWidth = DEFAULT_PIXEL_WIDTH;
    private int busX, busY, busColor;
    private boolean busWrite, busClear, lastWrite, lastClear, syncPending;

    public DisplayBlockEntity(BlockPos pos, BlockState state) { super(ModBlockEntities.DISPLAY, pos, state); }
    public DisplayFramebuffer framebuffer() { return framebuffer; }
    public int pixelWidth() { return pixelWidth; }
    public int pixelHeight() { return pixelHeightFor(pixelWidth); }

    public static int pixelHeightFor(int width) { return Math.max(1, (int)Math.round(width * 9.0 / 16.0)); }
    public static int nextPixelWidth(int current) {
        for (int index = 0; index < PIXEL_WIDTHS.length; index++)
            if (PIXEL_WIDTHS[index] == current) return PIXEL_WIDTHS[(index + 1) % PIXEL_WIDTHS.length];
        return DEFAULT_PIXEL_WIDTH;
    }

    public void setPixelWidth(int width) {
        int normalized = normalizePixelWidth(width);
        if (normalized == pixelWidth) return;
        pixelWidth = normalized;
        framebuffer.clear(0);
        framebuffer.markAllDirty();
        lastWrite = false;
        lastClear = false;
        setChanged();
    }

    public static int setWallPixelWidth(Level level, BlockPos start, BlockState startState, int width) {
        if (level == null || start == null || !(startState.getBlock() instanceof DisplayBlock)) return 0;
        Direction facing = DisplayPorts.front(startState);
        Direction left = DisplayPorts.left(startState);
        Direction right = left.getOpposite();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(start.immutable());
        int changed = 0;
        while (!queue.isEmpty() && seen.size() < MAX_WALL_BLOCKS) {
            BlockPos pos = queue.removeFirst();
            if (!seen.add(pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof DisplayBlock) || DisplayPorts.front(state) != facing) continue;
            if (level.getBlockEntity(pos) instanceof DisplayBlockEntity display) {
                display.setPixelWidth(width);
                changed++;
            }
            queue.add(pos.relative(left));
            queue.add(pos.relative(right));
            queue.add(pos.relative(Direction.UP));
            queue.add(pos.relative(Direction.DOWN));
        }
        return changed;
    }

    public static void tick(Level level, BlockPos pos, BlockState state, DisplayBlockEntity display) {
        if (level.isClientSide()) return;
        display.busX = (int)(CableRuntime.value(level, pos.relative(DisplayPorts.left(state))) & 0xFFFFL);
        display.busY = (int)(CableRuntime.value(level, pos.relative(DisplayPorts.right(state))) & 0xFFFFL);
        display.busColor = (int)(CableRuntime.value(level, pos.relative(DisplayPorts.back(state))) & 0xFFFFL);
        display.busWrite = (CableRuntime.value(level, pos.relative(Direction.UP)) & 1L) != 0L;
        display.busClear = (CableRuntime.value(level, pos.relative(Direction.DOWN)) & 1L) != 0L;
        display.sampleSignals(display.busX, display.busY, display.busColor, display.busWrite, display.busClear);
        display.flushClientSync(level);
    }

    public void acceptCableValue(Direction face, long value) {
        switch (DisplayPorts.portAt(getBlockState(), face)) {
            case X -> busX = (int)(value & 0xFFFFL);
            case Y -> busY = (int)(value & 0xFFFFL);
            case COLOR -> busColor = (int)(value & 0xFFFFL);
            case WRITE -> busWrite = (value & 1L) != 0L;
            case CLEAR -> busClear = (value & 1L) != 0L;
            case NONE -> { return; }
        }
        sampleSignals(busX, busY, busColor, busWrite, busClear);
    }

    public void sampleSignals(int x, int y, int rgb565, boolean write, boolean clear) {
        long before = framebuffer.revision();
        if (clear && !lastClear) framebuffer.clear(0);
        if (write && !lastWrite && x >= 0 && x < pixelWidth && y >= 0 && y < pixelHeight())
            framebuffer.writePixel(x, y, rgb565);
        lastWrite = write;
        lastClear = clear;
        if (framebuffer.revision() != before) setChanged();
    }

    public void writePixel(int x, int y, int rgb565) {
        if (x < 0 || x >= pixelWidth || y < 0 || y >= pixelHeight()) return;
        long before = framebuffer.revision();
        framebuffer.writePixel(x, y, rgb565);
        if (framebuffer.revision() != before) setChanged();
    }

    public void clearScreen() {
        long before = framebuffer.revision();
        framebuffer.clear(0);
        if (framebuffer.revision() != before) setChanged();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        output.putInt("pixelWidth", pixelWidth);
        int width = pixelWidth, height = pixelHeight();
        for (int index = 0; index < width * height; index++) {
            int value = framebuffer.pixelRgb565(index % width, index / width);
            if (value != 0) output.putInt("p" + index, value);
        }
        super.saveAdditional(output);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        pixelWidth = normalizePixelWidth(input.getIntOr("pixelWidth", DEFAULT_PIXEL_WIDTH));
        framebuffer.clear(0);
        int width = pixelWidth, height = pixelHeight();
        for (int index = 0; index < width * height; index++) {
            int value = input.getIntOr("p" + index, 0);
            if (value != 0) framebuffer.writePixel(index % width, index / width, value);
        }
        framebuffer.markAllDirty();
        lastWrite = false;
        lastClear = false;
    }

    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registryLookup) { return saveWithoutMetadata(registryLookup); }
    @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public void setChanged() { super.setChanged(); syncPending = true; }

    private void flushClientSync(Level level) {
        if (!syncPending) return;
        syncPending = false;
        BlockState state = getBlockState();
        level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_ALL);
    }

    private static int normalizePixelWidth(int width) {
        for (int candidate : PIXEL_WIDTHS) if (candidate == width) return candidate;
        return DEFAULT_PIXEL_WIDTH;
    }
}
