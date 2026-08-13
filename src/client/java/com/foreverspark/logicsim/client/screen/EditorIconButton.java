package com.foreverspark.logicsim.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Compact icon-only toolbar button for the circuit editor. */
public final class EditorIconButton extends AbstractWidget {
    public enum Icon {
        BACK,
        SAVE,
        NEW,
        DELETE,
        WIDTH_DOWN,
        WIDTH_UP,
        FIT,
        HOME
    }

    private final Icon icon;
    private final Runnable action;
    private final int accent;
    private final String tooltip;

    public EditorIconButton(int x, int y, int size, Icon icon, int accent, String tooltip, Runnable action) {
        super(x, y, size, size, Component.literal(tooltip));
        this.icon = icon;
        this.action = action;
        this.accent = 0xFF000000 | (accent & 0x00FFFFFF);
        this.tooltip = tooltip;
    }

    public String tooltip() {
        return tooltip;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int bg = isHovered() ? 0xFF2A313A : 0xFF171C22;
        int border = isHovered() ? accent : 0xFF39434E;
        graphics.fill(getX(), getY(), getX() + width, getY() + height, bg);
        graphics.outline(getX(), getY(), width, height, border);
        drawIcon(graphics, getX() + width / 2, getY() + height / 2, isHovered() ? 0xFFFFFFFF : 0xFFD7DEE8);
    }

    private void drawIcon(GuiGraphicsExtractor g, int cx, int cy, int color) {
        switch (icon) {
            case BACK -> {
                g.fill(cx - 7, cy - 1, cx + 6, cy + 2, color);
                g.fill(cx - 7, cy - 1, cx - 4, cy + 2, color);
                g.fill(cx - 5, cy - 4, cx - 2, cy + 5, color);
                g.fill(cx - 3, cy - 6, cx, cy - 3, color);
                g.fill(cx - 3, cy + 4, cx, cy + 7, color);
            }
            case SAVE -> {
                g.outline(cx - 6, cy - 7, 13, 14, color);
                g.fill(cx - 3, cy - 6, cx + 4, cy - 2, color);
                g.outline(cx - 3, cy + 1, 7, 5, color);
            }
            case NEW -> {
                g.outline(cx - 5, cy - 7, 10, 14, color);
                g.fill(cx - 1, cy - 4, cx + 1, cy + 5, color);
                g.fill(cx - 4, cy, cx + 4, cy + 2, color);
            }
            case DELETE -> {
                g.outline(cx - 5, cy - 4, 10, 11, color);
                g.fill(cx - 7, cy - 7, cx + 7, cy - 5, color);
                g.fill(cx - 3, cy - 9, cx + 3, cy - 7, color);
            }
            case WIDTH_DOWN -> {
                g.fill(cx - 6, cy, cx + 6, cy + 2, color);
                g.fill(cx - 6, cy - 3, cx - 4, cy + 5, color);
            }
            case WIDTH_UP -> {
                g.fill(cx - 6, cy, cx + 6, cy + 2, color);
                g.fill(cx + 4, cy - 3, cx + 6, cy + 5, color);
                g.fill(cx + 1, cy, cx + 9, cy + 2, color);
                g.fill(cx + 4, cy - 3, cx + 6, cy + 5, color);
            }
            case FIT -> {
                g.fill(cx - 7, cy - 7, cx - 1, cy - 5, color);
                g.fill(cx - 7, cy - 7, cx - 5, cy - 1, color);
                g.fill(cx + 1, cy - 7, cx + 7, cy - 5, color);
                g.fill(cx + 5, cy - 7, cx + 7, cy - 1, color);
                g.fill(cx - 7, cy + 5, cx - 1, cy + 7, color);
                g.fill(cx - 7, cy + 1, cx - 5, cy + 7, color);
                g.fill(cx + 1, cy + 5, cx + 7, cy + 7, color);
                g.fill(cx + 5, cy + 1, cx + 7, cy + 7, color);
            }
            case HOME -> {
                g.fill(cx - 6, cy - 1, cx + 6, cy + 7, color);
                g.fill(cx - 4, cy - 4, cx + 4, cy - 2, color);
                g.fill(cx - 2, cy - 6, cx + 2, cy - 4, color);
                g.fill(cx - 2, cy + 2, cx + 2, cy + 7, 0xFF171C22);
            }
        }
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && active && visible) {
            action.run();
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput builder) {
    }
}
