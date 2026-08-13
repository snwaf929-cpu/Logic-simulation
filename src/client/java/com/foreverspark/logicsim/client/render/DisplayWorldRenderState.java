package com.foreverspark.logicsim.client.render;

import com.foreverspark.logicsim.block.DisplayBlockEntity;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;

public final class DisplayWorldRenderState extends BlockEntityRenderState {
    public final int[] pixels = new int[DisplayBlockEntity.WIDTH * DisplayBlockEntity.HEIGHT];
    public Direction facing = Direction.NORTH;
}
