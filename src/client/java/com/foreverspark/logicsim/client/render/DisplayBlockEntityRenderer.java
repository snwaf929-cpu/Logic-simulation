package com.foreverspark.logicsim.client.render;

import com.foreverspark.logicsim.LogicSimulationMod;
import com.foreverspark.logicsim.block.DisplayBlockEntity;
import com.foreverspark.logicsim.block.DisplayPorts;
import com.foreverspark.logicsim.display.DisplayFramebuffer;
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

    private static final long ROW_MASK = 0x3FL;
    private static final long X_MASK = 0x3FL;
    private static final long END_X_MASK = 0x7FL;

    public DisplayBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        // Rendering is deliberately quad-based rather than font-based so logical pixels have exact shared edges.
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

        int width = blockEntity.pixelWidth();
        int height = blockEntity.pixelHeight();
        long sourcePos = blockEntity.getBlockPos().asLong();
        long revision = blockEntity.framebuffer().revision();
        state.pixelWidth = width;
        state.pixelHeight = height;

        // Render-state extraction runs every frame. Never resample a static framebuffer.
        if (state.sourcePos == sourcePos
                && state.framebufferRevision == revision
                && state.cachedPixelWidth == width
                && state.cachedPixelHeight == height) {
            return;
        }

        state.sourcePos = sourcePos;
        state.framebufferRevision = revision;
        state.cachedPixelWidth = width;
        state.cachedPixelHeight = height;
        state.runCount = 0;

        DisplayFramebuffer framebuffer = blockEntity.framebuffer();
        // This is the overwhelmingly common case for an idle/off screen and is now O(1), with zero render submit.
        if (framebuffer.isBlack()) return;

        rebuildRuns(blockEntity, state, framebuffer, width, height);
    }

    private static void rebuildRuns(DisplayBlockEntity blockEntity, DisplayWorldRenderState state,
                                    DisplayFramebuffer framebuffer, int width, int height) {
        for (int y = 0; y < height; y++) {
            int x = 0;
            while (x < width) {
                int color = logicalPixelArgb(framebuffer, x, y, width, height);
                if ((color & 0x00FFFFFF) == 0) {
                    x++;
                    continue;
                }

                int startX = x;
                x++;
                while (x < width && logicalPixelArgb(framebuffer, x, y, width, height) == color) x++;
                state.runs[state.runCount++] = packRun(y, startX, x, color);
            }
        }
    }

    /**
     * Samples the persistent 64x64 backing store directly. End coordinates are exclusive, which also fixes the
     * old 64x64 path where a 1x1 backing cell could accidentally sample zero pixels.
     */
    private static int logicalPixelArgb(DisplayFramebuffer framebuffer, int x, int y, int width, int height) {
        int scaleX = DisplayBlockEntity.MAX_WIDTH / width;
        int scaleY = DisplayBlockEntity.MAX_HEIGHT / height;
        int minX = x * scaleX;
        int minY = y * scaleY;
        int endX = minX + scaleX;
        int endY = minY + scaleY;
        for (int backingY = minY; backingY < endY; backingY++) {
            for (int backingX = minX; backingX < endX; backingX++) {
                int value = framebuffer.pixelRgb565(backingX, backingY);
                if (value != 0) return DisplayFramebuffer.rgb565ToArgb(value);
            }
        }
        return 0xFF000000;
    }

    @Override
    public void submit(DisplayWorldRenderState state, PoseStack pose, SubmitNodeCollector queue, CameraRenderState cameraState) {
        final int runCount = state.runCount;
        if (runCount == 0) return;

        final int width = Math.max(1, state.pixelWidth);
        final int height = Math.max(1, state.pixelHeight);
        final long[] runs = state.runs;
        final float cellWidth = (SCREEN_MAX - SCREEN_MIN) / width;
        final float cellHeight = (SCREEN_MAX - SCREEN_MIN) / height;

        pose.pushPose();
        pose.translate(0.5, 0.5, 0.5);
        pose.mulPose(Axis.YN.rotationDegrees(surfaceYawDegrees(state.facing)));
        pose.translate(-0.5, -0.5, screenFaceZ() - 0.5);

        /*
         * Horizontal same-color logical pixels are emitted as one quad. A solid 32x32 tile is therefore 32
         * quads instead of 1024, while an all-black tile submits no custom geometry at all.
         */
        queue.submitCustomGeometry(pose, RenderTypes.text(WHITE_TEXTURE), (matrix, consumer) -> {
            for (int index = 0; index < runCount; index++) {
                long run = runs[index];
                int color = (int) run;
                int y = unpackY(run);
                int startX = unpackStartX(run);
                int endX = unpackEndX(run);

                float top = SCREEN_MAX - y * cellHeight;
                float bottom = SCREEN_MAX - (y + 1) * cellHeight;
                float left = SCREEN_MIN + startX * cellWidth;
                float right = SCREEN_MIN + endX * cellWidth;

                consumer.addVertex(matrix, left, top, 0.0f)
                        .setColor(color).setUv(0.0f, 0.0f).setLight(FULL_BRIGHT);
                consumer.addVertex(matrix, right, top, 0.0f)
                        .setColor(color).setUv(1.0f, 0.0f).setLight(FULL_BRIGHT);
                consumer.addVertex(matrix, right, bottom, 0.0f)
                        .setColor(color).setUv(1.0f, 1.0f).setLight(FULL_BRIGHT);
                consumer.addVertex(matrix, left, bottom, 0.0f)
                        .setColor(color).setUv(0.0f, 1.0f).setLight(FULL_BRIGHT);
            }
        });

        pose.popPose();
    }

    static long packRun(int y, int startX, int endX, int argb) {
        return Integer.toUnsignedLong(argb)
                | ((long) (y & 0x3F) << 32)
                | ((long) (startX & 0x3F) << 38)
                | ((long) (endX & 0x7F) << 44);
    }

    static int unpackY(long run) { return (int) ((run >>> 32) & ROW_MASK); }
    static int unpackStartX(long run) { return (int) ((run >>> 38) & X_MASK); }
    static int unpackEndX(long run) { return (int) ((run >>> 44) & END_X_MASK); }

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
