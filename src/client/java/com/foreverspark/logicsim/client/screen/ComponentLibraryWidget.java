package com.foreverspark.logicsim.client.screen;

import com.foreverspark.logicsim.client.board.ClientBoardLibrary;
import com.foreverspark.logicsim.client.chip.ClientChipLibrary;
import com.foreverspark.logicsim.client.device.BuiltinDevices;
import com.foreverspark.logicsim.client.screen.v2.ClientEditorPreferences;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Compact component, editable-board, and saved-chip library with Phase 6 search/favorites. */
public final class ComponentLibraryWidget extends AbstractWidget {
    private static final int ROW_HEIGHT = 18;
    private static final int INDENT = 12;
    private static final int HEADER_HEIGHT = 25;
    private static final int FOOTER_HEIGHT = 30;
    private static final int STAR_WIDTH = 15;

    private static final List<ComponentEntry> COMPONENTS = List.of(
            new ComponentEntry("INPUT", "INPUT", NodeKind.INPUT, 0xFF4C86D9, Section.PRIMITIVES, Special.NORMAL, ""),
            new ComponentEntry("OUTPUT", "OUTPUT", NodeKind.OUTPUT, 0xFF7B68D9, Section.PRIMITIVES, Special.NORMAL, ""),
            new ComponentEntry("NAND", "NAND", NodeKind.NAND, 0xFF7B8796, Section.PRIMITIVES, Special.NORMAL, ""),
            new ComponentEntry("CONSTANT", "CONSTANT", NodeKind.CONSTANT, 0xFFD29A45, Section.INFRASTRUCTURE, Special.NORMAL, ""),
            new ComponentEntry("CLOCK", "CLOCK", NodeKind.CONSTANT, 0xFF5FA8FF, Section.INFRASTRUCTURE, Special.CLOCK, ""),
            new ComponentEntry("RANDOM", "RANDOM", NodeKind.CONSTANT, 0xFFB06CE8, Section.INFRASTRUCTURE, Special.RANDOM, ""),
            new ComponentEntry("PROBE", "PROBE", NodeKind.PROBE, 0xFF63A9D8, Section.INFRASTRUCTURE, Special.NORMAL, ""),
            new ComponentEntry("BUS", "BUS LINE", NodeKind.BUS, 0xFF4FA6A0, Section.ROUTING, Special.NORMAL, ""),
            new ComponentEntry("SPLITTER", "BUS -> BITS", NodeKind.SPLITTER, 0xFF4FA6A0, Section.ROUTING, Special.NORMAL, ""),
            new ComponentEntry("MERGER", "BITS -> BUS", NodeKind.MERGER, 0xFF4FA6A0, Section.ROUTING, Special.NORMAL, ""),
            new ComponentEntry("BUS_SLICE", "BUS SLICE", NodeKind.BUS_SLICE, 0xFF55AFC2, Section.ROUTING, Special.BUS_SLICE, "1-64"),
            new ComponentEntry("NET_LABEL", "NET LABEL", NodeKind.NET_LABEL, 0xFF8E73D8, Section.ROUTING, Special.NET_LABEL, "named net")
    );

    private final ClientChipLibrary library;
    private final ClientBoardLibrary boards = new ClientBoardLibrary();
    private final ClientEditorPreferences preferences = new ClientEditorPreferences();
    private final CircuitCanvasWidget canvas;
    private final Runnable requestAdd;
    private final Consumer<String> openChip;
    private final Consumer<String> status;
    private final List<RowHit> rows = new ArrayList<>();

    private Consumer<String> openBoard = ignored -> {};
    private String selectedBoard;
    private String selectedFolder;
    private String selectedChip;
    private String draggingChip;
    private boolean dragMoved;
    private double scroll;
    private double maxScroll;
    private String searchQuery = "";
    private boolean searchFocused;

    public ComponentLibraryWidget(int x, int y, int width, int height, ClientChipLibrary library, CircuitCanvasWidget canvas, Runnable requestAdd, Consumer<String> openChip, Consumer<String> status) {
        super(x, y, width, height, Component.literal("Component library"));
        this.library = library;
        this.canvas = canvas;
        this.requestAdd = requestAdd;
        this.openChip = openChip;
        this.status = status;
    }

