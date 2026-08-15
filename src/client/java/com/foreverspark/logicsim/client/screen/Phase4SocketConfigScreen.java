package com.foreverspark.logicsim.client.screen;

import com.foreverspark.logicsim.editor.model.PortDirection;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Exact BOARD socket editor: stable identity, label, direction, width, and explicit ordering. */
public final class Phase4SocketConfigScreen extends Screen {
    @FunctionalInterface
    public interface Apply {
        void accept(String name, String interfaceId, PortDirection direction, int width, int order);
    }

    private final Screen parent;
    private final String initialName;
    private final String initialInterfaceId;
    private final int initialWidth;
    private final int initialOrder;
    private final Apply apply;
    private PortDirection direction;

    private EditBox nameBox;
    private EditBox identityBox;
    private EditBox widthBox;
    private EditBox orderBox;
    private FlatActionButton directionButton;
    private String error = "";

    public Phase4SocketConfigScreen(
            Screen parent,
            String name,
            String interfaceId,
            PortDirection direction,
            int width,
            int order,
            Apply apply
    ) {
        super(Component.literal("BOARD SOCKET"));
        this.parent = parent;
        this.initialName = name == null ? "" : name;
        this.initialInterfaceId = interfaceId == null ? "" : interfaceId;
        this.direction = direction == null ? PortDirection.INPUT : direction;
        this.initialWidth = Math.max(1, Math.min(64, width));
        this.initialOrder = Math.max(0, order);
        this.apply = apply;
    }

    @Override
    protected void init() {
        int w = Math.min(470, Math.max(310, width - 40));
        int x = (width - w) / 2;
        int y = Math.max(28, height / 2 - 116);
        int fieldW = w - 36;

        nameBox = new EditBox(font, x + 18, y + 51, fieldW, 20, Component.literal("Socket name"));
        nameBox.setMaxLength(32);
        nameBox.setValue(initialName);
        addRenderableWidget(nameBox);

        identityBox = new EditBox(font, x + 18, y + 91, fieldW, 20, Component.literal("Interface identity"));
        identityBox.setMaxLength(48);
        identityBox.setValue(initialInterfaceId);
        addRenderableWidget(identityBox);

        widthBox = new EditBox(font, x + 18, y + 131, 70, 20, Component.literal("Width"));
        widthBox.setMaxLength(2);
        widthBox.setValue(Integer.toString(initialWidth));
        addRenderableWidget(widthBox);

        orderBox = new EditBox(font, x + 100, y + 131, 70, 20, Component.literal("Order"));
        orderBox.setMaxLength(4);
        orderBox.setValue(Integer.toString(initialOrder));
        addRenderableWidget(orderBox);

        directionButton = new FlatActionButton(x + 182, y + 131, 112, 20, directionText(), 0xFF63A9D8, this::toggleDirection);
        addRenderableWidget(directionButton);
        addRenderableWidget(new FlatActionButton(x + w - 146, y + 178, 60, 20, "APPLY", 0xFF55B96B, this::applyNow));
        addRenderableWidget(new FlatActionButton(x + w - 78, y + 178, 60, 20, "CANCEL", 0xFF7B8796, this::closeToParent));
        setInitialFocus(nameBox);
    }

    private void toggleDirection() {
        direction = direction == PortDirection.INPUT ? PortDirection.OUTPUT : PortDirection.INPUT;
        directionButton.setMessage(Component.literal(directionText()));
    }

    private String directionText() {
        return direction == PortDirection.INPUT ? "INPUT SOCKET" : "OUTPUT SOCKET";
    }

    private void applyNow() {
        try {
            String name = nameBox.getValue().trim();
            String identity = identityBox.getValue().trim();
            if (name.isEmpty()) throw new IllegalArgumentException("Socket name is required");
            if (identity.isEmpty()) throw new IllegalArgumentException("Stable interface identity is required");
            int width = Integer.parseInt(widthBox.getValue().trim());
            int order = Integer.parseInt(orderBox.getValue().trim());
            if (width < 1 || width > 64) throw new IllegalArgumentException("Width must be 1-64 bits");
            if (order < 0) throw new IllegalArgumentException("Order must be 0 or greater");
            apply.accept(name, identity, direction, width, order);
            closeToParent();
        } catch (NumberFormatException exception) {
            error = "Width/order must be numbers";
        } catch (RuntimeException exception) {
            error = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        }
    }

    private void closeToParent() {
        if (minecraft != null) minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            closeToParent();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
            applyNow();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int w = Math.min(470, Math.max(310, width - 40));
        int x = (width - w) / 2;
        int y = Math.max(28, height / 2 - 116);
        int h = 216;
        graphics.fill(0, 0, width, height, 0xB006080B);
        graphics.fill(x, y, x + w, y + h, 0xFF151A20);
        graphics.outline(x, y, w, h, 0xFF4A5663);
        graphics.fill(x, y, x + 4, y + h, 0xFF55AFC2);
        graphics.text(font, "BOARD SOCKET", x + 18, y + 14, 0xFFF1F4F7, true);
        graphics.text(font, "Routing interface only — no primitive logic", x + 18, y + 30, 0xFF81909E, false);
        graphics.text(font, "NAME", x + 18, y + 42, 0xFF8B96A3, false);
        graphics.text(font, "STABLE INTERFACE ID", x + 18, y + 82, 0xFF8B96A3, false);
        graphics.text(font, "WIDTH", x + 18, y + 122, 0xFF8B96A3, false);
        graphics.text(font, "ORDER", x + 100, y + 122, 0xFF8B96A3, false);
        graphics.text(font, "DIRECTION", x + 182, y + 122, 0xFF8B96A3, false);
        if (!error.isBlank()) graphics.text(font, "! " + error, x + 18, y + 159, 0xFFFF7878, false);
    }
}
