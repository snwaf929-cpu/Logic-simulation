package com.foreverspark.logicsim.block;

import com.foreverspark.logicsim.display.DisplayController;
import com.foreverspark.logicsim.display.DisplayFramebuffer;
import com.foreverspark.logicsim.interconnect.CableRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class DisplayBlockEntity extends BlockEntity {
    public static final int WIDTH = 32;
    public static final int HEIGHT = 18;

    private final DisplayController controller = new DisplayController(WIDTH, HEIGHT);

    public DisplayBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DISPLAY, pos, state);
    }

    public DisplayFramebuffer framebuffer() {
        return controller.framebuffer();
    }

    public static void tick(Level level, BlockPos pos, BlockState state, DisplayBlockEntity display) {
        if (level.isClientSide()) return;
        int x = (int)(CableRuntime.value(level, pos.relative(Direction.WEST)) & 0xFFFFL);
        int y = (int)(CableRuntime.value(level, pos.relative(Direction.EAST)) & 0xFFFFL);
        int color = (int)(CableRuntime.value(level, pos.relative(Direction.SOUTH)) & 0xFFFFL);
        boolean write = (CableRuntime.value(level, pos.relative(Direction.UP)) & 1L) != 0L;
        boolean clear = (CableRuntime.value(level, pos.relative(Direction.DOWN)) & 1L) != 0L;
        display.sampleSignals(x, y, color, write, clear);
    }

    public void sampleSignals(int x, int y, int rgb565, boolean write, boolean clear) {
        long before = framebuffer().revision();
        controller.sample(x, y, rgb565, write, clear);
        if (framebuffer().revision() != before) setChanged();
    }

    public void writePixel(int x, int y, int rgb565) {
        long before = framebuffer().revision();
        framebuffer().writePixel(x, y, rgb565);
        if (framebuffer().revision() != before) setChanged();
    }

    public void clearScreen() {
        long before = framebuffer().revision();
        framebuffer().clear(0);
        if (framebuffer().revision() != before) setChanged();
    }
}
