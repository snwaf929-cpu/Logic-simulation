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
    private int busX;
    private int busY;
    private int busColor;
    private boolean busWrite;
    private boolean busClear;

    public DisplayBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DISPLAY, pos, state);
    }

    public DisplayFramebuffer framebuffer() {
        return controller.framebuffer();
    }

    /** Fallback sampling keeps the display correct after chunks/cables load. Normal changes arrive event-driven. */
    public static void tick(Level level, BlockPos pos, BlockState state, DisplayBlockEntity display) {
        if (level.isClientSide()) return;
        display.busX = (int)(CableRuntime.value(level, pos.relative(Direction.WEST)) & 0xFFFFL);
        display.busY = (int)(CableRuntime.value(level, pos.relative(Direction.EAST)) & 0xFFFFL);
        display.busColor = (int)(CableRuntime.value(level, pos.relative(Direction.SOUTH)) & 0xFFFFL);
        display.busWrite = (CableRuntime.value(level, pos.relative(Direction.UP)) & 1L) != 0L;
        display.busClear = (CableRuntime.value(level, pos.relative(Direction.DOWN)) & 1L) != 0L;
        display.sampleSignals(display.busX, display.busY, display.busColor, display.busWrite, display.busClear);
    }

    public void acceptCableValue(Direction face, long value) {
        switch (face) {
            case WEST -> busX = (int)(value & 0xFFFFL);
            case EAST -> busY = (int)(value & 0xFFFFL);
            case SOUTH -> busColor = (int)(value & 0xFFFFL);
            case UP -> busWrite = (value & 1L) != 0L;
            case DOWN -> busClear = (value & 1L) != 0L;
            case NORTH -> { return; }
        }
        sampleSignals(busX, busY, busColor, busWrite, busClear);
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
