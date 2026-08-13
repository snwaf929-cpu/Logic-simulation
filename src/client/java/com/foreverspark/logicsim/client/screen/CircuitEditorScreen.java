package com.foreverspark.logicsim.client.screen;

import com.foreverspark.logicsim.client.chip.ClientChipLibrary;
import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.ChipVisualSettings;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.runtime.CircuitCompiler;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Main editor shell with CAD-like shortcuts, safe deletion, and hierarchical live inspection. */
public final class CircuitEditorScreen extends Screen {
    private static final int TOP_BAR_HEIGHT = 34;
    private static final int STATUS_BAR_HEIGHT = 24;
    private static final int SIDEBAR_WIDTH = 186;
    private static final int MODAL_WIDTH = 330;
    private static final int MODAL_HEIGHT = 214;
    private static final int TOOLBAR_RESERVED_WIDTH = 238;

    private final ClientChipLibrary library = new ClientChipLibrary();
    private final List<EditorIconButton> toolbarButtons = new ArrayList<>();

    private CircuitCanvasWidget canvas;
    private ComponentLibraryWidget componentLibrary;
    private String currentChipName;
    private String breadcrumb = "ROOT";
    private boolean liveNestedRuntime;
    private int toolbarStartX = 154;
    private String status = "Ready — right/middle-drag to pan. Double-click a custom chip to inspect it.";

    private ModalMode modalMode = ModalMode.NONE;
    private LibraryEditKind libraryEditKind;
    private String pendingLibraryName;
    private CircuitCanvasWidget.IoSelection pendingIo;
    private CircuitCanvasWidget.DeletionIntent pendingDeletion;
    private String modalError = "";
    private int modalColor = ClientChipLibrary.DEFAULT_CHIP_COLOR;

    /** Original screenshot key while this editor temporarily owns F2. Null means nothing was changed. */
    private String suppressedScreenshotKey;

    private EditBox modalNameBox;
    private EditBox modalWidthBox;
    private EditBox modalHeightBox;
    private EditBox modalGapBox;
    private ColorPaletteWidget modalPalette;
    private FlatActionButton modalApplyButton;
    private FlatActionButton modalCancelButton;

    public CircuitEditorScreen() {
        super(Component.translatable("screen.logicsimulation.circuit_editor"));
    }

    @Override
    public void added() {
        super.added();
        suppressVanillaF2ScreenshotWhileOpen();
    }

    @Override
    public void removed() {
        restoreVanillaScreenshotBinding();
        super.removed();
    }

    /**
     * Vanilla handles the screenshot mapping before Screen.keyPressed, so consuming F2 in this
     * screen is too late. Temporarily unbind screenshot only when the user's screenshot key is F2,
     * then restore the exact key when this editor closes. We intentionally do not save Options.
     */
    private void suppressVanillaF2ScreenshotWhileOpen() {
        if (suppressedScreenshotKey != null) return;
        String saved = this.minecraft.options.keyScreenshot.saveString();
        InputConstants.Key screenshotKey = InputConstants.getKey(saved);
        if (screenshotKey.getType() == InputConstants.Type.KEYSYM && screenshotKey.getValue() == GLFW.GLFW_KEY_F2) {
            suppressedScreenshotKey = saved;
            this.minecraft.options.keyScreenshot.setKey(InputConstants.UNKNOWN);
            KeyMapping.resetMapping();
        }
    }

    private void restoreVanillaScreenshotBinding() {
        if (suppressedScreenshotKey == null) return;
        this.minecraft.options.keyScreenshot.setKey(InputConstants.getKey(suppressedScreenshotKey));
        KeyMapping.resetMapping();
        suppressedScreenshotKey = null;
    }