    public void setBoardOpenHandler(Consumer<String> openBoard) {
        this.openBoard = openBoard == null ? ignored -> {} : openBoard;
    }

    public String selectedBoardName() { return selectedBoard; }
    public String selectedChipName() { return selectedChip; }
    public String selectedFolderName() { return selectedFolder; }
    public boolean searchFocused() { return searchFocused; }

    public void beginSearch() {
        searchFocused = true;
        scroll = 0;
        status.accept("LIBRARY SEARCH: type a component, chip, or board name — Esc clears");
    }

    public void selectBoard(String name) {
        selectedBoard = name;
        selectedChip = null;
        selectedFolder = null;
    }

    public void selectChip(String name) {
        selectedChip = name;
        selectedFolder = null;
        selectedBoard = null;
    }

    public void selectFolder(String name) {
        selectedFolder = name;
        selectedChip = null;
        selectedBoard = null;
    }

    public void renameSelection(String oldName, String newName) {
        if (oldName != null && oldName.equals(selectedChip)) selectedChip = newName;
        if (oldName != null && oldName.equals(selectedFolder)) selectedFolder = newName;
        if (oldName != null && oldName.equals(draggingChip)) draggingChip = newName;
        if (oldName != null && newName != null) {
            try {
                preferences.renameFavorite(chipKey(oldName), chipKey(newName));
            } catch (IOException exception) {
                status.accept("Favorite rename failed: " + exception.getMessage());
            }
        }
    }

    public void renameBoardSelection(String oldName, String newName) {
        if (oldName != null && oldName.equals(selectedBoard)) selectedBoard = newName;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        EditorClockRuntime.attach(canvas);
        var font = Minecraft.getInstance().font;
        graphics.fill(getX(), getY(), getX() + width, getY() + height, 0xF015191E);
        graphics.outline(getX(), getY(), width, height, 0xFF343C46);
        graphics.text(font, "LIBRARY", getX() + 8, getY() + 8, 0xFFE7ECF2, true);
        int contentTop = getY() + HEADER_HEIGHT;
        int footerTop = getY() + height - FOOTER_HEIGHT;
        int viewportHeight = Math.max(20, footerTop - contentTop - 2);
        int y = contentTop - (int) Math.round(scroll);
        rows.clear();

        if (searchQuery.isBlank()) y = drawNormalLibrary(graphics, y, contentTop, footerTop);
        else y = drawSearchResults(graphics, y, contentTop, footerTop);

        int contentHeight = Math.max(0, y + (int) Math.round(scroll) - contentTop);
        maxScroll = Math.max(0, contentHeight - viewportHeight);
        scroll = clamp(scroll, 0, maxScroll);
        drawFooter(graphics, footerTop);
    }

    private int drawNormalLibrary(GuiGraphicsExtractor graphics, int y, int contentTop, int footerTop) {
        if (hasResolvableFavorites()) {
            y = drawSection(graphics, "FAVORITES", y, contentTop, footerTop);
            for (String key : preferences.favorites()) {
                ComponentEntry component = componentForKey(key);
                if (component != null) y = drawComponent(graphics, component, y, contentTop, footerTop);
                else if (key.startsWith("chip:")) {
                    String chip = key.substring("chip:".length());
                    if (library.exists(chip) && visibleChip(chip)) y = drawChip(graphics, chip, y, contentTop, footerTop);
                }
            }
            y += 4;
        }

        y = drawSection(graphics, "BOARDS", y, contentTop, footerTop);
        List<String> boardNames = boards.names();
        if (boardNames.isEmpty()) y = drawHint(graphics, "Ctrl+S saves this board", y, contentTop, footerTop);
        else for (String board : boardNames) y = drawBoard(graphics, board, y, contentTop, footerTop);

        y += 4;
        y = drawComponentSection(graphics, Section.PRIMITIVES, "PRIMITIVES", y, contentTop, footerTop);
        y += 3;
        y = drawComponentSection(graphics, Section.INFRASTRUCTURE, "INFRASTRUCTURE", y, contentTop, footerTop);
        y += 3;
        y = drawComponentSection(graphics, Section.ROUTING, "ROUTING", y, contentTop, footerTop);

        y += 5;
        y = drawSection(graphics, "MY CHIPS", y, contentTop, footerTop);
        for (ClientChipLibrary.FolderInfo folder : library.folders()) {
            y = drawFolder(graphics, folder, y, contentTop, footerTop);
            if (folder.expanded()) for (String chip : library.chipsInFolder(folder.name())) y = drawChip(graphics, chip, y, contentTop, footerTop);
        }
        y = drawOtherFolder(graphics, y, contentTop, footerTop);
        for (String chip : library.unfiledChips()) y = drawChip(graphics, chip, y, contentTop, footerTop);
        return y;
    }

