package com.foreverspark.logicsim.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

public final class ColorPaletteWidget extends AbstractWidget {
    public static final int[] DEFAULT_COLORS = {0xFFE05252,0xFFE08A3E,0xFFE1C84A,0xFF55B96B,0xFF4C86D9,0xFF7B68D9,0xFFC25BC7,0xFF7B8796,0xFF4FA6A0,0xFFB5BDC8};
    private final IntConsumer onChanged;
    private int selectedColor;

    public ColorPaletteWidget(int x, int y, int width, int height, int selectedColor, IntConsumer onChanged) {
        super(x, y, width, height, Component.literal("Color palette"));
        this.selectedColor = forceOpaque(selectedColor);
        this.onChanged = onChanged;
    }

    public void setBounds(int x, int y, int width, int height) {
        setX(x); setY(y); this.width = Math.max(80, width); this.height = Math.max(10, height);
    }

    public int selectedColor() { return selectedColor; }
    public void setSelectedColor(int color) { selectedColor = forceOpaque(color); }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int gap = 3;
        int swatchWidth = Math.max(10, (width - gap * (DEFAULT_COLORS.length - 1)) / DEFAULT_COLORS.length);
        int x = getX();
        for (int color : DEFAULT_COLORS) {
            int swatchRight = Math.min(getX() + width, x + swatchWidth);
            graphics.fill(x, getY(), swatchRight, getY() + height, color);
            graphics.outline(x - 1, getY() - 1, swatchRight - x + 2, height + 2, sameRgb(color, selectedColor) ? 0xFFFFFFFF : 0xFF101318);
            x += swatchWidth + gap;
        }
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0 || !active || !visible) return;
        int gap = 3;
        int swatchWidth = Math.max(10, (width - gap * (DEFAULT_COLORS.length - 1)) / DEFAULT_COLORS.length);
        double local = event.x() - getX();
        if (local < 0 || local >= width) return;
        int stride = swatchWidth + gap;
        int index = (int) (local / stride);
        if (index < 0 || index >= DEFAULT_COLORS.length || (int) local % stride >= swatchWidth) return;
        selectedColor = DEFAULT_COLORS[index];
        onChanged.accept(selectedColor);
    }

    @Override protected void updateWidgetNarration(NarrationElementOutput builder) {}
    private static boolean sameRgb(int a, int b) { return (a & 0x00FFFFFF) == (b & 0x00FFFFFF); }
    private static int forceOpaque(int color) { return 0xFF000000 | (color & 0x00FFFFFF); }
}
