package com.foreverspark.logicsim.client.screen;

import com.foreverspark.logicsim.client.chip.ClientChipLibrary;
import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.runtime.CircuitCompiler;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.List;

/** Main freeform editor screen for Milestone 1. */
public final class CircuitEditorScreen extends Screen {
    private final ClientChipLibrary library = new ClientChipLibrary();

    private CircuitCanvasWidget canvas;
    private EditBox circuitNameBox;
    private EditBox insertChipBox;
    private String status = "Click a tool, place nodes, then click OUT port → IN port to wire.";

    public CircuitEditorScreen() {
        super(Component.translatable("screen.logicsimulation.circuit_editor"));
    }

    @Override
    protected void init() {
        CircuitDocument previousDocument = canvas == null ? new CircuitDocument() : canvas.document();
        String previousCircuitName = circuitNameBox == null ? "" : circuitNameBox.getValue();
        String previousInsertName = insertChipBox == null ? "" : insertChipBox.getValue();

        circuitNameBox = new EditBox(this.font, 8, 18, 118, 18, Component.literal("Circuit name"));
        circuitNameBox.setMaxLength(48);
        circuitNameBox.setValue(previousCircuitName);
        this.addRenderableWidget(circuitNameBox);

        insertChipBox = new EditBox(this.font, 8, 54, 118, 18, Component.literal("Saved chip name"));
        insertChipBox.setMaxLength(48);
        insertChipBox.setValue(previousInsertName);
        this.addRenderableWidget(insertChipBox);

        int buttonWidth = 118;
        int y = 82;
        this.addRenderableWidget(button("+ INPUT", y, () -> canvas.setPlacement(NodeKind.INPUT))); y += 22;
        this.addRenderableWidget(button("+ NAND", y, () -> canvas.setPlacement(NodeKind.NAND))); y += 22;
        this.addRenderableWidget(button("+ OUTPUT", y, () -> canvas.setPlacement(NodeKind.OUTPUT))); y += 22;
        this.addRenderableWidget(button("+ SPLITTER", y, () -> canvas.setPlacement(NodeKind.SPLITTER))); y += 22;
        this.addRenderableWidget(button("+ MERGER", y, () -> canvas.setPlacement(NodeKind.MERGER))); y += 22;
        this.addRenderableWidget(Button.builder(Component.literal("+ CUSTOM CHIP"), button -> canvas.setCustomChipPlacement(insertChipBox.getValue()))
                .bounds(8, y, buttonWidth, 20).build()); y += 25;

        this.addRenderableWidget(button("DELETE SELECTED", y, () -> canvas.deleteSelection())); y += 22;
        this.addRenderableWidget(button("WIDTH -", y, () -> canvas.changeSelectedWidth(-1))); y += 22;
        this.addRenderableWidget(button("WIDTH +", y, () -> canvas.changeSelectedWidth(1))); y += 22;
        this.addRenderableWidget(button("RESET VIEW", y, () -> canvas.resetView())); y += 25;

        this.addRenderableWidget(Button.builder(Component.literal("SAVE CHIP"), button -> saveChip())
                .bounds(8, y, 57, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("LOAD"), button -> loadChip())
                .bounds(69, y, 57, 20).build()); y += 22;
        this.addRenderableWidget(button("NEW CIRCUIT", y, () -> canvas.newDocument()));

        int canvasX = 136;
        int canvasY = 8;
        int canvasWidth = Math.max(120, this.width - canvasX - 8);
        int canvasHeight = Math.max(100, this.height - 16);
        canvas = new CircuitCanvasWidget(canvasX, canvasY, canvasWidth, canvasHeight, previousDocument, library, this::setStatus);
        this.addRenderableWidget(canvas);
    }

    private Button button(String text, int y, Runnable action) {
        return Button.builder(Component.literal(text), button -> action.run())
                .bounds(8, y, 118, 20)
                .build();
    }

    private void saveChip() {
        String name = circuitNameBox.getValue().trim();
        try {
            if (name.isEmpty()) {
                setStatus("Enter a circuit name first");
                return;
            }
            CircuitCompiler.compile(canvas.document(), library);
            library.save(name, canvas.document());
            setStatus("Saved reusable chip: " + name);
            if (insertChipBox.getValue().isBlank()) {
                insertChipBox.setValue(name);
            }
        } catch (RuntimeException | IOException exception) {
            setStatus("SAVE FAILED: " + message(exception));
        }
    }

    private void loadChip() {
        String name = circuitNameBox.getValue().trim();
        try {
            ChipDefinition definition = library.load(name);
            canvas.setDocument(library.copyDocument(definition.circuit));
            circuitNameBox.setValue(definition.name);
            setStatus("Loaded chip for editing: " + definition.name);
        } catch (RuntimeException | IOException exception) {
            setStatus("LOAD FAILED: " + message(exception));
        }
    }

    private void setStatus(String status) {
        this.status = status == null ? "" : status;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.text(this.font, "CIRCUIT NAME / SAVE", 8, 7, 0xFFAAB3C0, true);
        graphics.text(this.font, "CHIP TO INSERT", 8, 43, 0xFFAAB3C0, true);

        int statusY = Math.max(4, this.height - 13);
        String shown = status.length() > 96 ? status.substring(0, 95) + "…" : status;
        graphics.text(this.font, shown, 138, statusY, status.startsWith("ERROR") || status.contains("FAILED") || status.contains("MISMATCH") ? 0xFFFF6B6B : 0xFFD7DEE8, true);

        List<String> names = library.names();
        if (!names.isEmpty()) {
            String saved = "Saved: " + String.join(", ", names);
            if (saved.length() > 23) saved = saved.substring(0, 22) + "…";
            graphics.text(this.font, saved, 8, Math.max(4, this.height - 13), 0xFF7F8A99, true);
        }
    }

    private static String message(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