    private int drawSearchResults(GuiGraphicsExtractor graphics, int y, int contentTop, int footerTop) {
        y = drawSection(graphics, "SEARCH RESULTS", y, contentTop, footerTop);
        int matches = 0;
        for (String board : boards.names()) {
            if (!matchesSearch(board)) continue;
            y = drawBoard(graphics, board, y, contentTop, footerTop);
            matches++;
        }
        for (ComponentEntry component : COMPONENTS) {
            if (!matchesSearch(component.name())) continue;
            y = drawComponent(graphics, component, y, contentTop, footerTop);
            matches++;
        }
        for (String chip : library.names()) {
            if (!visibleChip(chip) || !matchesSearch(chip)) continue;
            y = drawChip(graphics, chip, y, contentTop, footerTop);
            matches++;
        }
        if (matches == 0) y = drawHint(graphics, "No matches for " + searchQuery, y, contentTop, footerTop);
        return y;
    }

    private int drawComponentSection(GuiGraphicsExtractor graphics, Section section, String title, int y, int clipTop, int clipBottom) {
        y = drawSection(graphics, title, y, clipTop, clipBottom);
        for (ComponentEntry component : COMPONENTS) if (component.section() == section) y = drawComponent(graphics, component, y, clipTop, clipBottom);
        return y;
    }

    private int drawSection(GuiGraphicsExtractor graphics, String text, int y, int clipTop, int clipBottom) {
        if (visibleRow(y, 14, clipTop, clipBottom)) graphics.text(Minecraft.getInstance().font, text, getX() + 7, y + 3, 0xFF737E8B, false);
        return y + 14;
    }

    private int drawHint(GuiGraphicsExtractor graphics, String text, int y, int clipTop, int clipBottom) {
        if (visibleRow(y, ROW_HEIGHT, clipTop, clipBottom)) graphics.text(Minecraft.getInstance().font, truncate(text, 25), getX() + 13, y + 5, 0xFF66727F, false);
        return y + ROW_HEIGHT + 1;
    }

    private int drawBoard(GuiGraphicsExtractor graphics, String boardName, int y, int clipTop, int clipBottom) {
        if (visibleRow(y, ROW_HEIGHT, clipTop, clipBottom)) {
            boolean selected = boardName.equals(selectedBoard);
            graphics.fill(getX() + 5, y, getX() + width - 5, y + ROW_HEIGHT - 1, selected ? 0xFF263441 : 0xFF181F26);
            graphics.fill(getX() + 5, y, getX() + 9, y + ROW_HEIGHT - 1, 0xFF63A9D8);
            graphics.text(Minecraft.getInstance().font, "▣", getX() + 14, y + 5, 0xFF9ED2F0, false);
            graphics.text(Minecraft.getInstance().font, truncate(boardName, 19), getX() + 27, y + 5, selected ? 0xFFFFFFFF : 0xFFD7DEE8, false);
            rows.add(new RowHit(RowType.BOARD, boardName, null, null, y, ROW_HEIGHT, null));
        }
        return y + ROW_HEIGHT + 1;
    }

    private int drawComponent(GuiGraphicsExtractor graphics, ComponentEntry component, int y, int clipTop, int clipBottom) {
        if (visibleRow(y, ROW_HEIGHT, clipTop, clipBottom)) {
            String favoriteKey = componentKey(component);
            graphics.fill(getX() + 5, y, getX() + width - 5, y + ROW_HEIGHT - 1, 0xFF1C2229);
            graphics.fill(getX() + 5, y, getX() + 8, y + ROW_HEIGHT - 1, component.color());
            graphics.text(Minecraft.getInstance().font, truncate(component.name(), component.badge().isBlank() ? 18 : 12), getX() + 13, y + 5, 0xFFD7DEE8, false);
            String badge = componentBadge(component);
            if (!badge.isBlank()) {
                int right = getX() + width - STAR_WIDTH - 9;
                int badgeX = right - Minecraft.getInstance().font.width(badge);
                graphics.text(Minecraft.getInstance().font, badge, Math.max(getX() + 75, badgeX), y + 5, 0xFF7FA7C8, false);
            }
            drawFavoriteStar(graphics, favoriteKey, y);
            rows.add(new RowHit(RowType.COMPONENT, component.name(), component.kind(), component, y, ROW_HEIGHT, favoriteKey));
        }
        return y + ROW_HEIGHT + 1;
    }

