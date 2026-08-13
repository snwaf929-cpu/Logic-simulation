package com.foreverspark.logicsim.client.render;

import com.foreverspark.logicsim.block.DisplayBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class DisplayBlockEntityRenderer implements BlockEntityRenderer<DisplayBlockEntity, DisplayBlockEntityRenderState> {
    private static final String PIXEL = "█";
    private final Font font;

    public DisplayBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.font();
    }

    @Override
    public DisplayBlockEntityRenderState createRenderState() {
        return new DisplayBlockEntityRenderState();
    }

    @Override
    public void extractRenderState(DisplayBlockEntity blockEntity, DisplayBlockEntityRenderState state, float tickProgress, Vec3 cameraPos, @Nullable ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, tickProgress, cameraPos, crumblingOverlay);
        int index = 0;
        for (int y = 0; y < DisplayBlockEntity.HEIGHT; y++) {
            for (int x = 0; x < DisplayBlockEntity.WIDTH; x++) {
                state.pixels[index++] = blockEntity.framebuffer().pixelArgb(x, y);
            }
        }
    }

    @Override
    public void submit(DisplayBlockEntityRenderState state, PoseStack matrices, SubmitNodeCollector queue, CameraRenderState cameraState) {
        matrices.pushPose();
        // Debug panel on the north/front face. 32x18 cells fit inside a 16:9 area on one block face.
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
}