    @Override
    protected void init() {
        CircuitDocument previousDocument = canvas == null ? new CircuitDocument() : canvas.document();
        String previousChipName = canvas == null ? currentChipName : canvas.currentChipName();
        toolbarButtons.clear();

        int canvasX = SIDEBAR_WIDTH + 16;
        int canvasY = TOP_BAR_HEIGHT + 2;
        int canvasWidth = Math.max(140, this.width - canvasX - 8);
        int canvasHeight = Math.max(100, this.height - canvasY - STATUS_BAR_HEIGHT - 4);
        canvas = new CircuitCanvasWidget(
                canvasX,
                canvasY,
                canvasWidth,
                canvasHeight,
                previousDocument,
                previousChipName,
                library,
                this::setStatus,
                this::onCanvasNavigationChanged
        );
        this.addRenderableWidget(canvas);

        int sidebarY = TOP_BAR_HEIGHT + 2;
        componentLibrary = new ComponentLibraryWidget(
                8,
                sidebarY,
                SIDEBAR_WIDTH,
                Math.max(110, this.height - sidebarY - STATUS_BAR_HEIGHT - 4),
                library,
                canvas,
                this::openAddFolderModal,
                this::openChip,
                this::setStatus
        );
        this.addRenderableWidget(componentLibrary);
        if (currentChipName != null) {
            componentLibrary.selectChip(currentChipName);
        }

        toolbarStartX = Math.max(154, this.width - TOOLBAR_RESERVED_WIDTH);
        int x = toolbarStartX;
        x = addToolbarButton(x, EditorIconButton.Icon.BACK, 0xFF63A9D8, "Back one chip level  Alt+Left", canvas::navigateBack);
        x = addToolbarButton(x, EditorIconButton.Icon.SAVE, 0xFF55B96B, "Save chip  Ctrl+S", this::openSaveModal);
        x = addToolbarButton(x, EditorIconButton.Icon.NEW, 0xFF7B8796, "New circuit", this::newCircuit);
        x = addToolbarButton(x, EditorIconButton.Icon.DELETE, 0xFFE05252, "Delete selection  Del / Backspace", this::requestDeleteSelection);
        x += 5;
        x = addToolbarButton(x, EditorIconButton.Icon.WIDTH_DOWN, 0xFF4FA6A0, "Decrease selected width", () -> canvas.changeSelectedWidth(-1));
        x = addToolbarButton(x, EditorIconButton.Icon.WIDTH_UP, 0xFF4FA6A0, "Increase selected width", () -> canvas.changeSelectedWidth(1));
        x += 5;
        x = addToolbarButton(x, EditorIconButton.Icon.FIT, 0xFF7B68D9, "Fit circuit in view", canvas::fitView);
        addToolbarButton(x, EditorIconButton.Icon.HOME, 0xFF7B8796, "Reset view", canvas::resetView);

        initModalWidgets();
        hideModalWidgets();
    }

    private int addToolbarButton(int x, EditorIconButton.Icon icon, int accent, String tooltip, Runnable action) {
        EditorIconButton button = new EditorIconButton(x, 5, 24, icon, accent, tooltip, action);
        toolbarButtons.add(button);
        this.addRenderableWidget(button);
        return x + 28;
    }

    private void initModalWidgets() {
        int modalX = (this.width - MODAL_WIDTH) / 2;
        int modalY = Math.max(44, (this.height - 220) / 2);

        modalNameBox = new EditBox(this.font, modalX + 20, modalY + 52, MODAL_WIDTH - 40, 18, Component.literal("Name"));
        modalNameBox.setMaxLength(48);
        this.addRenderableWidget(modalNameBox);

        int fieldWidth = 82;
        modalWidthBox = new EditBox(this.font, modalX + 20, modalY + 93, fieldWidth, 18, Component.literal("Width"));
        modalWidthBox.setMaxLength(8);
        this.addRenderableWidget(modalWidthBox);

        modalHeightBox = new EditBox(this.font, modalX + 124, modalY + 93, fieldWidth, 18, Component.literal("Height"));
        modalHeightBox.setMaxLength(8);
        this.addRenderableWidget(modalHeightBox);

        modalGapBox = new EditBox(this.font, modalX + 228, modalY + 93, fieldWidth, 18, Component.literal("Pin gap"));
        modalGapBox.setMaxLength(8);
        this.addRenderableWidget(modalGapBox);

        modalPalette = new ColorPaletteWidget(modalX + 20, modalY + 137, MODAL_WIDTH - 40, 16, modalColor, color -> modalColor = color);
        this.addRenderableWidget(modalPalette);

        modalApplyButton = new FlatActionButton(modalX + MODAL_WIDTH - 138, modalY + 174, 58, 20, "APPLY", 0xFF55B96B, this::applyModal);
        this.addRenderableWidget(modalApplyButton);
        modalCancelButton = new FlatActionButton(modalX + MODAL_WIDTH - 74, modalY + 174, 54, 20, "CANCEL", 0xFF7B8796, this::closeModal);
        this.addRenderableWidget(modalCancelButton);
    }

