package com.foreverspark.logicsim.client.screen;

import com.foreverspark.logicsim.editor.model.ChipVisualSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Live reusable-chip preview used by the save dialog.
 *
 * The right edge, bottom edge, and bottom-right corner are drag handles. Dimensions are
 * quantized to the editor routing grid so the resulting chip body and pin rows remain clean.
 * Pin spacing is derived automatically from the chosen height instead of asking the player
 * to guess a numeric gap.
 */
public final class ChipPreviewWidget extends AbstractWidget {
    private static final double GRID = 6.0;
    private static final double MIN_WIDTH = 72.0;
    private static final double MAX_WIDTH = 260.0;
    private static final double MIN_HEIGHT = 48.0;
    private static final double MAX_HEIGHT = 300.0;
    private static final double MIN_PIN_GAP = 12.0;
    private static final double MAX_PIN_GAP = 48.0;

    private final Supplier<String> nameSupplier;
    private final IntSupplier colorSupplier;

    private int inputCount;
    private int outputCount;
    private double bodyWidth = ChipVisualSettings.DEFAULT_WIDTH;
    private double bodyHeight = ChipVisualSettings.DEFAULT_MIN_HEIGHT;

    private double renderScale = 1.0;
    private int bodyX;
    private int bodyY;
    private int bodyScreenWidth;
    private int bodyScreenHeight;
    private DragMode dragMode = DragMode.NONE;

    public ChipPreviewWidget(
            int x,
            int y,
            int width,
            int height,
            Supplier<String> nameSupplier,
            IntSupplier colorSupplier
    ) {
        super(x, y, width, height, Component.literal("Chip preview"));
        this.nameSupplier = nameSupplier == null ? () -> "" : nameSupplier;
        this.colorSupplier = colorSupplier == null ? () -> 0xFF59636E : colorSupplier;
    }

    public void setPortCounts(int inputs, int outputs) {
        inputCount = Math.max(0, inputs);
        outputCount = Math.max(0, outputs);
        bodyHeight = Math.max(bodyHeight, minimumHeight());
    }

    public void setVisual(ChipVisualSettings visual) {
        ChipVisualSettings source = visual == null ? new ChipVisualSettings() : visual;
        source.normalize();
        bodyWidth = snap(clamp(source.width, MIN_WIDTH, MAX_WIDTH));
        bodyHeight = snap(clamp(source.minHeight, MIN_HEIGHT, MAX_HEIGHT));
        bodyWidth = Math.max(bodyWidth, minimumWidth());
        bodyHeight = Math.max(bodyHeight, minimumHeight());
    }

    /** Reset to a compact automatically fitted body for the current name and port count. */
    public void autoFit() {
        bodyWidth = minimumWidth();
        bodyHeight = minimumHeight();
    }

