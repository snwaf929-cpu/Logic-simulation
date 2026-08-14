package com.foreverspark.logicsim.client.render;

import com.foreverspark.logicsim.LogicSimulationMod;
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
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

public final class DisplayBlockEntityRenderer implements BlockEntityRenderer<DisplayBlockEntity, DisplayWorldRenderState> {
    /** Vanilla packed block-light 15 + sky-light 15. */
    private static final int FULL_BRIGHT = 0x00F000F0;
    /** A one-pixel white texture; vertex color supplies the actual RGB565 display color. */
    private static final Identifier WHITE_TEXTURE = Identifier.fromNamespaceAndPath(
            LogicSimulationMod.MOD_ID,
            "textures/misc/display_white.png"
    );
    /** display_block.json puts the visible local-NORTH screen surface at z=0.75/16. */
    private static final double SCREEN_FACE_Z = (0.75 / 16.0) - 0.001;
    private static final float SCREEN_MIN = 0.0f;
    private static final float SCREEN_MAX = 1.0f;

    public DisplayBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        // The renderer intentionally does not use Font anymore. A font glyph can never be a perfect pixel cell:
        // glyph bearings/advance/line height create the visible gaps that the old renderer produced.
    }

    @Override
    public DisplayWorldRenderState createRenderState() {
        return new DisplayWorldRenderState();
    }

    @Override
    public void extractRenderState(DisplayBlockEntity blockEntity, DisplayWorldRenderState state, float tickProgress,
                                   Vec3 cameraPos, @Nullable ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, tickProgress, cameraPos, crumblingOverlay);
        state.facing = DisplayPorts.front(blockEntity.getBlockState());
        state.pixelWidth = blockEntity.pixelWidth();
        state.pixelHeight = blockEntity.pixelHeight();
        Arrays.fill(state.pixels, 0xFF000000);
        for (int y = 0; y < state.pixelHeight; y++) {
            for (int x = 0; x < state.pixelWidth; x++) {
                state.pixels[y * DisplayBlockEntity.MAX_WIDTH + x] = blockEntity.logicalPixelArgb(x, y);
            }
        }
    }

    @Override
    public void submit(DisplayWorldRenderState state, PoseStack pose, SubmitNodeCollector queue, CameraRenderState cameraState) {
        final int width = Math.max(1, state.pixelWidth);
        final int height = Math.max(1, state.pixelHeight);
        final int[] pixels = state.pixels;
        final float cellWidth = (SCREEN_MAX - SCREEN_MIN) / width;
        final float cellHeight = (SCREEN_MAX - SCREEN_MIN) / height;

        pose.pushPose();
        pose.translate(0.5, 0.5, 0.5);
        pose.mulPose(Axis.YN.rotationDegrees(surfaceYawDegrees(state.facing)));

        // Convert back to local block coordinates while keeping the plane just outside the real NORTH face.
        pose.translate(-0.5, -0.5, screenFaceZ() - 0.5);

        /*
         * One real quad per logical pixel. Adjacent cells use the exact same calculated edge coordinate, so
         * there is no font advance, line-height gap, inset, bezel, or artificial padding between pixels.
         * At 1x1 the single quad is exactly 0..1 by 0..1: the ENTIRE block face is one pixel.
         */
        queue.submitCustomGeometry(pose, RenderTypes.text(WHITE_TEXTURE), (matrix, consumer) -> {
            for (int y = 0; y < height; y++) {
                float top = SCREEN_MAX - y * cellHeight;
                float bottom = SCREEN_MAX - (y + 1) * cellHeight;
                for (int x = 0; x < width; x++) {
                    int color = pixels[y * DisplayBlockEntity.MAX_WIDTH + x];
                    if ((color & 0x00FFFFFF) == 0) continue;

                    float left = SCREEN_MIN + x * cellWidth;
                    float right = SCREEN_MIN + (x + 1) * cellWidth;

                    // Front normal is local NORTH (-Z): TL -> TR -> BR -> BL gives the correct winding.
                    consumer.addVertex(matrix, left, top, 0.0f)
                            .setColor(color).setUv(0.0f, 0.0f).setLight(FULL_BRIGHT);
                    consumer.addVertex(matrix, right, top, 0.0f)
                            .setColor(color).setUv(1.0f, 0.0f).setLight(FULL_BRIGHT);
                    consumer.addVertex(matrix, right, bottom, 0.0f)
                            .setColor(color).setUv(1.0f, 1.0f).setLight(FULL_BRIGHT);
                    consumer.addVertex(matrix, left, bottom, 0.0f)
                            .setColor(color).setUv(0.0f, 1.0f).setLight(FULL_BRIGHT);
                }
            }
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

    static int pixelLight() {
        return FULL_BRIGHT;
    }

    static double screenFaceZ() {
        return SCREEN_FACE_Z;
    }

    static float screenMin() {
        return SCREEN_MIN;
    }

    static float screenMax() {
        return SCREEN_MAX;
    }
}
