package com.foreverspark.logicsim.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Small rectangular editor button. Deliberately not styled like vanilla Minecraft UI. */
public final class FlatActionButton extends AbstractWidget {
    private final Runnable action;
    private final int accent;

    public FlatActionButton(int x, int y, int width, int height, String text, int accent, Runnable action) {
        super(x, y, width, height, Component.literal(text));
        this.action = action;
        this.accent = 0xFF000000 | (accent & 0x00FFFFFF);
    }

    public void setBounds(int x, int y, int width, int height) {
        setX(x);
        setY(y);
        this.width = Math.max(20, width);
        this.height = Math.max(14, height);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        boolean hovered = isHovered();
        int bg = hovered ? 0xFF2B313A : 0xFF1B2027;
        int border = hovered ? accent : 0xFF3B444F;
        graphics.fill(getX(), getY(), getX() + width, getY() + height, bg);
        graphics.outline(getX(), getY(), width, height, border);
        var font = Minecraft.getInstance().font;
        String text = getMessage().getString();
        int tx = getX() + Math.max(4, (width - font.width(text)) / 2);
        int ty = getY() + Math.max(4, (height - 8) / 2);
        graphics.text(font, text, tx, ty, hovered ? 0xFFFFFFFF : 0xFFD5DCE5, false);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && active && visible) action.run();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput builder) {}
}