    public ChipVisualSettings visualSettings() {
        double width = Math.max(bodyWidth, minimumWidth());
        double height = Math.max(bodyHeight, minimumHeight());
        return new ChipVisualSettings(width, height, automaticPinGap(height));
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(getX(), getY(), getX() + width, getY() + height, 0xFF0E1217);
        graphics.outline(getX(), getY(), width, height, 0xFF35404B);

        graphics.text(font(), "LIVE CHIP PREVIEW", getX() + 8, getY() + 7, 0xFF7F8B98, false);

        double modelW = Math.max(bodyWidth, minimumWidth());
        double modelH = Math.max(bodyHeight, minimumHeight());
        double availableW = Math.max(40.0, width - 54.0);
        double availableH = Math.max(30.0, height - 50.0);
        renderScale = Math.min(1.35, Math.min(availableW / modelW, availableH / modelH));
        renderScale = Math.max(0.35, renderScale);

        bodyScreenWidth = Math.max(36, (int) Math.round(modelW * renderScale));
        bodyScreenHeight = Math.max(28, (int) Math.round(modelH * renderScale));
        bodyX = getX() + (width - bodyScreenWidth) / 2;
        bodyY = getY() + 24 + Math.max(0, (height - 48 - bodyScreenHeight) / 2);

        int accent = forceOpaque(colorSupplier.getAsInt());
        int border = darken(accent, 0.86);
        graphics.fill(bodyX, bodyY, bodyX + bodyScreenWidth, bodyY + bodyScreenHeight, 0xFF1A2027);
        graphics.outline(bodyX, bodyY, bodyScreenWidth, bodyScreenHeight, border);
        graphics.fill(bodyX + 1, bodyY + 1, bodyX + bodyScreenWidth - 1, bodyY + Math.max(3, (int) Math.round(4 * renderScale)), accent);

        String title = normalizedName();
        String shown = fitText(title.isBlank() ? "CHIP" : title, Math.max(8, bodyScreenWidth - 18));
        int titleX = bodyX + (bodyScreenWidth - font().width(shown)) / 2;
        int titleY = bodyY + Math.max(8, (int) Math.round(10 * renderScale));
        graphics.text(font(), shown, titleX, titleY, 0xFFF1F4F7, false);

        double pinGap = automaticPinGap(modelH);
        drawPins(graphics, bodyX, bodyY, bodyScreenWidth, bodyScreenHeight, inputCount, true, pinGap, modelH);
        drawPins(graphics, bodyX, bodyY, bodyScreenWidth, bodyScreenHeight, outputCount, false, pinGap, modelH);

        DragMode hover = resizeModeAt(mouseX, mouseY);
        if (dragMode != DragMode.NONE) hover = dragMode;
        if (hover.horizontal()) {
            graphics.fill(bodyX + bodyScreenWidth - 1, bodyY + 3, bodyX + bodyScreenWidth + 2, bodyY + bodyScreenHeight - 2, 0xFF8CC7FF);
        }
        if (hover.vertical()) {
            graphics.fill(bodyX + 3, bodyY + bodyScreenHeight - 1, bodyX + bodyScreenWidth - 2, bodyY + bodyScreenHeight + 2, 0xFF8CC7FF);
        }
        if (hover == DragMode.BOTH) {
            graphics.fill(bodyX + bodyScreenWidth - 5, bodyY + bodyScreenHeight - 5, bodyX + bodyScreenWidth + 4, bodyY + bodyScreenHeight + 4, 0xFFB8DCFF);
        }

        ChipVisualSettings visual = visualSettings();
        String dimensions = Math.round(visual.width) + " × " + Math.round(visual.minHeight) + "   pins " + Math.round(visual.portSpacing);
        graphics.text(font(), dimensions, getX() + width - 8 - font().width(dimensions), getY() + height - 14, 0xFF8B96A3, false);
        graphics.text(font(), "Drag right / bottom / corner to resize", getX() + 8, getY() + height - 14, 0xFF65717E, false);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0 || !active || !visible) return;
        dragMode = resizeModeAt(event.x(), event.y());
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        if (event.button() != 0 || dragMode == DragMode.NONE) return;
        double scale = Math.max(0.001, renderScale);
        if (dragMode.horizontal()) {
            bodyWidth = snap(clamp(bodyWidth + dx / scale, minimumWidth(), MAX_WIDTH));
        }
        if (dragMode.vertical()) {
            bodyHeight = snap(clamp(bodyHeight + dy / scale, minimumHeight(), MAX_HEIGHT));
        }
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        if (event.button() == 0) dragMode = DragMode.NONE;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput builder) {
    }

    private void drawPins(
            GuiGraphicsExtractor graphics,
            int x,
            int y,
            int w,
            int h,
            int count,
            boolean input,
            double pinGap,
            double modelHeight
    ) {
        if (count <= 0) return;
        double centerModel = modelHeight * 0.5;
        double firstModel = centerModel - (count - 1) * pinGap * 0.5;
        int pinSize = Math.max(2, (int) Math.round(3.0 * renderScale));
        int pinColor = 0xFFFFC857;
        for (int i = 0; i < count; i++) {
            double modelY = firstModel + i * pinGap;
            int py = y + (int) Math.round(modelY * renderScale);
            int px = input ? x : x + w;
            graphics.fill(px - pinSize, py - pinSize, px + pinSize + 1, py + pinSize + 1, pinColor);
            graphics.outline(px - pinSize - 1, py - pinSize - 1, pinSize * 2 + 3, pinSize * 2 + 3, 0xFF090B0D);
        }
    }

    private DragMode resizeModeAt(double mouseX, double mouseY) {
        int tolerance = 7;
        boolean nearRight = Math.abs(mouseX - (bodyX + bodyScreenWidth)) <= tolerance
                && mouseY >= bodyY - tolerance && mouseY <= bodyY + bodyScreenHeight + tolerance;
        boolean nearBottom = Math.abs(mouseY - (bodyY + bodyScreenHeight)) <= tolerance
                && mouseX >= bodyX - tolerance && mouseX <= bodyX + bodyScreenWidth + tolerance;
        if (nearRight && nearBottom) return DragMode.BOTH;
        if (nearRight) return DragMode.WIDTH;
        if (nearBottom) return DragMode.HEIGHT;
        return DragMode.NONE;
    }

    private double minimumWidth() {
        int titleWidth = font().width(normalizedName().isBlank() ? "CHIP" : normalizedName());
        return snap(clamp(titleWidth + 30.0, MIN_WIDTH, MAX_WIDTH));
    }

    private double minimumHeight() {
        int count = Math.max(inputCount, outputCount);
        double required = count <= 1 ? 54.0 : 36.0 + (count - 1) * MIN_PIN_GAP;
        return snap(clamp(required, MIN_HEIGHT, MAX_HEIGHT));
    }

    private double automaticPinGap(double height) {
        int count = Math.max(inputCount, outputCount);
        if (count <= 1) return 18.0;
        double available = Math.max(MIN_PIN_GAP, (height - 36.0) / (count - 1));
        return snap(clamp(available, MIN_PIN_GAP, MAX_PIN_GAP));
    }

    private String normalizedName() {
        String value = nameSupplier.get();
        return value == null ? "" : value.trim();
    }

    private String fitText(String value, int maxPixels) {
        if (font().width(value) <= maxPixels) return value;
        String suffix = "…";
        int max = Math.max(1, value.length());
        while (max > 1 && font().width(value.substring(0, max - 1) + suffix) > maxPixels) max--;
        return value.substring(0, Math.max(0, max - 1)) + suffix;
    }

    private net.minecraft.client.gui.Font font() {
        return Minecraft.getInstance().font;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double snap(double value) {
        return Math.round(value / GRID) * GRID;
    }

    private static int darken(int color, double factor) {
        int r = (int) (((color >>> 16) & 0xFF) * factor);
        int g = (int) (((color >>> 8) & 0xFF) * factor);
        int b = (int) ((color & 0xFF) * factor);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int forceOpaque(int color) {
        return 0xFF000000 | (color & 0x00FFFFFF);
    }

    private enum DragMode {
        NONE(false, false),
        WIDTH(true, false),
        HEIGHT(false, true),
        BOTH(true, true);

        private final boolean horizontal;
        private final boolean vertical;

        DragMode(boolean horizontal, boolean vertical) {
            this.horizontal = horizontal;
            this.vertical = vertical;
        }

        boolean horizontal() {
            return horizontal;
        }

        boolean vertical() {
            return vertical;
        }
    }
}
