package com.foreverspark.logicsim.client.screen;

import com.foreverspark.logicsim.client.board.ClientBoardTemplateLibrary;
import com.foreverspark.logicsim.editor.model.BoardSocketSpec;
import com.foreverspark.logicsim.editor.model.BoardTemplateDefinition;
import com.foreverspark.logicsim.editor.model.BoardTemplateReplacementPreview;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/** BOARD-template picker with an explicit socket reconnection preview before replacement. */
public final class BoardTemplateScreen extends Screen {
    @FunctionalInterface
    public interface PreviewProvider {
        BoardTemplateReplacementPreview preview(BoardTemplateDefinition template);
    }

    private final Screen parent;
    private final ClientBoardTemplateLibrary templates;
    private final PreviewProvider previewProvider;
    private final Consumer<BoardTemplateDefinition> insert;
    private final Consumer<BoardTemplateDefinition> replace;

    private BoardTemplateDefinition selected;
    private BoardTemplateReplacementPreview preview;
    private FlatActionButton insertButton;
    private FlatActionButton replaceButton;
    private String error = "";

    public BoardTemplateScreen(
            Screen parent,
            ClientBoardTemplateLibrary templates,
            PreviewProvider previewProvider,
            Consumer<BoardTemplateDefinition> insert,
            Consumer<BoardTemplateDefinition> replace
    ) {
        super(Component.literal("BOARD TEMPLATES"));
        this.parent = parent;
        this.templates = templates;
        this.previewProvider = previewProvider;
        this.insert = insert;
        this.replace = replace;
    }

    @Override
    protected void init() {
        int panelW = Math.min(700, Math.max(430, width - 40));
        int panelH = Math.min(430, Math.max(280, height - 50));
        int x = (width - panelW) / 2;
        int y = (height - panelH) / 2;
        int listW = Math.min(210, panelW / 3);

        List<String> names = templates.names();
        int rowY = y + 48;
        int maxRows = Math.max(1, (panelH - 108) / 22);
        for (int i = 0; i < names.size() && i < maxRows; i++) {
            String name = names.get(i);
            addRenderableWidget(new FlatActionButton(x + 14, rowY, listW - 28, 19, name, 0xFF63A9D8, () -> select(name)));
            rowY += 22;
        }

        insertButton = new FlatActionButton(x + panelW - 226, y + panelH - 34, 62, 20, "INSERT", 0xFF55B96B, this::insertSelected);
        replaceButton = new FlatActionButton(x + panelW - 156, y + panelH - 34, 70, 20, "REPLACE", 0xFF55AFC2, this::replaceSelected);
        addRenderableWidget(insertButton);
        addRenderableWidget(replaceButton);
        addRenderableWidget(new FlatActionButton(x + panelW - 78, y + panelH - 34, 64, 20, "CANCEL", 0xFF7B8796, this::closeToParent));
        updateActions();
        if (!names.isEmpty()) select(names.getFirst());
    }

    private void select(String name) {
        try {
            selected = templates.load(name);
            preview = previewProvider == null ? null : previewProvider.preview(selected);
            error = "";
        } catch (IOException | RuntimeException exception) {
            selected = null;
            preview = null;
            error = message(exception);
        }
        updateActions();
    }

    private void updateActions() {
        if (insertButton != null) insertButton.active = selected != null;
        if (replaceButton != null) replaceButton.active = selected != null && preview != null && preview.compatible();
    }

    private void insertSelected() {
        if (selected == null) return;
        try {
            insert.accept(selected);
            closeToParent();
        } catch (RuntimeException exception) {
            error = message(exception);
        }
    }

