package com.foreverspark.logicsim.client.render;

import com.foreverspark.logicsim.block.DisplayBlockEntity;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;

public final class DisplayBlockEntityRenderState extends BlockEntityRenderState {
    public final int[] pixels = new int[DisplayBlockEntity.WIDTH * DisplayBlockEntity.HEIGHT];
}
