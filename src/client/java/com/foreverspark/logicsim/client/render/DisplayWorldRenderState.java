package com.foreverspark.logicsim.client.render;

import com.foreverspark.logicsim.block.DisplayBlockEntity;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;

public final class DisplayWorldRenderState extends BlockEntityRenderState {
    /**
     * A run packs ARGB + row + [start,end) X coordinates into one long. Worst case is one run per pixel,
     * while normal display content is much smaller and black pixels emit no geometry.
     */
    public final long[] runs = new long[DisplayBlockEntity.MAX_WIDTH * DisplayBlockEntity.MAX_HEIGHT];
    public Direction facing = Direction.NORTH;
    public int pixelWidth = DisplayBlockEntity.DEFAULT_PIXEL_WIDTH;
    public int pixelHeight = DisplayBlockEntity.pixelHeightFor(DisplayBlockEntity.DEFAULT_PIXEL_WIDTH);
    public int runCount;
}
