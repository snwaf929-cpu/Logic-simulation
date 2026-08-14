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
 * The old renderer rebuilt horizontal color runs by scanning every logical pixel on every frame and then emitted one
 * quad per run. A random 1024x1024 wall could therefore approach a million quads per frame. Each physical display
 * tile is now one cached 64x64 DynamicTexture and exactly one screen-face quad, independent of image entropy.
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
        state.hasPixels = !blockEntity.framebuffer().isBlack();
        state.textureId = state.hasPixels ? DisplayTextureCache.textureFor(blockEntity) : null;
    }

    @Override
    public void submit(DisplayWorldRenderState state, PoseStack pose, SubmitNodeCollector queue, CameraRenderState cameraState) {
        if (!state.hasPixels || state.textureId == null) return;

        pose.pushPose();
        pose.translate(0.5, 0.5, 0.5);
        pose.mulPose(Axis.YN.rotationDegrees(surfaceYawDegrees(state.facing)));
        pose.translate(-0.5, -0.5, screenFaceZ() - 0.5);

        queue.submitCustomGeometry(pose, RenderTypes.text(state.textureId), (matrix, consumer) -> {
            // NativeImage row 0 is the logical top row. Keep V=0 at the top of the display quad.
            consumer.addVertex(matrix, SCREEN_MIN, SCREEN_MAX, 0.0f)
                    .setColor(0xFFFFFFFF).setUv(0.0f, 0.0f).setLight(FULL_BRIGHT);
            consumer.addVertex(matrix, SCREEN_MAX, SCREEN_MAX, 0.0f)
                    .setColor(0xFFFFFFFF).setUv(1.0f, 0.0f).setLight(FULL_BRIGHT);
            consumer.addVertex(matrix, SCREEN_MAX, SCREEN_MIN, 0.0f)
                    .setColor(0xFFFFFFFF).setUv(1.0f, 1.0f).setLight(FULL_BRIGHT);
            consumer.addVertex(matrix, SCREEN_MIN, SCREEN_MIN, 0.0f)
                    .setColor(0xFFFFFFFF).setUv(0.0f, 1.0f).setLight(FULL_BRIGHT);
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
