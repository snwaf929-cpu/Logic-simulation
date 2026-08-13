package com.foreverspark.logicsim.client.display;

import com.foreverspark.logicsim.block.entity.DisplayPanelBlockEntity;
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

public final class DisplayPanelRenderer implements BlockEntityRenderer<DisplayPanelBlockEntity, DisplayPanelRenderState> {
    private static final String PIXEL = "#";
    private final Font font;

    public DisplayPanelRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.font();
    }

    @Override
    public DisplayPanelRenderState createRenderState() {
        return new DisplayPanelRenderState();
    }

    @Override
    public void extractRenderState(DisplayPanelBlockEntity blockEntity, DisplayPanelRenderState state, float tickProgress, Vec3 cameraPos, @Nullable ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, tickProgress, cameraPos, crumblingOverlay);
        state.width = blockEntity.framebuffer().width();
        state.height = blockEntity.framebuffer().height();
        state.pixelsArgb = blockEntity.framebuffer().snapshotArgb();
    }

    @Override
    public void submit(DisplayPanelRenderState state, PoseStack matrices, SubmitNodeCollector queue, CameraRenderState cameraState) {
        if (state.width <= 0 || state.height <= 0 || state.pixelsArgb.length == 0) return;
        float glyphW = Math.max(1, font.width(PIXEL));
        float glyphH = Math.max(1, font.lineHeight);
        float scaleX = 0.90f / (state.width * glyphW);
        float scaleY = 0.90f / (state.height * glyphH);
        var glyph = Component.literal(PIXEL).getVisualOrderText();
        matrices.pushPose();
        matrices.translate(0.05, 0.95, -0.002);
        matrices.scale(scaleX, -scaleY, scaleX);
        for (int y = 0; y < state.height; y++) {
            for (int x = 0; x < state.width; x++) {
                int argb = state.pixelsArgb[y * state.width + x];
                if ((argb & 0x00FFFFFF) == 0) continue;
                queue.submitText(matrices, x * glyphW, y * glyphH, glyph, false, Font.DisplayMode.NORMAL, state.lightCoords, argb, 0, 0);
            }
        }
        matrices.popPose();
    }
}
