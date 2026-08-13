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
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class DisplayBlockEntityRenderer implements BlockEntityRenderer<DisplayBlockEntity, DisplayWorldRenderState> {
    private static final String PIXEL = "█";
    private final Font font;

    public DisplayBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.font();
    }

    @Override
    public DisplayWorldRenderState createRenderState() {
        return new DisplayWorldRenderState();
    }

    @Override
    public void extractRenderState(DisplayBlockEntity blockEntity, DisplayWorldRenderState state, float tickProgress, Vec3 cameraPos, @Nullable ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, tickProgress, cameraPos, crumblingOverlay);
        state.facing = DisplayPorts.front(blockEntity.getBlockState());
        int index = 0;
        for (int y = 0; y < DisplayBlockEntity.HEIGHT; y++) {
            for (int x = 0; x < DisplayBlockEntity.WIDTH; x++) {
                state.pixels[index++] = blockEntity.framebuffer().pixelArgb(x, y);
            }
        }
    }

    @Override
    public void submit(DisplayWorldRenderState state, PoseStack matrices, SubmitNodeCollector queue, CameraRenderState cameraState) {
        matrices.pushPose();
        matrices.translate(0.5, 0.5, 0.5);
        matrices.mulPose(Axis.YP.rotationDegrees(rotationDegrees(state.facing)));
        matrices.translate(-0.5, -0.5, -0.5);

        // The canonical panel is on NORTH; the pose above rotates it onto the placed block's FACING side.
        matrices.translate(0.07, 0.755, -0.002);
        matrices.scale(0.0045f, -0.0030f, 0.0045f);

        int glyphWidth = Math.max(1, font.width(PIXEL));
        for (int y = 0; y < DisplayBlockEntity.HEIGHT; y++) {
            for (int x = 0; x < DisplayBlockEntity.WIDTH; x++) {
                int color = state.pixels[y * DisplayBlockEntity.WIDTH + x];
                if ((color & 0x00FFFFFF) == 0) continue;
                queue.submitText(
                        matrices,
                        x * glyphWidth,
                        y * 9.0f,
                        Component.literal(PIXEL).getVisualOrderText(),
                        false,
                        Font.DisplayMode.SEE_THROUGH,
                        state.lightCoords,
                        color,
                        0,
                        0
                );
            }
        }
        matrices.popPose();
    }

    private static float rotationDegrees(net.minecraft.core.Direction facing) {
        return switch (facing) {
            case NORTH -> 0.0f;
            case EAST -> -90.0f;
            case SOUTH -> 180.0f;
            case WEST -> 90.0f;
            default -> 0.0f;
        };
    }
}
