package com.foreverspark.logicsim.client.render;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

/** Render state for one physical display tile sampling its slice of a shared wall texture. */
public final class DisplayWorldRenderState extends BlockEntityRenderState {
    public Direction facing = Direction.NORTH;
    public Identifier textureId;
    public boolean hasPixels;
    public float u0;
    public float v0;
    public float u1 = 1.0f;
    public float v1 = 1.0f;
}