    private int drawFolder(GuiGraphicsExtractor graphics, ClientChipLibrary.FolderInfo folder, int y, int clipTop, int clipBottom) {
        if (visibleRow(y, ROW_HEIGHT, clipTop, clipBottom)) {
            boolean selected = folder.name().equals(selectedFolder);
            graphics.fill(getX() + 5, y, getX() + width - 5, y + ROW_HEIGHT - 1, selected ? 0xFF2A3038 : 0xFF1B2026);
            graphics.fill(getX() + 5, y, getX() + 9, y + ROW_HEIGHT - 1, folder.color());
            graphics.text(Minecraft.getInstance().font, folder.expanded() ? "v" : ">", getX() + 14, y + 5, 0xFFAEB7C2, false);
            graphics.text(Minecraft.getInstance().font, truncate(folder.name(), 19), getX() + 25, y + 5, selected ? 0xFFFFFFFF : 0xFFD7DEE8, false);
            rows.add(new RowHit(RowType.FOLDER, folder.name(), null, null, y, ROW_HEIGHT, null));
        }
        return y + ROW_HEIGHT + 1;
    }

    private int drawOtherFolder(GuiGraphicsExtractor graphics, int y, int clipTop, int clipBottom) {
        if (visibleRow(y, ROW_HEIGHT, clipTop, clipBottom)) {
            boolean selected = "".equals(selectedFolder);
            graphics.fill(getX() + 5, y, getX() + width - 5, y + ROW_HEIGHT - 1, selected ? 0xFF2A3038 : 0xFF1B2026);
            graphics.fill(getX() + 5, y, getX() + 9, y + ROW_HEIGHT - 1, 0xFF606A76);
            graphics.text(Minecraft.getInstance().font, "OTHER", getX() + 25, y + 5, 0xFFD7DEE8, false);
            rows.add(new RowHit(RowType.OTHER, "", null, null, y, ROW_HEIGHT, null));
        }
        return y + ROW_HEIGHT + 1;
    }

    private int drawChip(GuiGraphicsExtractor graphics, String chipName, int y, int clipTop, int clipBottom) {
        if (!visibleChip(chipName)) return y;
        if (visibleRow(y, ROW_HEIGHT, clipTop, clipBottom)) {
            boolean selected = chipName.equals(selectedChip);
            String favoriteKey = chipKey(chipName);
            int left = getX() + 5 + INDENT;
            graphics.fill(left, y, getX() + width - 5, y + ROW_HEIGHT - 1, selected ? 0xFF29313A : 0xFF181D23);
            graphics.fill(left + 4, y + 4, left + 12, y + 12, library.chipColor(chipName));
            graphics.text(Minecraft.getInstance().font, truncate(chipName, 15), left + 17, y + 5, selected ? 0xFFFFFFFF : 0xFFC8D0DA, false);
            drawFavoriteStar(graphics, favoriteKey, y);
            rows.add(new RowHit(RowType.CHIP, chipName, null, null, y, ROW_HEIGHT, favoriteKey));
        }
        return y + ROW_HEIGHT + 1;
    }

    private void drawFavoriteStar(GuiGraphicsExtractor graphics, String favoriteKey, int y) {
        boolean favorite = preferences.isFavorite(favoriteKey);
        int x = getX() + width - STAR_WIDTH - 5;
        graphics.text(Minecraft.getInstance().font, favorite ? "★" : "☆", x + 2, y + 5, favorite ? 0xFFFFD56A : 0xFF66717D, false);
    }

