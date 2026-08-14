package com.foreverspark.logicsim.client.render;

import com.foreverspark.logicsim.block.DisplayBlockEntity;
import com.foreverspark.logicsim.block.DisplayPorts;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

public final class DisplayBlockEntityRenderer implements BlockEntityRenderer<DisplayBlockEntity, DisplayWorldRenderState> {
    private static final String PIXEL = "█";
    /** Vanilla packed block-light 15 + sky-light 15. Kept local because the helper class moved in 26.2 mappings. */
    private static final int FULL_BRIGHT = 0x00F000F0;
    /** display_block.json puts the visible local-NORTH screen surface at z=0.75/16. */
    private static final double SCREEN_FACE_Z = (0.75 / 16.0) - 0.001;
    private final Font font;

    public DisplayBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.font();
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
                state.pixels[y * DisplayBlockEntity.MAX_WIDTH + x] = blockEntity.framebuffer().pixelArgb(x, y);
            }
        }
    }

    @Override
    public void submit(DisplayWorldRenderState state, PoseStack pose, SubmitNodeCollector queue, CameraRenderState cameraState) {
        int glyphWidth = Math.max(1, font.width(PIXEL));
        float scaleX = 0.998f / Math.max(1.0f, state.pixelWidth * glyphWidth);
        float scaleY = 0.998f / Math.max(1.0f, state.pixelHeight * 9.0f);

        pose.pushPose();
        pose.translate(0.5, 0.5, 0.5);

        /*
         * Follow the same transform convention used by current 26.x block-entity text renderers:
         * rotate around negative Y using the opposite direction's vanilla yaw. This maps the model's
         * local NORTH screen plane to the block's FACING direction without copying blockstate JSON signs.
         */
        pose.mulPose(Axis.YN.rotationDegrees(surfaceYawDegrees(state.facing)));

        // Start at the top-left corner of the real local-NORTH screen, slightly outside the model surface.
        pose.translate(-0.499, 0.499, screenFaceZ() - 0.5);

        /*
         * Do not mirror the PoseStack with a negative scale. Mirroring flips geometry winding and can make
         * depth-tested glyphs disappear. Rotate 180 degrees around Z and keep scales positive, which is the
         * normal surface-text pattern in current Minecraft renderers.
         */
        pose.mulPose(Axis.ZN.rotationDegrees(180.0f));
        pose.scale(scaleX, scaleY, scaleX);

        for (int y = 0; y < state.pixelHeight; y++) {
            for (int x = 0; x < state.pixelWidth; x++) {
                int color = state.pixels[y * DisplayBlockEntity.MAX_WIDTH + x];
                if ((color & 0x00FFFFFF) == 0) continue;

                // After the 180-degree Z rotation, negative text X advances toward screen-right.
                float textX = -(x + 1) * glyphWidth;
                float textY = y * 9.0f;
                queue.submitText(
                        pose,
                        textX,
                        textY,
                        Component.literal(PIXEL).getVisualOrderText(),
                        false,
                        pixelDisplayMode(),
                        pixelLight(),
                        color,
                        0,
                        0
                );
            }
        }
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

    static Font.DisplayMode pixelDisplayMode() {
        return Font.DisplayMode.POLYGON_OFFSET;
    }

    /** A monitor pixel is emissive; its RGB565 value should not become black because the block is unlit. */
    static int pixelLight() {
        return FULL_BRIGHT;
    }

    static double screenFaceZ() {
        return SCREEN_FACE_Z;
    }
}
