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

/** Resize preview for reusable chips. Labels scale to fit; label length never changes body size. */
public final class ChipPreviewWidget extends AbstractWidget {
    private static final double GRID = 6.0;
    private static final double MIN_WIDTH = 66.0;
    private static final double MAX_WIDTH = 260.0;
    private static final double MIN_HEIGHT = 42.0;
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
    private double dragScale = 1.0;

    public ChipPreviewWidget(int x, int y, int width, int height, Supplier<String> nameSupplier, IntSupplier colorSupplier) {
        super(x, y, width, height, Component.literal("Chip preview"));
        this.nameSupplier = nameSupplier == null ? () -> "" : nameSupplier;
        this.colorSupplier = colorSupplier == null ? () -> 0xFF59636E : colorSupplier;
    }

    public void setBounds(int x, int y, int width, int height) {
        setX(x);
        setY(y);
        this.width = Math.max(80, width);
        this.height = Math.max(80, height);
    }

    public void setPortCounts(int inputs, int outputs) {
        inputCount = Math.max(0, inputs);
        outputCount = Math.max(0, outputs);
        bodyHeight = Math.max(bodyHeight, minimumHeight());
    }

    public void setVisual(ChipVisualSettings visual) {
        ChipVisualSettings source = visual == null ? new ChipVisualSettings() : visual;
        source.normalize();
        bodyWidth = Math.max(snap(clamp(source.width, MIN_WIDTH, MAX_WIDTH)), minimumWidth());
        bodyHeight = Math.max(snap(clamp(source.minHeight, MIN_HEIGHT, MAX_HEIGHT)), minimumHeight());
    }

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
        renderScale = Math.max(0.35, Math.min(1.35, Math.min(availableW / modelW, availableH / modelH)));
        bodyScreenWidth = Math.max(30, (int)Math.round(modelW * renderScale));
        bodyScreenHeight = Math.max(24, (int)Math.round(modelH * renderScale));
        bodyX = getX() + (width - bodyScreenWidth) / 2;
        bodyY = getY() + 24 + Math.max(0, (height - 48 - bodyScreenHeight) / 2);

        int accent = forceOpaque(colorSupplier.getAsInt());
        graphics.fill(bodyX, bodyY, bodyX + bodyScreenWidth, bodyY + bodyScreenHeight, 0xFF1A2027);
        graphics.outline(bodyX, bodyY, bodyScreenWidth, bodyScreenHeight, darken(accent, 0.86));
        int strip = Math.max(2, (int)Math.round(4 * renderScale));
        graphics.fill(bodyX + 1, bodyY + 1, bodyX + bodyScreenWidth - 1, bodyY + strip + 1, accent);

        String title = normalizedName().isBlank() ? "CHIP" : normalizedName();
        drawScaledText(graphics, title,
                bodyX + Math.max(3, (int)Math.round(6 * renderScale)),
                bodyY + strip + Math.max(2, (int)Math.round(4 * renderScale)),
                bodyX + bodyScreenWidth - Math.max(3, (int)Math.round(6 * renderScale)),
                bodyY + bodyScreenHeight - Math.max(2, (int)Math.round(4 * renderScale)));

        double pinGap = automaticPinGap(modelH);
        drawPins(graphics, bodyX, bodyY, bodyScreenWidth, inputCount, true, pinGap, modelH);
        drawPins(graphics, bodyX, bodyY, bodyScreenWidth, outputCount, false, pinGap, modelH);