    private void newCircuit() {
        if (modalMode != ModalMode.NONE) return;
        canvas.newDocument();
        currentChipName = null;
        breadcrumb = "ROOT";
        liveNestedRuntime = false;
        setStatus("New untitled circuit");
    }

    private void openSaveModal() {
        if (modalMode != ModalMode.NONE) return;
        modalMode = ModalMode.SAVE_CHIP;
        modalError = "";
        pendingLibraryName = null;
        pendingIo = null;
        pendingDeletion = null;

        String name = currentChipName == null ? "" : currentChipName;
        ChipVisualSettings visual = currentChipName == null ? new ChipVisualSettings() : library.chipVisual(currentChipName);
        modalColor = currentChipName == null ? ClientChipLibrary.DEFAULT_CHIP_COLOR : library.chipColor(currentChipName);

        modalNameBox.setValue(name);
        modalWidthBox.setValue(formatNumber(visual.width));
        modalHeightBox.setValue(formatNumber(visual.minHeight));
        modalGapBox.setValue(formatNumber(visual.portSpacing));
        modalPalette.setSelectedColor(modalColor);
        configureModalWidgets();
        setEditorEnabled(false);
    }

    private void openAddFolderModal() {
        if (modalMode != ModalMode.NONE) return;
        canvas.cancelPlacement();
        modalMode = ModalMode.ADD_FOLDER;
        modalError = "";
        pendingLibraryName = null;
        pendingIo = null;
        pendingDeletion = null;
        modalColor = ClientChipLibrary.DEFAULT_FOLDER_COLOR;
        modalNameBox.setValue("");
        modalPalette.setSelectedColor(modalColor);
        configureModalWidgets();
        setEditorEnabled(false);
    }

    /**
     * F2 belongs to whichever editor surface the user most recently interacted with: canvas I/O
     * or library item. This prevents a stale canvas selection from hijacking an F2 library rename.
     */
    private void openF2EditModal() {
        if (modalMode != ModalMode.NONE) return;
        if (this.getFocused() == componentLibrary) {
            openLibraryEditModal();
            return;
        }
        CircuitCanvasWidget.IoSelection io = canvas.selectedIoSelection();
        if (io != null) {
            openIoEditModal(io);
            return;
        }
        openLibraryEditModal();
    }

    private void openIoEditModal(CircuitCanvasWidget.IoSelection io) {
        canvas.cancelPlacement();
        modalMode = ModalMode.EDIT_IO;
        pendingIo = io;
        pendingLibraryName = null;
        pendingDeletion = null;
        modalError = "";
        modalNameBox.setMaxLength(32);
        modalNameBox.setValue(io.label());
        configureModalWidgets();
        setEditorEnabled(false);
    }

    private void openLibraryEditModal() {
        if (modalMode != ModalMode.NONE) return;
        String selectedChip = componentLibrary.selectedChipName();
        String selectedFolder = componentLibrary.selectedFolderName();

        if (selectedChip != null) {
            canvas.cancelPlacement();
            modalMode = ModalMode.EDIT_LIBRARY_ITEM;
            libraryEditKind = LibraryEditKind.CHIP;
            pendingLibraryName = selectedChip;
            pendingIo = null;
            modalColor = library.chipColor(selectedChip);
            modalNameBox.setMaxLength(48);
            modalNameBox.setValue(selectedChip);
            modalPalette.setSelectedColor(modalColor);
            modalError = "";
            configureModalWidgets();
            setEditorEnabled(false);
            return;
        }

        if (selectedFolder != null && !selectedFolder.isBlank()) {
            modalMode = ModalMode.EDIT_LIBRARY_ITEM;
            libraryEditKind = LibraryEditKind.FOLDER;
            pendingLibraryName = selectedFolder;
            pendingIo = null;
            modalColor = library.folderColor(selectedFolder);
            modalNameBox.setMaxLength(32);
            modalNameBox.setValue(selectedFolder);
            modalPalette.setSelectedColor(modalColor);
            modalError = "";
            configureModalWidgets();
            setEditorEnabled(false);
            return;
        }

        setStatus("Select an INPUT/OUTPUT on the canvas or a saved chip/folder, then press F2");
    }