    private void drawFooter(GuiGraphicsExtractor graphics, int footerTop) {
        var font = Minecraft.getInstance().font;
        graphics.fill(getX() + 1, footerTop, getX() + width - 1, getY() + height - 1, 0xFF101419);
        int bx = getX() + 6;
        int by = footerTop + 5;
        graphics.fill(bx, by, bx + 20, by + 20, 0xFF20262D);
        graphics.outline(bx, by, 20, 20, 0xFF46515D);
        graphics.fill(bx + 5, by + 9, bx + 15, by + 11, 0xFFE5EBF2);
        graphics.fill(bx + 9, by + 5, bx + 11, by + 15, 0xFFE5EBF2);

        int searchLeft = bx + 27;
        int searchRight = searchRight();
        graphics.fill(searchLeft, by, searchRight, by + 20, 0xFF151B21);
        graphics.outline(searchLeft, by, Math.max(1, searchRight - searchLeft), 20, searchFocused ? 0xFF63A9D8 : 0xFF3D4853);
        String shown = searchQuery.isBlank() ? "SEARCH...  Ctrl+F" : searchQuery;
        graphics.text(font, truncate(shown, searchQuery.isBlank() ? 18 : 15), searchLeft + 5, by + 7, searchQuery.isBlank() ? 0xFF687482 : 0xFFE1E7EE, false);
        if (!searchQuery.isBlank()) graphics.text(font, "x", searchRight - 10, by + 7, 0xFF9CA7B2, false);

        if (selectedFolder != null && !selectedFolder.isBlank()) {
            int tx = getX() + width - 23;
            graphics.outline(tx, by, 17, 20, 0xFF684148);
            graphics.text(font, "x", tx + 6, by + 7, 0xFFE6A9B0, false);
        }
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        if (!contains(mx, my)) return;
        int footerTop = getY() + height - FOOTER_HEIGHT;
        if (my >= footerTop) {
            int by = footerTop + 5;
            if (event.button() == 0 && mx >= getX() + 6 && mx < getX() + 26 && my >= by && my < by + 20) {
                searchFocused = false;
                requestAdd.run();
                return;
            }
            if (event.button() == 0 && selectedFolder != null && !selectedFolder.isBlank() && mx >= getX() + width - 23 && mx < getX() + width - 6 && my >= by && my < by + 20) {
                searchFocused = false;
                try {
                    String removed = selectedFolder;
                    library.deleteFolder(removed);
                    selectedFolder = null;
                    status.accept("Deleted folder " + removed + "; its chips moved to OTHER");
                } catch (IOException | RuntimeException exception) {
                    status.accept("Delete folder failed: " + exception.getMessage());
                }
                return;
            }
            int searchLeft = getX() + 33;
            int searchRight = searchRight();
            if (event.button() == 0 && mx >= searchLeft && mx < searchRight && my >= by && my < by + 20) {
                searchFocused = true;
                if (!searchQuery.isBlank() && mx >= searchRight - 15) {
                    searchQuery = "";
                    scroll = 0;
                    status.accept("Library search cleared");
                } else status.accept("LIBRARY SEARCH: type a component, chip, or board name — Esc clears");
            }
            return;
        }

        searchFocused = false;
        RowHit row = rowAt(mx, my);
        if (row == null) return;
        if (event.button() == 0 && row.favoriteKey != null && favoriteStarHit(mx)) {
            toggleFavorite(row.favoriteKey, row.name);
            return;
        }

        if (row.type == RowType.BOARD) {
            selectedBoard = row.name;
            selectedChip = null;
            selectedFolder = null;
            canvas.cancelPlacement();
            if (event.button() == 1 || (event.button() == 0 && doubleClick)) openBoard.accept(row.name);
            else if (event.button() == 0) status.accept("Selected board " + row.name + " — double-click to open and continue editing it");
            return;
        }

        if (row.type == RowType.COMPONENT) {
            selectedChip = null;
            selectedFolder = null;
            selectedBoard = null;
            handleComponentClick(row.component, event);
            return;
        }
        if ((row.type == RowType.FOLDER || row.type == RowType.OTHER) && event.button() == 0) {
            selectedChip = null;
            selectedBoard = null;
            selectedFolder = row.name;
            if (row.type == RowType.FOLDER) {
                try {
                    library.setFolderExpanded(row.name, !library.folderExpanded(row.name));
                } catch (IOException exception) {
                    status.accept("Folder error: " + exception.getMessage());
                }
            }
            return;
        }
        if (row.type == RowType.CHIP) {
            selectedChip = row.name;
            selectedFolder = null;
            selectedBoard = null;
            if (event.button() == 1 || (event.button() == 0 && doubleClick)) {
                draggingChip = null;
                dragMoved = false;
                canvas.cancelPlacement();
                openChip.accept(row.name);
                return;
            }
            if (event.button() == 0) {
                draggingChip = row.name;
                dragMoved = false;
                canvas.setCustomChipPlacement(row.name);
            }
        }
    }