    private void replaceSelected() {
        if (selected == null || preview == null || !preview.compatible()) return;
        try {
            replace.accept(selected);
            closeToParent();
        } catch (RuntimeException exception) {
            error = message(exception);
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
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int panelW = Math.min(700, Math.max(430, width - 40));
        int panelH = Math.min(430, Math.max(280, height - 50));
        int x = (width - panelW) / 2;
        int y = (height - panelH) / 2;
        int listW = Math.min(210, panelW / 3);
        int infoX = x + listW + 10;
        int infoW = panelW - listW - 24;

        graphics.fill(0, 0, width, height, 0xB006080B);
        graphics.fill(x, y, x + panelW, y + panelH, 0xFF151A20);
        graphics.outline(x, y, panelW, panelH, 0xFF4A5663);
        graphics.fill(x, y, x + 4, y + panelH, 0xFF55AFC2);
        graphics.text(font, "BOARD TEMPLATES", x + 14, y + 14, 0xFFF1F4F7, true);
        graphics.text(font, "Reusable physical/layout modules — real nodes are cloned into the board", x + 14, y + 30, 0xFF81909E, false);
        graphics.text(font, "TEMPLATES", x + 14, y + 39, 0xFF8B96A3, false);
        graphics.fill(x + listW, y + 42, x + listW + 1, y + panelH - 48, 0xFF303943);

        if (templates.names().isEmpty()) {
            graphics.text(font, "No templates saved", x + 14, y + 58, 0xFF8995A2, false);
            graphics.text(font, "Ctrl+Alt+S saves this BOARD", x + 14, y + 74, 0xFF65717E, false);
        }

        if (selected != null) {
            graphics.text(font, selected.name, infoX, y + 50, 0xFFFFFFFF, true);
            graphics.text(font, selected.circuit.nodes.size() + " nodes   " + selected.circuit.wires.size() + " internal wires", infoX, y + 66, 0xFF8FA0B0, false);
            graphics.text(font, "SOCKETS / EXPLICIT ORDER", infoX, y + 88, 0xFF8B96A3, false);
            int sy = y + 104;
            for (BoardSocketSpec socket : selected.sockets()) {
                String line = String.format("%02d  %s  %s  [%d]  %s", socket.order(), socket.direction(), socket.name(), socket.width(), socket.interfaceId());
                graphics.text(font, truncate(line, Math.max(24, infoW / 6)), infoX, sy, 0xFFD7DEE8, false);
                sy += 14;
                if (sy > y + 190) break;
            }

            if (preview == null) {
                graphics.text(font, "INSERT PREVIEW", infoX, y + 214, 0xFF8B96A3, false);
                graphics.text(font, "No template instance selected — INSERT is available", infoX, y + 230, 0xFF8FA0B0, false);
            } else {
                graphics.text(font, "REPLACEMENT CONNECTION PREVIEW", infoX, y + 214,
                        preview.compatible() ? 0xFF66C982 : 0xFFFF7878, false);
                graphics.text(font, preview.oldTemplateName() + " -> " + preview.newTemplateName()
                        + "   external wires: " + preview.externalConnections(), infoX, y + 230, 0xFFD7DEE8, false);
                int py = y + 247;
                for (var mapping : preview.mappings()) {
                    String line = mapping.oldSocket().name() + " -> " + mapping.newSocket().name()
                            + "   " + mapping.matchKind() + "   wires " + mapping.externalConnections();
                    graphics.text(font, truncate(line, Math.max(24, infoW / 6)), infoX, py, 0xFF9ED2F0, false);
                    py += 14;
                    if (py > y + panelH - 86) break;
                }
                for (String problem : preview.errors()) {
                    if (py > y + panelH - 72) break;
                    graphics.text(font, "! " + truncate(problem, Math.max(22, infoW / 6)), infoX, py, 0xFFFF7878, false);
                    py += 14;
                }
                for (String warning : preview.warnings()) {
                    if (py > y + panelH - 72) break;
                    graphics.text(font, "~ " + truncate(warning, Math.max(22, infoW / 6)), infoX, py, 0xFFFFC857, false);
                    py += 14;
                }
            }
        } else if (!error.isBlank()) {
            graphics.text(font, "! " + truncate(error, Math.max(24, infoW / 6)), infoX, y + 54, 0xFFFF7878, false);
        }

        if (!error.isBlank()) graphics.text(font, "! " + truncate(error, Math.max(30, (panelW - 36) / 6)), x + 14, y + panelH - 55, 0xFFFF7878, false);
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static String message(Exception exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? exception.getClass().getSimpleName() : value;
    }
}