    private void requestDeleteSelection() {
        if (modalMode != ModalMode.NONE) return;
        CircuitCanvasWidget.DeletionIntent intent = canvas.deletionIntent();
        if (!intent.hasSelection()) {
            setStatus("Nothing selected — click a node or wire first");
            return;
        }
        if (!intent.confirmationRequired()) {
            canvas.deleteSelectionConfirmed();
            return;
        }

        pendingDeletion = intent;
        pendingIo = null;
        modalMode = ModalMode.CONFIRM_DELETE;
        modalError = "";
        configureModalWidgets();
        setEditorEnabled(false);
    }

    private void applyModal() {
        try {
            switch (modalMode) {
                case SAVE_CHIP -> applySave();
                case ADD_FOLDER -> applyAddFolder();
                case EDIT_LIBRARY_ITEM -> applyLibraryEdit();
                case EDIT_IO -> applyIoEdit();
                case CONFIRM_DELETE -> {
                    canvas.deleteSelectionConfirmed();
                    closeModal();
                }
                case NONE -> {
                }
            }
        } catch (RuntimeException | IOException exception) {
            modalError = message(exception);
        }
    }

    private void applySave() throws IOException {
        String name = modalNameBox.getValue().trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Chip name is required");

        if (canvas.isNestedView() && currentChipName != null && !name.equalsIgnoreCase(currentChipName)) {
            throw new IllegalArgumentException("Use F2 to rename a chip while inspecting a live instance");
        }
        if (library.exists(name) && (currentChipName == null || !name.equalsIgnoreCase(currentChipName))) {
            throw new IllegalArgumentException("A chip named '" + name + "' already exists. Open it first or choose another name.");
        }

        double width = parseNumber(modalWidthBox.getValue(), "Width");
        double height = parseNumber(modalHeightBox.getValue(), "Height");
        double gap = parseNumber(modalGapBox.getValue(), "Pin gap");
        ChipVisualSettings visual = new ChipVisualSettings(width, height, gap);

        CircuitCompiler.compile(canvas.document(), library);

        String targetFolder;
        if (currentChipName != null && library.exists(currentChipName)) {
            targetFolder = library.folderOf(currentChipName);
        } else {
            String selectedFolder = componentLibrary.selectedFolderName();
            targetFolder = selectedFolder == null ? "" : selectedFolder;
        }
        library.save(name, canvas.document(), modalColor, visual, targetFolder);

        currentChipName = name;
        canvas.setCurrentChipName(name);
        componentLibrary.selectChip(name);

        boolean nested = canvas.isNestedView();
        if (nested) {
            canvas.refreshLiveRuntime();
        }
        closeModal();
        String folderText = targetFolder == null || targetFolder.isBlank() ? "OTHER" : targetFolder;
        setStatus(nested
                ? "Saved " + name + " in " + folderText + " and rebuilt the running parent"
                : "Saved " + name + " in " + folderText + "  |  body " + formatNumber(visual.width) + "×" + formatNumber(visual.minHeight) + "  pin gap " + formatNumber(visual.portSpacing));
    }

    private void applyAddFolder() throws IOException {
        String name = modalNameBox.getValue().trim();
        library.createFolder(name, modalColor);
        componentLibrary.selectFolder(name);
        closeModal();
        setStatus("Created folder " + name);
    }

