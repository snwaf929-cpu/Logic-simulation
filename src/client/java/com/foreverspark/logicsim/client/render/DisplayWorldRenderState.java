package com.foreverspark.logicsim.client.render;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

/** Render state for one physical display tile: one texture, one quad. */
public final class DisplayWorldRenderState extends BlockEntityRenderState {
    public Direction facing = Direction.NORTH;
    public Identifier textureId;
    public boolean hasPixels;
}
