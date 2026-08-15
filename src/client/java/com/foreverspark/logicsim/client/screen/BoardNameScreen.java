package com.foreverspark.logicsim.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/** Small focused dialog used for editable board project save/rename operations. */
public final class BoardNameScreen extends Screen {
    private final Screen parent;
    private final String titleText;
    private final String subtitle;
    private final String initialName;
    private final Consumer<String> apply;

    private EditBox nameBox;
    private FlatActionButton saveButton;
    private FlatActionButton cancelButton;
    private String error = "";

    public BoardNameScreen(Screen parent, String titleText, String subtitle, String initialName, Consumer<String> apply) {
        super(Component.literal(titleText == null ? "BOARD" : titleText));
        this.parent = parent;
        this.titleText = titleText == null ? "BOARD" : titleText;
        this.subtitle = subtitle == null ? "" : subtitle;
        this.initialName = initialName == null ? "" : initialName;
        this.apply = apply == null ? ignored -> {} : apply;
    }

    @Override
    protected void init() {
        int panelW = Math.min(420, Math.max(260, this.width - 40));
        int panelX = (this.width - panelW) / 2;
        int panelY = Math.max(46, this.height / 2 - 72);

        nameBox = new EditBox(this.font, panelX + 18, panelY + 58, panelW - 36, 20, Component.literal("Board name"));
        nameBox.setMaxLength(48);
        nameBox.setValue(initialName);
        this.addRenderableWidget(nameBox);
        this.setInitialFocus(nameBox);

        saveButton = new FlatActionButton(panelX + panelW - 142, panelY + 96, 58, 20, "SAVE", 0xFF55B96B, this::applyNow);
        cancelButton = new FlatActionButton(panelX + panelW - 76, panelY + 96, 58, 20, "CANCEL", 0xFF7B8796, this::closeToParent);
        this.addRenderableWidget(saveButton);
        this.addRenderableWidget(cancelButton);
    }

    private void applyNow() {
        String name = nameBox == null ? "" : nameBox.getValue().trim();
        if (name.isEmpty()) {
            error = "Board name is required";
            return;
        }
        try {
            apply.accept(name);
            closeToParent();
        } catch (RuntimeException exception) {
            error = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        }
    }

    private void closeToParent() {
        if (this.minecraft != null) this.minecraft.gui.setScreen(parent);
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
        int panelW = Math.min(420, Math.max(260, this.width - 40));
        int panelX = (this.width - panelW) / 2;
        int panelY = Math.max(46, this.height / 2 - 72);
        int panelH = 132;

        graphics.fill(0, 0, this.width, this.height, 0xB006080B);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xFF151A20);
        graphics.outline(panelX, panelY, panelW, panelH, 0xFF4A5663);
        graphics.fill(panelX, panelY, panelX + 4, panelY + panelH, 0xFF63A9D8);
        graphics.text(this.font, titleText, panelX + 18, panelY + 15, 0xFFF1F4F7, true);
        graphics.text(this.font, truncate(subtitle, Math.max(24, (panelW - 36) / 6)), panelX + 18, panelY + 32, 0xFF82909E, false);
        graphics.text(this.font, "BOARD NAME", panelX + 18, panelY + 49, 0xFF8B96A3, false);
        if (!error.isBlank()) graphics.text(this.font, "! " + truncate(error, Math.max(24, (panelW - 36) / 6)), panelX + 18, panelY + 85, 0xFFFF7878, false);
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, Math.max(0, max - 1)) + "…";
    }
}
