package com.foreverspark.logicsim.client.screen;

import com.foreverspark.logicsim.editor.model.PortDirection;
import com.foreverspark.logicsim.editor.model.PortSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class PortChoiceButton extends AbstractWidget {
    private Runnable action = () -> {};
    private PortSpec port;
    private PortDirection direction;
    private boolean cableSupported;

    public PortChoiceButton(int x, int y, int width, int height) {
        super(x, y, width, height, Component.empty());
        visible = false;
        active = false;
    }

    public void setChoice(PortSpec port, PortDirection direction, boolean cableSupported, Runnable action) {
        this.port = port;
        this.direction = direction;
        this.cableSupported = cableSupported;
        this.action = action == null ? () -> {} : action;
        this.visible = true;
        this.active = cableSupported;
    }

    public void clearChoice() {
        port = null;
        direction = null;
        visible = false;
        active = false;
        action = () -> {};
    }

    public void setBounds(int x, int y, int width, int height) {
        setX(x);
        setY(y);
        this.width = width;
        this.height = height;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (port == null || direction == null) return;
        boolean hovered = isHovered() && active;
        int accent = direction == PortDirection.INPUT ? 0xFF4C86D9 : 0xFF7B68D9;
        int bg = hovered ? 0xFF29323C : 0xFF181E25;
        int border = cableSupported ? (hovered ? accent : 0xFF3C4652) : 0xFF382F31;
        graphics.fill(getX(), getY(), getX() + width, getY() + height, bg);
        graphics.outline(getX(), getY(), width, height, border);
        graphics.fill(getX(), getY(), getX() + 4, getY() + height, cableSupported ? accent : 0xFF6B4448);

        var font = Minecraft.getInstance().font;
        String dir = direction == PortDirection.INPUT ? "IN" : "OUT";
        String widthText = Integer.toString(port.width());
        int textColor = cableSupported ? 0xFFE8EDF3 : 0xFF8A7074;
        graphics.text(font, dir, getX() + 10, getY() + 7, cableSupported ? accent : 0xFF8A7074, true);
        graphics.text(font, fit(port.name(), Math.max(20, width - 92), font), getX() + 42, getY() + 7, textColor, false);
        graphics.text(font, widthText, getX() + width - 12 - font.width(widthText), getY() + 7, textColor, true);
        if (!cableSupported) graphics.text(font, "NO CABLE", getX() + width - 78, getY() + 7, 0xFF9B6268, false);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && active && visible) action.run();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput builder) {}

    private static String fit(String value, int pixels, net.minecraft.client.gui.Font font) {
        if (value == null) return "";
        if (font.width(value) <= pixels) return value;
        String suffix = "…";
        int end = value.length();
        while (end > 1 && font.width(value.substring(0, end - 1) + suffix) > pixels) end--;
        return value.substring(0, Math.max(0, end - 1)) + suffix;
    }
}
