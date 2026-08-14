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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

public final class DisplayBlockEntityRenderer implements BlockEntityRenderer<DisplayBlockEntity, DisplayWorldRenderState> {
    private static final String PIXEL = "█";
    /**
     * The model's visible screen starts at z=0.75/16 on its local NORTH/front side.
     * Keep pixels just in front of that surface so they are flush without z-fighting or visibly floating.
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
        BlockPos pos = blockEntity.getBlockPos();
        state.cameraOnFront = isCameraOnFront(
                state.facing,
                cameraPos.x - (pos.getX() + 0.5),
                cameraPos.y - (pos.getY() + 0.5),
                cameraPos.z - (pos.getZ() + 0.5)
        );
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
        // A physical monitor is one-sided. This also prevents the old SEE_THROUGH-style illusion
        // where the front pixels appeared pasted onto the back of the display.
        if (!state.cameraOnFront) return;

        matrices.pushPose();
        matrices.translate(0.5, 0.5, 0.5);
        // PoseStack uses the normal world-space Y rotation convention. These angles rotate the
        // local NORTH screen plane toward the actual FACING direction.
        matrices.mulPose(Axis.YP.rotationDegrees(rotationDegrees(state.facing)));
        matrices.translate(-0.5, -0.5, -0.5);

        int glyphWidth = Math.max(1, font.width(PIXEL));
        float scaleX = 0.998f / Math.max(1.0f, state.pixelWidth * glyphWidth);
        float scaleY = 0.998f / Math.max(1.0f, state.pixelHeight * 9.0f);

        // Local NORTH is the model's screen face. Render almost flush with the actual screen surface.
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
                        // NORMAL uses the world depth-tested text layer. Pixels are therefore hidden
                        // by blocks between the camera and the display instead of drawing through them.
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

    /** Rotates the local NORTH screen plane to the requested world-facing direction. */
    static float rotationDegrees(Direction facing) {
        return switch (facing) {
            case NORTH -> 0.0f;
            case EAST -> -90.0f;
            case SOUTH -> 180.0f;
            case WEST -> 90.0f;
            default -> 0.0f;
        };
    }

    static boolean isCameraOnFront(Direction facing, double dx, double dy, double dz) {
        return dx * facing.getStepX() + dy * facing.getStepY() + dz * facing.getStepZ() > 0.0;
    }

    static Font.DisplayMode pixelDisplayMode() {
        return Font.DisplayMode.NORMAL;
    }

    static double screenFaceZ() {
        return SCREEN_FACE_Z;
    }
}