    private void applyLibraryEdit() throws IOException {
        String newName = modalNameBox.getValue().trim();
        if (libraryEditKind == LibraryEditKind.CHIP) {
            String oldName = pendingLibraryName;
            if (!oldName.equals(newName)) {
                library.renameChip(oldName, newName);
                canvas.renameCustomChipReferences(oldName, newName);
                componentLibrary.renameSelection(oldName, newName);
                if (currentChipName != null && currentChipName.equalsIgnoreCase(oldName)) {
                    currentChipName = newName;
                    canvas.setCurrentChipName(newName);
                }
            }
            library.setChipColor(newName, modalColor);
            componentLibrary.selectChip(newName);
            canvas.refreshLiveRuntime();
            closeModal();
            setStatus("Updated chip " + newName + " — color and folder metadata persisted");
            return;
        }

        String oldName = pendingLibraryName;
        if (!oldName.equals(newName)) {
            library.renameFolder(oldName, newName);
            componentLibrary.renameSelection(oldName, newName);
        }
        library.setFolderColor(newName, modalColor);
        componentLibrary.selectFolder(newName);
        closeModal();
        setStatus("Updated folder " + newName);
    }

    private void applyIoEdit() {
        String newLabel = modalNameBox.getValue().trim();
        if (!canvas.renameSelectedIo(newLabel)) {
            throw new IllegalStateException("Selected INPUT/OUTPUT is no longer available");
        }
        closeModal();
    }

    private void openChip(String name) {
        try {
            ChipDefinition definition = library.load(name);
            canvas.setDocument(library.copyDocument(definition.circuit), definition.name);
            currentChipName = definition.name;
            componentLibrary.selectChip(definition.name);
            setStatus("Editing " + definition.name + " — double-click any custom chip instance to drill inside it live");
        } catch (RuntimeException | IOException exception) {
            setStatus("LOAD FAILED: " + message(exception));
        }
    }

    private void onCanvasNavigationChanged(CircuitCanvasWidget.NavigationState state) {
        currentChipName = state.currentChipName();
        breadcrumb = state.breadcrumb();
        liveNestedRuntime = state.depth() > 0 && state.liveRuntime();
        if (componentLibrary != null && currentChipName != null) {
            componentLibrary.selectChip(currentChipName);
        }
    }

    private void configureModalWidgets() {
        boolean hasName = modalMode != ModalMode.CONFIRM_DELETE && modalMode != ModalMode.NONE;
        boolean saveLayoutFields = modalMode == ModalMode.SAVE_CHIP;
        boolean hasPalette = modalMode == ModalMode.SAVE_CHIP || modalMode == ModalMode.ADD_FOLDER || modalMode == ModalMode.EDIT_LIBRARY_ITEM;
        if (modalNameBox != null && modalMode != ModalMode.EDIT_IO) {
            modalNameBox.setMaxLength(modalMode == ModalMode.ADD_FOLDER || (modalMode == ModalMode.EDIT_LIBRARY_ITEM && libraryEditKind == LibraryEditKind.FOLDER) ? 32 : 48);
        }
        setWidgetVisible(modalNameBox, hasName);
        setWidgetVisible(modalWidthBox, saveLayoutFields);
        setWidgetVisible(modalHeightBox, saveLayoutFields);
        setWidgetVisible(modalGapBox, saveLayoutFields);
        setWidgetVisible(modalPalette, hasPalette);
        setWidgetVisible(modalApplyButton, modalMode != ModalMode.NONE);
        setWidgetVisible(modalCancelButton, modalMode != ModalMode.NONE);
    }

    private void hideModalWidgets() {
        setWidgetVisible(modalNameBox, false);
        setWidgetVisible(modalWidthBox, false);
        setWidgetVisible(modalHeightBox, false);
        setWidgetVisible(modalGapBox, false);
        setWidgetVisible(modalPalette, false);
        setWidgetVisible(modalApplyButton, false);
        setWidgetVisible(modalCancelButton, false);
    }

    private static void setWidgetVisible(net.minecraft.client.gui.components.AbstractWidget widget, boolean visible) {
        widget.visible = visible;
        widget.active = visible;
    }