    private void handleComponentClick(ComponentEntry component, MouseButtonEvent event) {
        if (component == null) return;
        switch (component.special()) {
            case CLOCK -> {
                if (event.button() == 0) {
                    RandomPlacementState.disarm();
                    ClockPlacementState.arm(canvas);
                    canvas.setPlacement(NodeKind.CONSTANT);
                    status.accept("Place CLOCK at " + EditorNode.formatFrequency(ClockPlacementState.frequencyHz())
                            + " — right-click CLOCK to enter exact Hz / kHz / MHz");
                } else if (event.button() == 1) {
                    var parent = EditorScreenContext.current();
                    Minecraft.getInstance().gui.setScreen(SourceConfigScreen.clock(parent, ClockPlacementState.frequencyHz(), hz -> {
                        ClockPlacementState.setFrequencyHz(hz);
                        status.accept("CLOCK placement frequency = " + EditorNode.formatFrequency(hz));
                    }));
                } else if (event.button() == 2) {
                    boolean running = EditorClockRuntime.toggleAll(canvas);
                    status.accept(running ? "CLOCKS RUNNING" : "CLOCKS PAUSED");
                }
            }
            case RANDOM -> {
                if (event.button() == 0) {
                    ClockPlacementState.disarm();
                    RandomPlacementState.arm(canvas);
                    canvas.setPlacement(NodeKind.CONSTANT);
                    status.accept("Place RANDOM — " + RandomPlacementState.chancePercent()
                            + "% chance of HIGH on each TRIGGER 0 -> 1 edge. Right-click RANDOM to set the chance.");
                } else if (event.button() == 1) {
                    var parent = EditorScreenContext.current();
                    Minecraft.getInstance().gui.setScreen(SourceConfigScreen.random(parent, RandomPlacementState.chancePercent(), chance -> {
                        RandomPlacementState.setChancePercent(chance);
                        status.accept("RANDOM placement chance = " + chance + "% HIGH per rising edge");
                    }));
                }
            }
            case BUS_SLICE -> {
                if (event.button() == 0) {
                    canvas.setPlacement(NodeKind.BUS_SLICE);
                    status.accept("Place BUS SLICE — double-click it or press W to define ranges like OPCODE=12:4, OPERAND=0:12");
                }
            }
            case NET_LABEL -> {
                if (event.button() == 0) {
                    canvas.setPlacement(NodeKind.NET_LABEL);
                    status.accept("Place NET LABEL — double-click it or press W to name the electrical net and set its width");
                }
            }
            case NORMAL -> {
                if (event.button() == 0) canvas.setPlacement(component.kind());
            }
        }
    }

