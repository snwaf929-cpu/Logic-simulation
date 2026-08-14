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

        // IMPORTANT: use the same Y rotations as assets/.../blockstates/display_block.json.
        // In Minecraft 26.2 the static block model and this dynamic render submission are separate;
        // matching the blockstate values keeps the pixel plane on the visible black screen face.
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
                        // NORMAL participates in world depth testing. Do not use SEE_THROUGH here:
                        // the display body and other solid blocks must occlude screen pixels normally.
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

    /** Exact rotations used by display_block.json. */
    static float rotationDegrees(Direction facing) {
        return switch (facing) {
            case NORTH -> 0.0f;
            case EAST -> 90.0f;
            case SOUTH -> 180.0f;
            case WEST -> 270.0f;
            default -> 0.0f;
        };
    }

    static Font.DisplayMode pixelDisplayMode() {
        return Font.DisplayMode.NORMAL;
    }

    static double screenFaceZ() {
        return SCREEN_FACE_Z;
    }
}