    private void closeModal() {
        modalMode = ModalMode.NONE;
        libraryEditKind = null;
        pendingLibraryName = null;
        pendingIo = null;
        pendingDeletion = null;
        modalError = "";
        if (modalNameBox != null) modalNameBox.setMaxLength(48);
        hideModalWidgets();
        setEditorEnabled(true);
    }

    private void setEditorEnabled(boolean enabled) {
        canvas.active = enabled;
        componentLibrary.active = enabled;
        for (EditorIconButton button : toolbarButtons) button.active = enabled;
    }

    private void setStatus(String status) {
        this.status = status == null ? "" : status;
    }

    /**
     * Minecraft 26.2's ContainerEventHandler only forwards drag events for button 0.
     * MouseHandler itself tracks RMB/MMB correctly, so forward those two buttons straight
     * to the canvas at the Screen boundary instead of relying on the vanilla child-drag path.
     */
    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (modalMode == ModalMode.NONE && canvas != null && (event.button() == 1 || event.button() == 2)) {
            return canvas.mouseDragged(event, dx, dy);
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (canvas != null && (event.button() == 1 || event.button() == 2)) {
            if (canvas.mouseReleased(event)) return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();

        if (modalMode != ModalMode.NONE) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                closeModal();
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                applyModal();
                return true;
            }
            return super.keyPressed(event);
        }

        boolean ctrl = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean shift = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        boolean alt = (event.modifiers() & GLFW.GLFW_MOD_ALT) != 0;

        if (ctrl && key == GLFW.GLFW_KEY_S) {
            openSaveModal();
            return true;
        }
        if (ctrl && key == GLFW.GLFW_KEY_D) {
            canvas.duplicateSelection();
            return true;
        }
        if (ctrl && key == GLFW.GLFW_KEY_C) {
            canvas.copySelection();
            return true;
        }
        if (ctrl && key == GLFW.GLFW_KEY_V) {
            canvas.pasteClipboard();
            return true;
        }
        if (key == GLFW.GLFW_KEY_KP_ADD || (key == GLFW.GLFW_KEY_EQUAL && shift)) {
            canvas.addRoutePointToSelection();
            return true;
        }
        if (alt && key == GLFW.GLFW_KEY_LEFT && canvas.canNavigateBack()) {
            canvas.navigateBack();
            return true;
        }
        if (key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE) {
            requestDeleteSelection();
            return true;
        }
        if (key == GLFW.GLFW_KEY_F2) {
            openF2EditModal();
            return true;
        }
        if (key == GLFW.GLFW_KEY_E) {
            canvas.toggleWireEditMode();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && canvas.cancelTransientMode()) {
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, this.width, TOP_BAR_HEIGHT, 0xFF101419);
        graphics.fill(0, TOP_BAR_HEIGHT - 1, this.width, TOP_BAR_HEIGHT, 0xFF303843);
        graphics.fill(0, this.height - STATUS_BAR_HEIGHT, this.width, this.height, 0xFF101419);
        graphics.fill(0, this.height - STATUS_BAR_HEIGHT, this.width, this.height - STATUS_BAR_HEIGHT + 1, 0xFF303843);

        super.extractRenderState(graphics, mouseX, mouseY, delta);

        graphics.text(this.font, "LOGIC", 8, 13, 0xFFE6ECF3, true);
        String path = breadcrumb == null || breadcrumb.isBlank()
                ? currentChipName == null ? "ROOT" : currentChipName
                : breadcrumb;
        String topPath = (liveNestedRuntime ? "LIVE  /  " : "/  ") + path;
        int maxPathChars = Math.max(8, (toolbarStartX - 52) / 6);
        int pathColor = liveNestedRuntime ? 0xFF63C8FF : currentChipName == null ? 0xFF6F7A87 : 0xFF9DA8B5;
        graphics.text(this.font, truncate(topPath, maxPathChars), 48, 13, pathColor, false);