        DragMode hover = dragMode != DragMode.NONE ? dragMode : resizeModeAt(mouseX, mouseY);
        drawResizeHandles(graphics, hover);
        ChipVisualSettings visual = visualSettings();
        String dimensions = Math.round(visual.width) + " × " + Math.round(visual.minHeight) + "   pins " + Math.round(visual.portSpacing);
        graphics.text(font(), dimensions, getX() + width - 8 - font().width(dimensions), getY() + height - 14, 0xFF8B96A3, false);
        graphics.text(font(), "Drag edge/corner; AUTO FIT = compact", getX() + 8, getY() + height - 14, 0xFF65717E, false);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && active && visible) {
            dragMode = resizeModeAt(event.x(), event.y());
            dragScale = Math.max(0.001, renderScale);
        }
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        if (event.button() != 0 || dragMode == DragMode.NONE) return;
        if (dragMode.horizontal()) bodyWidth = snap(clamp(bodyWidth + (dx / dragScale) * dragMode.xSign(), minimumWidth(), MAX_WIDTH));
        if (dragMode.vertical()) bodyHeight = snap(clamp(bodyHeight + (dy / dragScale) * dragMode.ySign(), minimumHeight(), MAX_HEIGHT));
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        if (event.button() == 0) dragMode = DragMode.NONE;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput builder) {}

    private void drawScaledText(GuiGraphicsExtractor graphics, String text, int left, int top, int right, int bottom) {
        if (text == null || text.isBlank()) return;
        int rawWidth = Math.max(1, font().width(text));
        int availableWidth = Math.max(1, right - left);
        int availableHeight = Math.max(1, bottom - top);
        float fitWidth = (float)(availableWidth / (double)rawWidth);
        float fitHeight = (float)(availableHeight / 9.0);
        float scale = Math.max(0.22f, Math.min(1.45f, Math.min((float)renderScale, Math.min(fitWidth, fitHeight))));
        float cx = (left + right) * 0.5f;
        float cy = (top + bottom) * 0.5f;
        graphics.pose().pushMatrix();
        graphics.pose().scale(scale);
        graphics.text(font(), text,
                Math.round(cx / scale - rawWidth / 2.0f),
                Math.round(cy / scale - 4.5f),
                0xFFF1F4F7, false);
        graphics.pose().popMatrix();
    }

    private void drawPins(GuiGraphicsExtractor graphics, int x, int y, int w, int count, boolean input, double pinGap, double modelHeight) {
        if (count <= 0) return;
        double firstModel = modelHeight * 0.5 - (count - 1) * pinGap * 0.5;
        int pinSize = Math.max(2, (int)Math.round(3.0 * renderScale));
        for (int i = 0; i < count; i++) {
            int py = y + (int)Math.round((firstModel + i * pinGap) * renderScale);
            int px = input ? x : x + w;
            graphics.fill(px - pinSize, py - pinSize, px + pinSize + 1, py + pinSize + 1, 0xFFFFC857);
            graphics.outline(px - pinSize - 1, py - pinSize - 1, pinSize * 2 + 3, pinSize * 2 + 3, 0xFF090B0D);
        }
    }

    private void drawResizeHandles(GuiGraphicsExtractor graphics, DragMode hover) {
        int idle = 0xFF40505E, hot = 0xFF8CC7FF;
        graphics.fill(bodyX - 2, bodyY + 7, bodyX + 1, bodyY + bodyScreenHeight - 6, hover.touchesLeft() ? hot : idle);
        graphics.fill(bodyX + bodyScreenWidth - 1, bodyY + 7, bodyX + bodyScreenWidth + 2, bodyY + bodyScreenHeight - 6, hover.touchesRight() ? hot : idle);
        graphics.fill(bodyX + 7, bodyY - 2, bodyX + bodyScreenWidth - 6, bodyY + 1, hover.touchesTop() ? hot : idle);
        graphics.fill(bodyX + 7, bodyY + bodyScreenHeight - 1, bodyX + bodyScreenWidth - 6, bodyY + bodyScreenHeight + 2, hover.touchesBottom() ? hot : idle);
        if (hover.isCorner()) {
            int cx = hover.touchesLeft() ? bodyX : bodyX + bodyScreenWidth;
            int cy = hover.touchesTop() ? bodyY : bodyY + bodyScreenHeight;
            graphics.fill(cx - 5, cy - 5, cx + 6, cy + 6, 0xFFB8DCFF);
        }
    }

    private DragMode resizeModeAt(double mouseX, double mouseY) {
        int tolerance = 13;
        boolean left = Math.abs(mouseX - bodyX) <= tolerance && mouseY >= bodyY - tolerance && mouseY <= bodyY + bodyScreenHeight + tolerance;
        boolean right = Math.abs(mouseX - (bodyX + bodyScreenWidth)) <= tolerance && mouseY >= bodyY - tolerance && mouseY <= bodyY + bodyScreenHeight + tolerance;
        boolean top = Math.abs(mouseY - bodyY) <= tolerance && mouseX >= bodyX - tolerance && mouseX <= bodyX + bodyScreenWidth + tolerance;
        boolean bottom = Math.abs(mouseY - (bodyY + bodyScreenHeight)) <= tolerance && mouseX >= bodyX - tolerance && mouseX <= bodyX + bodyScreenWidth + tolerance;
        if (left && top) return DragMode.TOP_LEFT;
        if (right && top) return DragMode.TOP_RIGHT;
        if (left && bottom) return DragMode.BOTTOM_LEFT;
        if (right && bottom) return DragMode.BOTTOM_RIGHT;
        if (left) return DragMode.LEFT;
        if (right) return DragMode.RIGHT;
        if (top) return DragMode.TOP;
        if (bottom) return DragMode.BOTTOM;
        return DragMode.NONE;
    }

    private double minimumWidth() {
        // Width is a presentation choice. A long name scales down instead of forcing a huge symbol.
        return MIN_WIDTH;
    }

    private double minimumHeight() {
        int count = Math.max(inputCount, outputCount);
        return snap(clamp(count <= 1 ? 48.0 : 30.0 + (count - 1) * MIN_PIN_GAP, MIN_HEIGHT, MAX_HEIGHT));
    }

    private double automaticPinGap(double height) {
        int count = Math.max(inputCount, outputCount);
        if (count <= 1) return 18.0;
        return snap(clamp(Math.max(MIN_PIN_GAP, (height - 30.0) / (count - 1)), MIN_PIN_GAP, MAX_PIN_GAP));
    }

    private String normalizedName() {
        String value = nameSupplier.get();
        return value == null ? "" : value.trim();
    }

    private net.minecraft.client.gui.Font font() { return Minecraft.getInstance().font; }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private static double snap(double value) { return Math.round(value / GRID) * GRID; }
    private static int darken(int color, double factor) { int r = (int)(((color >>> 16) & 0xFF) * factor), g = (int)(((color >>> 8) & 0xFF) * factor), b = (int)((color & 0xFF) * factor); return 0xFF000000 | (r << 16) | (g << 8) | b; }
    private static int forceOpaque(int color) { return 0xFF000000 | (color & 0x00FFFFFF); }

    private enum DragMode {
        NONE(0,0), LEFT(-1,0), RIGHT(1,0), TOP(0,-1), BOTTOM(0,1), TOP_LEFT(-1,-1), TOP_RIGHT(1,-1), BOTTOM_LEFT(-1,1), BOTTOM_RIGHT(1,1);
        private final int xSign, ySign;
        DragMode(int xSign, int ySign) { this.xSign = xSign; this.ySign = ySign; }
        int xSign() { return xSign; }
        int ySign() { return ySign; }
        boolean horizontal() { return xSign != 0; }
        boolean vertical() { return ySign != 0; }
        boolean touchesLeft() { return xSign < 0; }
        boolean touchesRight() { return xSign > 0; }
        boolean touchesTop() { return ySign < 0; }
        boolean touchesBottom() { return ySign > 0; }
        boolean isCorner() { return horizontal() && vertical(); }
    }
}
