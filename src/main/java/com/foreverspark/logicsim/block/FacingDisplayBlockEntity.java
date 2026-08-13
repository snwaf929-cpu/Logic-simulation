package com.foreverspark.logicsim.block;

import com.foreverspark.logicsim.display.DisplayController;
import com.foreverspark.logicsim.display.DisplayFramebuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class FacingDisplayBlockEntity extends BlockEntity {
    public static final int WIDTH = 32;
    public static final int HEIGHT = 18;

    private final DisplayController controller = new DisplayController(WIDTH, HEIGHT);
    private int busX;
    private int busY;
    private int busColor;
    private boolean busWrite;
    private boolean busClear;
    private boolean syncPending;

    public FacingDisplayBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DISPLAY, pos, state);
    }

    public DisplayFramebuffer framebuffer() {
        return controller.framebuffer();
    }
}
