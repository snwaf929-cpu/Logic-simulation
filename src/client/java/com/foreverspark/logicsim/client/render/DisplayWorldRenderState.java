package com.foreverspark.logicsim.client.render;

import com.foreverspark.logicsim.block.DisplayBlockEntity;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;

public final class DisplayWorldRenderState extends BlockEntityRenderState {
    /**
     * A run packs ARGB + row + [start,end) X coordinates into one long. Worst case is one run per pixel,
     * but normal display content is dramatically smaller and a black tile has zero runs.
     */
    public final long[] runs = new long[DisplayBlockEntity.MAX_WIDTH * DisplayBlockEntity.MAX_HEIGHT];
    public Direction facing = Direction.NORTH;
    public int pixelWidth = DisplayBlockEntity.DEFAULT_PIXEL_WIDTH;
    public int pixelHeight = DisplayBlockEntity.pixelHeightFor(DisplayBlockEntity.DEFAULT_PIXEL_WIDTH);
    public int runCount;

    /** Cache key for the logical run list; geometry is rebuilt only when source/framebuffer/density changes. */
    public long sourcePos = Long.MIN_VALUE;
    public long framebufferRevision = Long.MIN_VALUE;
    public int cachedPixelWidth = -1;
    public int cachedPixelHeight = -1;
}
