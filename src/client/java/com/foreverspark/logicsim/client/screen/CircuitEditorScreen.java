package com.foreverspark.logicsim.client.screen;

import com.foreverspark.logicsim.client.chip.ClientChipLibrary;
import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.runtime.CircuitCompiler;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;

/** Main editor shell with a compact custom toolbar and mixed component library. */
public final class CircuitEditorScreen extends Screen {
    private static final int TOP_BAR_HEIGHT = 32;
    private static final int SIDEBAR_WIDTH = 186;

    private final ClientChipLibrary library = new ClientChipLibrary();

    private CircuitCanvasWidget canvas;
    private ComponentLibraryWidget componentLibrary;
    private EditBox circuitNameBox;
    private EditBox folderNameBox;
    private String status = "Drag empty canvas to pan. Click INPUT switch to test. Click a component to place it.";

    public CircuitEditorScreen() {
        super(Component.translatable("screen.logicsimulation.circuit_editor"));
    }

    @Override
    protected void init() {
        CircuitDocument previousDocument = canvas == null ? new CircuitDocument() : canvas.document();
        String previousCircuitName = circuitNameBox == null ? "" : circuitNameBox.getValue();
        String previousFolderName = folderNameBox == null ? "" : folderNameBox.getValue();

        int canvasX = SIDEBAR_WIDTH + 16;
        int canvasY = TOP_BAR_HEIGHT + 2;
        int canvasWidth = Math.max(140, this.width - canvasX - 8);
        int canvasHeight = Math.max(100, this.height - canvasY - 8);
        canvas = new CircuitCanvasWidget(canvasX, canvasY, canvasWidth, canvasHeight, previousDocument, library, this::setStatus);
        this.addRenderableWidget(canvas);

        int sidebarY = TOP_BAR_HEIGHT + 2;
        componentLibrary = new ComponentLibraryWidget(
                8,
                sidebarY,
                SIDEBAR_WIDTH,
                Math.max(110, this.height - sidebarY - 8),
                library,
                canvas,
                () -> folderNameBox == null ? "" : folderNameBox.getValue(),
                value -> {
                    if (folderNameBox != null) folderNameBox.setValue(value);
                },
                this::openChip,
                this::setStatus
        );
        this.addRenderableWidget(componentLibrary);

        circuitNameBox = new EditBox(this.font, 42, 8, 116, 18, Component.literal("Chip name"));
        circuitNameBox.setMaxLength(48);
        circuitNameBox.setValue(previousCircuitName);
        this.addRenderableWidget(circuitNameBox);

        folderNameBox = new EditBox(this.font, 15, sidebarY + 33, SIDEBAR_WIDTH - 30, 16, Component.literal("Folder name"));
        folderNameBox.setMaxLength(32);
        folderNameBox.setValue(previousFolderName);
        this.addRenderableWidget(folderNameBox);

        int x = 164;
        this.addRenderableWidget(action(x, "SAVE", 46, 0xFF55B96B, this::saveChip)); x += 50;
        this.addRenderableWidget(action(x, "LOAD", 46, 0xFF4C86D9, this::loadNamedChip)); x += 50;
        this.addRenderableWidget(action(x, "NEW", 42, 0xFF7B8796, () -> {
            canvas.newDocument();
            circuitNameBox.setValue("");
        })); x += 46;
        this.addRenderableWidget(action(x, "DELETE", 54, 0xFFE05252, canvas::deleteSelection)); x += 58;
        this.addRenderableWidget(action(x, "W-", 34, 0xFF4FA6A0, () -> canvas.changeSelectedWidth(-1))); x += 38;
        this.addRenderableWidget(action(x, "W+", 34, 0xFF4FA6A0, () -> canvas.changeSelectedWidth(1))); x += 38;
        this.addRenderableWidget(action(x, "FIT", 38, 0xFF7B68D9, canvas::fitView)); x += 42;
        this.addRenderableWidget(action(x, "HOME", 44, 0xFF7B8796, canvas::resetView));
    }

    private FlatActionButton action(int x, String text, int width, int accent, Runnable runnable) {
        return new FlatActionButton(x, 7, width, 20, text, accent, runnable);
    }

    private void saveChip() {
        String name = circuitNameBox.getValue().trim();
        try {
            if (name.isEmpty()) {
                setStatus("Enter a chip name first");
                return;
            }
            CircuitCompiler.compile(canvas.document(), library);
            library.save(name, canvas.document());
            setStatus("Saved reusable chip: " + name + " — it is now in the component library");
        } catch (RuntimeException | IOException exception) {
            setStatus("SAVE FAILED: " + message(exception));
        }
    }

    private void loadNamedChip() {
        openChip(circuitNameBox.getValue().trim());
    }

    private void openChip(String name) {
        try {
            ChipDefinition definition = library.load(name);
            canvas.setDocument(library.copyDocument(definition.circuit));
            circuitNameBox.setValue(definition.name);
            setStatus("Editing chip: " + definition.name + "  |  right-click a library chip to edit it quickly");
        } catch (RuntimeException | IOException exception) {
            setStatus("LOAD FAILED: " + message(exception));
        }
    }

    private void setStatus(String status) {
        this.status = status == null ? "" : status;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, this.width, TOP_BAR_HEIGHT, 0xFF11151A);
        graphics.fill(0, TOP_BAR_HEIGHT - 1, this.width, TOP_BAR_HEIGHT, 0xFF303843);
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        graphics.text(this.font, "CHIP", 8, 13, 0xFF7F8A99, false);
        int statusX = 526;
        if (statusX < this.width - 20) {
            int maxChars = Math.max(18, (this.width - statusX - 8) / 6);
            String shown = status.length() > maxChars ? status.substring(0, Math.max(0, maxChars - 1)) + "…" : status;
            int color = status.startsWith("ERROR") || status.contains("FAILED") || status.contains("MISMATCH")
                    ? 0xFFFF7676
                    : 0xFFABB5C1;
            graphics.text(this.font, shown, statusX, 13, color, false);
        }
    }

    private static String message(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