    private void toggleFavorite(String key, String label) {
        try {
            boolean favorite = preferences.toggleFavorite(key);
            status.accept((favorite ? "Pinned " : "Unpinned ") + label + (favorite ? " to FAVORITES" : " from FAVORITES"));
        } catch (IOException | RuntimeException exception) {
            status.accept("Favorite update failed: " + exception.getMessage());
        }
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        if (event.button() == 0 && draggingChip != null && Math.abs(dx) + Math.abs(dy) > 0.01) dragMoved = true;
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        if (event.button() != 0 || draggingChip == null) return;
        if (dragMoved) {
            RowHit target = rowAt(event.x(), event.y());
            if (target != null && (target.type == RowType.FOLDER || target.type == RowType.OTHER)) {
                try {
                    library.moveChipToFolder(draggingChip, target.name);
                    canvas.cancelPlacement();
                    selectedChip = draggingChip;
                    selectedBoard = null;
                    status.accept(target.name.isBlank() ? "Moved " + draggingChip + " to OTHER" : "Moved " + draggingChip + " to " + target.name);
                } catch (IOException | RuntimeException exception) {
                    status.accept("Move failed: " + exception.getMessage());
                }
            }
        }
        draggingChip = null;
        dragMoved = false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!contains(mouseX, mouseY)) return false;
        double amount = scrollY != 0 ? scrollY : scrollX;
        scroll = clamp(scroll - amount * 28.0, 0, maxScroll);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!searchFocused) return super.keyPressed(event);
        int key = event.key();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (!searchQuery.isBlank()) {
                searchQuery = "";
                scroll = 0;
                status.accept("Library search cleared");
            } else searchFocused = false;
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            searchFocused = false;
            return true;
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE) {
            if (!searchQuery.isEmpty()) searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
            scroll = 0;
            return true;
        }
        Character typed = searchCharacter(event);
        if (typed != null && searchQuery.length() < 48) {
            searchQuery += typed;
            scroll = 0;
            return true;
        }
        return true;
    }

    private Character searchCharacter(KeyEvent event) {
        int key = event.key();
        boolean shift = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z) return (char)('a' + (key - GLFW.GLFW_KEY_A));
        if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) return (char)('0' + (key - GLFW.GLFW_KEY_0));
        if (key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_9) return (char)('0' + (key - GLFW.GLFW_KEY_KP_0));
        if (key == GLFW.GLFW_KEY_SPACE) return ' ';
        if (key == GLFW.GLFW_KEY_MINUS) return shift ? '_' : '-';
        if (key == GLFW.GLFW_KEY_PERIOD) return '.';
        return null;
    }

    private boolean matchesSearch(String text) {
        String needle = searchQuery.trim().toLowerCase(Locale.ROOT);
        return needle.isEmpty() || (text != null && text.toLowerCase(Locale.ROOT).contains(needle));
    }

    private boolean hasResolvableFavorites() {
        for (String key : preferences.favorites()) {
            if (componentForKey(key) != null) return true;
            if (key.startsWith("chip:")) {
                String chip = key.substring("chip:".length());
                if (library.exists(chip) && visibleChip(chip)) return true;
            }
        }
        return false;
    }

    private ComponentEntry componentForKey(String key) {
        if (key == null || !key.startsWith("component:")) return null;
        String id = key.substring("component:".length());
        for (ComponentEntry component : COMPONENTS) if (component.id().equalsIgnoreCase(id)) return component;
        return null;
    }

    private String componentBadge(ComponentEntry component) {
        return switch (component.special()) {
            case CLOCK -> EditorNode.formatFrequency(ClockPlacementState.frequencyHz());
            case RANDOM -> RandomPlacementState.chancePercent() + "%";
            default -> component.badge();
        };
    }

    private static String componentKey(ComponentEntry component) { return "component:" + component.id(); }
    private static String chipKey(String chipName) { return "chip:" + (chipName == null ? "" : chipName); }
    private static boolean visibleChip(String chipName) { return !BuiltinDevices.isRemovedFake(chipName) && !BuiltinDevices.isDisplay(chipName); }

    private int searchRight() {
        return selectedFolder != null && !selectedFolder.isBlank() ? getX() + width - 27 : getX() + width - 6;
    }

    private boolean favoriteStarHit(double mx) {
        return mx >= getX() + width - STAR_WIDTH - 5 && mx < getX() + width - 5;
    }

    private RowHit rowAt(double mx, double my) {
        if (mx < getX() || mx >= getX() + width) return null;
        for (int i = rows.size() - 1; i >= 0; i--) {
            RowHit row = rows.get(i);
            if (my >= row.y && my < row.y + row.height) return row;
        }
        return null;
    }

    private boolean visibleRow(int y, int h, int top, int bottom) { return y + h > top && y < bottom; }
    private boolean contains(double x, double y) { return x >= getX() && x < getX() + width && y >= getY() && y < getY() + height; }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private static String truncate(String text, int max) { if (text == null) return ""; return text.length() <= max ? text : text.substring(0, Math.max(0, max - 1)) + "…"; }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput builder) {}

    private enum RowType { BOARD, COMPONENT, FOLDER, OTHER, CHIP }
    private enum Section { PRIMITIVES, INFRASTRUCTURE, ROUTING }
    private enum Special { NORMAL, CLOCK, RANDOM, BUS_SLICE, NET_LABEL }
    private record ComponentEntry(String id, String name, NodeKind kind, int color, Section section, Special special, String badge) {}
    private record RowHit(RowType type, String name, NodeKind nodeKind, ComponentEntry component, int y, int height, String favoriteKey) {}
}