        boolean error = isErrorStatus(status);
        graphics.fill(8, this.height - 17, 13, this.height - 12, error ? 0xFFE05252 : 0xFF55B96B);
        graphics.text(this.font, truncate(status, Math.max(28, (this.width - 32) / 6)), 19, this.height - 19, error ? 0xFFFF8A8A : 0xFFAEB8C4, false);

        if (modalMode == ModalMode.NONE) {
            for (EditorIconButton button : toolbarButtons) {
                if (button.isHovered()) {
                    drawToolbarTooltip(graphics, button.tooltip(), button.getX(), TOP_BAR_HEIGHT + 3);
                    break;
                }
            }
        } else {
            drawModalOverlay(graphics, mouseX, mouseY, delta);
        }
    }

    private void drawToolbarTooltip(GuiGraphicsExtractor graphics, String text, int x, int y) {
        int w = this.font.width(text) + 10;
        int px = Math.min(x, Math.max(4, this.width - w - 4));
        graphics.fill(px, y, px + w, y + 17, 0xF01A2027);
        graphics.outline(px, y, w, 17, 0xFF44505C);
        graphics.text(this.font, text, px + 5, y + 5, 0xFFE1E6EC, false);
    }

    private void drawModalOverlay(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, TOP_BAR_HEIGHT, this.width, this.height - STATUS_BAR_HEIGHT, 0xAA05070A);

        int modalX = (this.width - MODAL_WIDTH) / 2;
        int modalY = Math.max(44, (this.height - 220) / 2);
        graphics.fill(modalX, modalY, modalX + MODAL_WIDTH, modalY + MODAL_HEIGHT, 0xFF151A20);
        graphics.outline(modalX, modalY, MODAL_WIDTH, MODAL_HEIGHT, 0xFF4A5663);
        graphics.fill(modalX, modalY, modalX + 4, modalY + MODAL_HEIGHT, modalAccent());

        graphics.text(this.font, modalTitle(), modalX + 18, modalY + 15, 0xFFF1F4F7, true);
        graphics.text(this.font, modalSubtitle(), modalX + 18, modalY + 30, 0xFF7F8B98, false);

        if (modalMode == ModalMode.SAVE_CHIP) {
            graphics.text(this.font, "NAME", modalX + 20, modalY + 43, 0xFF8B96A3, false);
            graphics.text(this.font, "WIDTH", modalX + 20, modalY + 83, 0xFF8B96A3, false);
            graphics.text(this.font, "HEIGHT", modalX + 124, modalY + 83, 0xFF8B96A3, false);
            graphics.text(this.font, "PIN GAP", modalX + 228, modalY + 83, 0xFF8B96A3, false);
            graphics.text(this.font, "CHIP COLOR", modalX + 20, modalY + 124, 0xFF8B96A3, false);
            graphics.text(this.font, canvas != null && canvas.isNestedView()
                            ? "Saving rebuilds the parent runtime so this exact instance becomes live again."
                            : "Height + pin gap control spacing between exposed inputs/outputs.",
                    modalX + 20, modalY + 158, 0xFF65717E, false);
        } else if (modalMode == ModalMode.ADD_FOLDER) {
            graphics.text(this.font, "NAME", modalX + 20, modalY + 43, 0xFF8B96A3, false);
            graphics.text(this.font, "FOLDER COLOR", modalX + 20, modalY + 124, 0xFF8B96A3, false);
        } else if (modalMode == ModalMode.EDIT_LIBRARY_ITEM) {
            graphics.text(this.font, "NAME", modalX + 20, modalY + 43, 0xFF8B96A3, false);
            graphics.text(this.font, libraryEditKind == LibraryEditKind.CHIP ? "CHIP COLOR" : "FOLDER COLOR", modalX + 20, modalY + 124, 0xFF8B96A3, false);
        } else if (modalMode == ModalMode.EDIT_IO) {
            graphics.text(this.font, "PORT NAME", modalX + 20, modalY + 43, 0xFF8B96A3, false);
            String kind = pendingIo != null && pendingIo.kind() == NodeKind.OUTPUT ? "output" : "input";
            graphics.text(this.font, "This becomes the reusable chip " + kind + " pin name and appears when that pin is hovered.", modalX + 20, modalY + 83, 0xFF7F8B98, false);
            graphics.text(this.font, "Leave it blank to use the automatic IN#/OUT# name.", modalX + 20, modalY + 102, 0xFF65717E, false);
        } else if (modalMode == ModalMode.CONFIRM_DELETE) {
            String text = pendingDeletion == null ? "Delete selected item?" : pendingDeletion.description();
            graphics.text(this.font, text, modalX + 20, modalY + 56, 0xFFE6CDD0, false);
            graphics.text(this.font, "This removes the attached wire connections too.", modalX + 20, modalY + 75, 0xFFB36A72, false);
        }

        if (!modalError.isBlank()) {
            graphics.text(this.font, "! " + truncate(modalError, 50), modalX + 20, modalY + MODAL_HEIGHT - 34, 0xFFFF7878, false);
        }

        if (modalNameBox.visible) modalNameBox.extractRenderState(graphics, mouseX, mouseY, delta);
        if (modalWidthBox.visible) modalWidthBox.extractRenderState(graphics, mouseX, mouseY, delta);
        if (modalHeightBox.visible) modalHeightBox.extractRenderState(graphics, mouseX, mouseY, delta);
        if (modalGapBox.visible) modalGapBox.extractRenderState(graphics, mouseX, mouseY, delta);
        if (modalPalette.visible) modalPalette.extractRenderState(graphics, mouseX, mouseY, delta);
        if (modalApplyButton.visible) modalApplyButton.extractRenderState(graphics, mouseX, mouseY, delta);
        if (modalCancelButton.visible) modalCancelButton.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private int modalAccent() {
        return switch (modalMode) {
            case SAVE_CHIP -> 0xFF55B96B;
            case ADD_FOLDER -> 0xFF4C86D9;
            case EDIT_LIBRARY_ITEM -> modalColor;
            case EDIT_IO -> pendingIo != null && pendingIo.kind() == NodeKind.OUTPUT ? 0xFF7B68D9 : 0xFF4C86D9;
            case CONFIRM_DELETE -> 0xFFE05252;
            case NONE -> 0xFF59636E;
        };
    }

    private String modalTitle() {
        return switch (modalMode) {
            case SAVE_CHIP -> "SAVE CHIP";
            case ADD_FOLDER -> "ADD FOLDER";
            case EDIT_LIBRARY_ITEM -> libraryEditKind == LibraryEditKind.CHIP ? "EDIT CHIP" : "EDIT FOLDER";
            case EDIT_IO -> pendingIo != null && pendingIo.kind() == NodeKind.OUTPUT ? "NAME OUTPUT" : "NAME INPUT";
            case CONFIRM_DELETE -> "CONFIRM DELETE";
            case NONE -> "";
        };
    }

    private String modalSubtitle() {
        return switch (modalMode) {
            case SAVE_CHIP -> canvas != null && canvas.isNestedView() ? "Edit this saved chip inside the running hierarchy" : "Name, color, folder, and reusable body layout";
            case ADD_FOLDER -> "Name and color for the new folder";
            case EDIT_LIBRARY_ITEM -> "F2 quick edit";
            case EDIT_IO -> "F2 names the selected reusable port";
            case CONFIRM_DELETE -> "Connected nodes require confirmation";
            case NONE -> "";
        };
    }

    private static boolean isErrorStatus(String status) {
        if (status == null) return false;
        String upper = status.toUpperCase();
        return upper.contains("ERROR") || upper.contains("FAILED") || upper.contains("MISMATCH") || upper.contains("INVALID");
    }

    private static double parseNumber(String value, String label) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " must be a number");
        }
    }

    private static String formatNumber(double value) {
        if (Math.rint(value) == value) return Long.toString(Math.round(value));
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static String message(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private enum ModalMode {
        NONE,
        SAVE_CHIP,
        ADD_FOLDER,
        EDIT_LIBRARY_ITEM,
        EDIT_IO,
        CONFIRM_DELETE
    }

    private enum LibraryEditKind {
        CHIP,
        FOLDER
    }
}
