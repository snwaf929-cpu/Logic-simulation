package com.foreverspark.logicsim.client.render;

import com.foreverspark.logicsim.block.DisplayBlockEntity;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;

public final class DisplayWorldRenderState extends BlockEntityRenderState {
    public final int[] pixels = new int[DisplayBlockEntity.MAX_WIDTH * DisplayBlockEntity.MAX_HEIGHT];
    public Direction facing = Direction.NORTH;
    public int pixelWidth = DisplayBlockEntity.DEFAULT_PIXEL_WIDTH;
    public int pixelHeight = DisplayBlockEntity.pixelHeightFor(DisplayBlockEntity.DEFAULT_PIXEL_WIDTH);
    /** Pixels are a one-sided screen surface; never render them while the camera is behind the display. */
    public boolean cameraOnFront;
}
