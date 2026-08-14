package com.foreverspark.logicsim.client.render;

import com.foreverspark.logicsim.block.DisplayBlockEntity;
import com.foreverspark.logicsim.block.DisplayPorts;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * GPU-textured physical display renderer.
 *
 * All tiles in one connected wall share a single GPU texture. Each block submits only one face quad with the UV slice
 * belonging to that physical tile. This preserves normal Minecraft per-block frustum culling while allowing the
 * renderer to batch the entire visible wall under one texture/render type instead of switching among hundreds or
 * thousands of DynamicTextures.
 *
 * In an integrated client, RealtimeDisplaySurface is preferred over the synchronized block-entity framebuffer. That
 * transient framebuffer is driven directly by the simulation worker and sampled by the renderer, so display motion no
 * longer inherits integrated-server tick stalls. Dedicated/remote clients keep the normal synchronized fallback.
 */
public final class DisplayBlockEntityRenderer implements BlockEntityRenderer<DisplayBlockEntity, DisplayWorldRenderState> {
    /** Vanilla packed block-light 15 + sky-light 15. */
    private static final int FULL_BRIGHT = 0x00F000F0;
    /** display_block.json puts the visible local-NORTH screen surface at z=0.75/16. */
    private static final double SCREEN_FACE_Z = (0.75 / 16.0) - 0.001;
    private static final float SCREEN_MIN = 0.0f;
    private static final float SCREEN_MAX = 1.0f;

    public DisplayBlockEntityRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public DisplayWorldRenderState createRenderState() {
        return new DisplayWorldRenderState();
    }

    @Override
    public void extractRenderState(DisplayBlockEntity blockEntity, DisplayWorldRenderState state, float tickProgress,
                                   Vec3 cameraPos, @Nullable ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, tickProgress, cameraPos, crumblingOverlay);
        state.facing = DisplayPorts.front(blockEntity.getBlockState());

        // A local realtime wall is newer than the client block-entity snapshot. Keep redstone power semantics, but
        // source pixels directly from the simulation-worker surface rather than waiting for a server tick/packet.
        if (RealtimeDisplaySurface.tileView(blockEntity.getBlockPos()) != null) {
            if (blockEntity.wallPowered()) {
                RealtimeDisplayTextureCache.prepare(blockEntity, state);
            } else {
                clearRenderState(state);
            }
            return;
        }

        state.hasPixels = !blockEntity.framebuffer().isBlack();
        if (state.hasPixels) {
            DisplayTextureCache.prepare(blockEntity, state);
        } else {
            clearRenderState(state);
        }
    }

    private static void clearRenderState(DisplayWorldRenderState state) {
        state.hasPixels = false;
        state.textureId = null;
        state.u0 = 0.0f;
        state.v0 = 0.0f;
        state.u1 = 1.0f;
        state.v1 = 1.0f;
    }

    @Override
    public void submit(DisplayWorldRenderState state, PoseStack pose, SubmitNodeCollector queue, CameraRenderState cameraState) {
        if (!state.hasPixels || state.textureId == null) return;

        pose.pushPose();
        pose.translate(0.5, 0.5, 0.5);
        pose.mulPose(Axis.YN.rotationDegrees(surfaceYawDegrees(state.facing)));
        pose.translate(-0.5, -0.5, screenFaceZ() - 0.5);

        queue.submitCustomGeometry(pose, RenderTypes.text(state.textureId), (matrix, consumer) -> {
            // NativeImage row 0 is the logical top row. Each tile samples its precompiled slice of the wall texture.
            consumer.addVertex(matrix, SCREEN_MIN, SCREEN_MAX, 0.0f)
                    .setColor(0xFFFFFFFF).setUv(state.u0, state.v0).setLight(FULL_BRIGHT);
            consumer.addVertex(matrix, SCREEN_MAX, SCREEN_MAX, 0.0f)
                    .setColor(0xFFFFFFFF).setUv(state.u1, state.v0).setLight(FULL_BRIGHT);
            consumer.addVertex(matrix, SCREEN_MAX, SCREEN_MIN, 0.0f)
                    .setColor(0xFFFFFFFF).setUv(state.u1, state.v1).setLight(FULL_BRIGHT);
            consumer.addVertex(matrix, SCREEN_MIN, SCREEN_MIN, 0.0f)
                    .setColor(0xFFFFFFFF).setUv(state.u0, state.v1).setLight(FULL_BRIGHT);
        });

        pose.popPose();
    }

    /** Vanilla yaw passed to Axis.YN; NORTH front = 0, EAST = 90, SOUTH = 180, WEST = 270. */
    static float surfaceYawDegrees(Direction facing) {
        return facing.getOpposite().toYRot();
    }

    /** Equivalent signed Axis.YP rotation, useful for regression-checking the resulting face normal. */
    static float rotationDegrees(Direction facing) {
        return switch (facing) {
            case NORTH -> 0.0f;
            case EAST -> -90.0f;
            case SOUTH -> 180.0f;
            case WEST -> 90.0f;
            default -> 0.0f;
        };
    }

    static int pixelLight() { return FULL_BRIGHT; }
    static double screenFaceZ() { return SCREEN_FACE_Z; }
    static float screenMin() { return SCREEN_MIN; }
    static float screenMax() { return SCREEN_MAX; }
}
