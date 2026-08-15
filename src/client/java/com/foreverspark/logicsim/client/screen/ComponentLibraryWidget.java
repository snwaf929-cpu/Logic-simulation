package com.foreverspark.logicsim.client.screen;

import com.foreverspark.logicsim.client.board.ClientBoardLibrary;
import com.foreverspark.logicsim.client.chip.ClientChipLibrary;
import com.foreverspark.logicsim.editor.model.NodeKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Compact component, editable-board, and saved-chip library. */
public final class ComponentLibraryWidget extends AbstractWidget {
    private static final int ROW_HEIGHT = 18;
    private static final int INDENT = 12;
    private static final int HEADER_HEIGHT = 25;
    private static final int FOOTER_HEIGHT = 30;

    private final ClientChipLibrary library;
    private final ClientBoardLibrary boards = new ClientBoardLibrary();
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
    }

    public void renameBoardSelection(String oldName, String newName) {
        if (oldName != null && oldName.equals(selectedBoard)) selectedBoard = newName;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        var font = Minecraft.getInstance().font;
        graphics.fill(getX(), getY(), getX() + width, getY() + height, 0xF015191E);
        graphics.outline(getX(), getY(), width, height, 0xFF343C46);
        graphics.text(font, "LIBRARY", getX() + 8, getY() + 8, 0xFFE7ECF2, true);
        int contentTop = getY() + HEADER_HEIGHT;
        int footerTop = getY() + height - FOOTER_HEIGHT;
        int viewportHeight = Math.max(20, footerTop - contentTop - 2);
        int y = contentTop - (int) Math.round(scroll);
        rows.clear();

        y = drawSection(graphics, "BOARDS", y, contentTop, footerTop);
        List<String> boardNames = boards.names();
        if (boardNames.isEmpty()) {
            y = drawHint(graphics, "Ctrl+S saves this board", y, contentTop, footerTop);
        } else {
            for (String board : boardNames) y = drawBoard(graphics, board, y, contentTop, footerTop);
        }

        y += 4;
        y = drawSection(graphics, "PRIMITIVES", y, contentTop, footerTop);
        y = drawComponent(graphics, "INPUT", NodeKind.INPUT, 0xFF4C86D9, y, contentTop, footerTop);
        y = drawComponent(graphics, "OUTPUT", NodeKind.OUTPUT, 0xFF7B68D9, y, contentTop, footerTop);
        y = drawComponent(graphics, "NAND", NodeKind.NAND, 0xFF7B8796, y, contentTop, footerTop);

        y += 3;
        y = drawSection(graphics, "INFRASTRUCTURE", y, contentTop, footerTop);
        y = drawComponent(graphics, "CONSTANT", NodeKind.CONSTANT, 0xFFD29A45, y, contentTop, footerTop);
        y = drawComponent(graphics, "PROBE", NodeKind.PROBE, 0xFF63A9D8, y, contentTop, footerTop);

        y += 3;
        y = drawSection(graphics, "ROUTING", y, contentTop, footerTop);
        y = drawComponent(graphics, "BUS LINE", NodeKind.BUS, 0xFF4FA6A0, y, contentTop, footerTop);
        y = drawComponent(graphics, "BUS -> BITS", NodeKind.SPLITTER, 0xFF4FA6A0, y, contentTop, footerTop);
        y = drawComponent(graphics, "BITS -> BUS", NodeKind.MERGER, 0xFF4FA6A0, y, contentTop, footerTop);

        y += 5;
        y = drawSection(graphics, "MY CHIPS", y, contentTop, footerTop);
        for (ClientChipLibrary.FolderInfo folder : library.folders()) {
            y = drawFolder(graphics, folder, y, contentTop, footerTop);
            if (folder.expanded()) for (String chip : library.chipsInFolder(folder.name())) y = drawChip(graphics, chip, y, contentTop, footerTop);
        }
        y = drawOtherFolder(graphics, y, contentTop, footerTop);
        for (String chip : library.unfiledChips()) y = drawChip(graphics, chip, y, contentTop, footerTop);

        int contentHeight = Math.max(0, y + (int) Math.round(scroll) - contentTop);
        maxScroll = Math.max(0, contentHeight - viewportHeight);
        scroll = clamp(scroll, 0, maxScroll);
        drawFooter(graphics, footerTop);
    }

    private int drawSection(GuiGraphicsExtractor graphics, String text, int y, int clipTop, int clipBottom) {
        if (visibleRow(y, 14, clipTop, clipBottom)) graphics.text(Minecraft.getInstance().font, text, getX() + 7, y + 3, 0xFF737E8B, false);
        return y + 14;
    }

    private int drawHint(GuiGraphicsExtractor graphics, String text, int y, int clipTop, int clipBottom) {
        if (visibleRow(y, ROW_HEIGHT, clipTop, clipBottom)) {
            graphics.text(Minecraft.getInstance().font, truncate(text, 25), getX() + 13, y + 5, 0xFF66727F, false);
        }
        return y + ROW_HEIGHT + 1;
    }

    private int drawBoard(GuiGraphicsExtractor graphics, String boardName, int y, int clipTop, int clipBottom) {
        if (visibleRow(y, ROW_HEIGHT, clipTop, clipBottom)) {
            boolean selected = boardName.equals(selectedBoard);
            graphics.fill(getX() + 5, y, getX() + width - 5, y + ROW_HEIGHT - 1, selected ? 0xFF263441 : 0xFF181F26);
            graphics.fill(getX() + 5, y, getX() + 9, y + ROW_HEIGHT - 1, 0xFF63A9D8);
            graphics.text(Minecraft.getInstance().font, "▣", getX() + 14, y + 5, 0xFF9ED2F0, false);
            graphics.text(Minecraft.getInstance().font, truncate(boardName, 19), getX() + 27, y + 5, selected ? 0xFFFFFFFF : 0xFFD7DEE8, false);
            rows.add(new RowHit(RowType.BOARD, boardName, null, y, ROW_HEIGHT));
        }
        return y + ROW_HEIGHT + 1;
    }

    private int drawComponent(GuiGraphicsExtractor graphics, String name, NodeKind kind, int color, int y, int clipTop, int clipBottom) {
        if (visibleRow(y, ROW_HEIGHT, clipTop, clipBottom)) {
            graphics.fill(getX() + 5, y, getX() + width - 5, y + ROW_HEIGHT - 1, 0xFF1C2229);
            graphics.fill(getX() + 5, y, getX() + 8, y + ROW_HEIGHT - 1, color);
            graphics.text(Minecraft.getInstance().font, name, getX() + 13, y + 5, 0xFFD7DEE8, false);
            rows.add(new RowHit(RowType.COMPONENT, name, kind, y, ROW_HEIGHT));
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
            rows.add(new RowHit(RowType.FOLDER, folder.name(), null, y, ROW_HEIGHT));
        }
        return y + ROW_HEIGHT + 1;
    }

    private int drawOtherFolder(GuiGraphicsExtractor graphics, int y, int clipTop, int clipBottom) {
        if (visibleRow(y, ROW_HEIGHT, clipTop, clipBottom)) {
            boolean selected = "".equals(selectedFolder);
            graphics.fill(getX() + 5, y, getX() + width - 5, y + ROW_HEIGHT - 1, selected ? 0xFF2A3038 : 0xFF1B2026);
            graphics.fill(getX() + 5, y, getX() + 9, y + ROW_HEIGHT - 1, 0xFF606A76);
            graphics.text(Minecraft.getInstance().font, "OTHER", getX() + 25, y + 5, 0xFFD7DEE8, false);
            rows.add(new RowHit(RowType.OTHER, "", null, y, ROW_HEIGHT));
        }
        return y + ROW_HEIGHT + 1;
    }

    private int drawChip(GuiGraphicsExtractor graphics, String chipName, int y, int clipTop, int clipBottom) {
        if (visibleRow(y, ROW_HEIGHT, clipTop, clipBottom)) {
            boolean selected = chipName.equals(selectedChip);
            int left = getX() + 5 + INDENT;
            graphics.fill(left, y, getX() + width - 5, y + ROW_HEIGHT - 1, selected ? 0xFF29313A : 0xFF181D23);
            graphics.fill(left + 4, y + 4, left + 12, y + 12, library.chipColor(chipName));
            graphics.text(Minecraft.getInstance().font, truncate(chipName, 18), left + 17, y + 5, selected ? 0xFFFFFFFF : 0xFFC8D0DA, false);
            rows.add(new RowHit(RowType.CHIP, chipName, null, y, ROW_HEIGHT));
        }
        return y + ROW_HEIGHT + 1;
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
        String hint = selectedBoard != null ? "DOUBLE CLICK BOARD TO OPEN"
                : selectedChip != null ? "DOUBLE CLICK CHIP TO EDIT"
                : selectedFolder != null && !selectedFolder.isBlank() ? "F2  EDIT FOLDER"
                : "+  ADD FOLDER";
        graphics.text(font, truncate(hint, 25), bx + 28, by + 7, 0xFF7F8A99, false);
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
            if (event.button() == 0 && mx >= getX() + 6 && mx < getX() + 26 && my >= footerTop + 5 && my < footerTop + 25) { requestAdd.run(); return; }
            if (event.button() == 0 && selectedFolder != null && !selectedFolder.isBlank() && mx >= getX() + width - 23 && mx < getX() + width - 6 && my >= footerTop + 5 && my < footerTop + 25) {
                try { String removed = selectedFolder; library.deleteFolder(removed); selectedFolder = null; status.accept("Deleted folder " + removed + "; its chips moved to OTHER"); }
                catch (IOException | RuntimeException exception) { status.accept("Delete folder failed: " + exception.getMessage()); }
            }
            return;
        }
        RowHit row = rowAt(mx, my);
        if (row == null) return;

        if (row.type == RowType.BOARD) {
            selectedBoard = row.name;
            selectedChip = null;
            selectedFolder = null;
            canvas.cancelPlacement();
            if (event.button() == 1 || (event.button() == 0 && doubleClick)) {
                openBoard.accept(row.name);
            } else if (event.button() == 0) {
                status.accept("Selected board " + row.name + " — double-click to open and continue editing it");
            }
            return;
        }

        if (row.type == RowType.COMPONENT && event.button() == 0) {
            selectedChip = null;
            selectedFolder = null;
            selectedBoard = null;
            canvas.setPlacement(row.nodeKind);
            return;
        }
        if ((row.type == RowType.FOLDER || row.type == RowType.OTHER) && event.button() == 0) {
            selectedChip = null;
            selectedBoard = null;
            selectedFolder = row.name;
            if (row.type == RowType.FOLDER) try { library.setFolderExpanded(row.name, !library.folderExpanded(row.name)); } catch (IOException exception) { status.accept("Folder error: " + exception.getMessage()); }
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
                } catch (IOException | RuntimeException exception) { status.accept("Move failed: " + exception.getMessage()); }
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
    private record RowHit(RowType type, String name, NodeKind nodeKind, int y, int height) {}
}
