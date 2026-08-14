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
    /**
     * display_block.json puts the visible local-NORTH screen face at z=0.75/16.
     * A slightly smaller Z is just outside that face, toward local NORTH.
     */
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
    public void submit(DisplayWorldRenderState state, PoseStack matrices, SubmitNodeCollector queue, CameraRenderState cameraState) {
        matrices.pushPose();
        matrices.translate(0.5, 0.5, 0.5);

        /*
         * IMPORTANT: blockstate JSON rotation and PoseStack Y rotation use opposite signs for EAST/WEST.
         * display_block.json has a local NORTH screen. Minecraft's blockstate y=90 turns that model to
         * facing=EAST, while Axis.YP needs -90 degrees to turn the dynamic local NORTH plane to EAST.
         * Using +90 here puts the pixels on the physical back of an east-facing display.
         */
        matrices.mulPose(Axis.YP.rotationDegrees(rotationDegrees(state.facing)));
        matrices.translate(-0.5, -0.5, -0.5);

        int glyphWidth = Math.max(1, font.width(PIXEL));
        float scaleX = 0.998f / Math.max(1.0f, state.pixelWidth * glyphWidth);
        float scaleY = 0.998f / Math.max(1.0f, state.pixelHeight * 9.0f);

        matrices.translate(0.001, 0.999, screenFaceZ());
        matrices.scale(scaleX, -scaleY, scaleX);

        for (int y = 0; y < state.pixelHeight; y++) {
            for (int x = 0; x < state.pixelWidth; x++) {
                int color = state.pixels[y * DisplayBlockEntity.MAX_WIDTH + x];
                if ((color & 0x00FFFFFF) == 0) continue;
                queue.submitText(
                        matrices,
                        x * glyphWidth,
                        y * 9.0f,
                        Component.literal(PIXEL).getVisualOrderText(),
                        false,
                        // POLYGON_OFFSET is the depth-tested text mode intended for text drawn on a surface.
                        // It avoids fighting with the black screen plane while still being occluded by blocks.
                        pixelDisplayMode(),
                        state.lightCoords,
                        color,
                        0,
                        0
                );
            }
        }
        matrices.popPose();
    }

    /** PoseStack rotation that maps the model's local NORTH screen normal to the requested world facing. */
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

    static double screenFaceZ() {
        return SCREEN_FACE_Z;
    }
}
