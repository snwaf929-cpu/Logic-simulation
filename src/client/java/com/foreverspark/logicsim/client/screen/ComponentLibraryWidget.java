package com.foreverspark.logicsim.client.screen;

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
import java.util.function.Supplier;

/**
 * Compact, cubic component browser inspired by the collection workflow of Digital Logic Sim.
 * Primitive components and user chip folders live in one scrollable library.
 */
public final class ComponentLibraryWidget extends AbstractWidget {
    private static final int ROW_HEIGHT = 18;
    private static final int INDENT = 12;
    private static final int[] PALETTE = {
            0xFFE05252,
            0xFFE08A3E,
            0xFFE1C84A,
            0xFF55B96B,
            0xFF4C86D9,
            0xFF7B68D9,
            0xFFC25BC7,
            0xFF7B8796
    };

    private final ClientChipLibrary library;
    private final CircuitCanvasWidget canvas;
    private final Supplier<String> folderNameSupplier;
    private final Consumer<String> folderNameSetter;
    private final Consumer<String> openChip;
    private final Consumer<String> status;
    private final List<RowHit> rows = new ArrayList<>();

    private String selectedFolder;
    private String selectedChip;
    private String draggingChip;
    private boolean dragMoved;
    private double scroll;
    private double maxScroll;

    public ComponentLibraryWidget(
            int x,
            int y,
            int width,
            int height,
            ClientChipLibrary library,
            CircuitCanvasWidget canvas,
            Supplier<String> folderNameSupplier,
            Consumer<String> folderNameSetter,
            Consumer<String> openChip,
            Consumer<String> status
    ) {
        super(x, y, width, height, Component.literal("Component library"));
        this.library = library;
        this.canvas = canvas;
        this.folderNameSupplier = folderNameSupplier;
        this.folderNameSetter = folderNameSetter;
        this.openChip = openChip;
        this.status = status;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        var font = Minecraft.getInstance().font;
        graphics.fill(getX(), getY(), getX() + width, getY() + height, 0xF015191E);
        graphics.outline(getX(), getY(), width, height, 0xFF343C46);

        graphics.text(font, "COMPONENTS", getX() + 7, getY() + 7, 0xFFE7ECF2, true);
        graphics.text(font, "FOLDER NAME", getX() + 7, getY() + 24, 0xFF7F8A99, false);

        int contentTop = getY() + 51;
        int footerTop = getY() + height - 68;
        int viewportHeight = Math.max(20, footerTop - contentTop - 3);
        int y = contentTop - (int) Math.round(scroll);
        rows.clear();

        y = drawSection(graphics, "PRIMITIVES", y, contentTop, footerTop);
        y = drawComponent(graphics, "INPUT", NodeKind.INPUT, 0xFF4C86D9, y, contentTop, footerTop);
        y = drawComponent(graphics, "OUTPUT", NodeKind.OUTPUT, 0xFF7B68D9, y, contentTop, footerTop);
        y = drawComponent(graphics, "NAND", NodeKind.NAND, 0xFF7B8796, y, contentTop, footerTop);

        y += 3;
        y = drawSection(graphics, "ROUTING", y, contentTop, footerTop);
        y = drawComponent(graphics, "SPLITTER", NodeKind.SPLITTER, 0xFF4FA6A0, y, contentTop, footerTop);
        y = drawComponent(graphics, "MERGER", NodeKind.MERGER, 0xFF4FA6A0, y, contentTop, footerTop);

        y += 5;
        y = drawSection(graphics, "MY CHIPS", y, contentTop, footerTop);
        for (ClientChipLibrary.FolderInfo folder : library.folders()) {
            y = drawFolder(graphics, folder, y, contentTop, footerTop);
            if (folder.expanded()) {
                for (String chip : library.chipsInFolder(folder.name())) {
                    y = drawChip(graphics, chip, folder.name(), y, contentTop, footerTop);
                }
            }
        }

        List<String> other = library.unfiledChips();
        if (!other.isEmpty()) {
            y = drawOtherFolder(graphics, y, contentTop, footerTop);
            for (String chip : other) {
                y = drawChip(graphics, chip, "", y, contentTop, footerTop);
            }
        }

        int contentHeight = Math.max(0, y + (int) Math.round(scroll) - contentTop);
        maxScroll = Math.max(0, contentHeight - viewportHeight);
        scroll = clamp(scroll, 0, maxScroll);

        drawFooter(graphics, footerTop);
    }

    private int drawSection(GuiGraphicsExtractor graphics, String text, int y, int clipTop, int clipBottom) {
        if (visibleRow(y, 14, clipTop, clipBottom)) {
            graphics.text(Minecraft.getInstance().font, text, getX() + 7, y + 3, 0xFF737E8B, false);
        }
        return y + 14;
    }

    private int drawComponent(GuiGraphicsExtractor graphics, String name, NodeKind kind, int color, int y, int clipTop, int clipBottom) {
        if (visibleRow(y, ROW_HEIGHT, clipTop, clipBottom)) {
            int bg = 0xFF1C2229;
            graphics.fill(getX() + 5, y, getX() + width - 5, y + ROW_HEIGHT - 1, bg);
            graphics.fill(getX() + 5, y, getX() + 8, y + ROW_HEIGHT - 1, color);
            graphics.text(Minecraft.getInstance().font, name, getX() + 13, y + 5, 0xFFD7DEE8, false);
            rows.add(new RowHit(RowType.COMPONENT, name, kind, y, ROW_HEIGHT));
        }
        return y + ROW_HEIGHT + 1;
    }

    private int drawFolder(GuiGraphicsExtractor graphics, ClientChipLibrary.FolderInfo folder, int y, int clipTop, int clipBottom) {
        if (visibleRow(y, ROW_HEIGHT, clipTop, clipBottom)) {
            boolean selected = folder.name().equals(selectedFolder);
            int bg = selected ? 0xFF2A3038 : 0xFF1B2026;
            graphics.fill(getX() + 5, y, getX() + width - 5, y + ROW_HEIGHT - 1, bg);
            graphics.fill(getX() + 5, y, getX() + 9, y + ROW_HEIGHT - 1, folder.color());
            String arrow = folder.expanded() ? "v" : ">";
            graphics.text(Minecraft.getInstance().font, arrow, getX() + 14, y + 5, 0xFFAEB7C2, false);
            graphics.text(Minecraft.getInstance().font, truncate(folder.name(), 18), getX() + 25, y + 5, selected ? 0xFFFFFFFF : 0xFFD7DEE8, false);
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

    private int drawChip(GuiGraphicsExtractor graphics, String chipName, String folderName, int y, int clipTop, int clipBottom) {
        if (visibleRow(y, ROW_HEIGHT, clipTop, clipBottom)) {
            boolean selected = chipName.equals(selectedChip);
            int left = getX() + 5 + INDENT;
            graphics.fill(left, y, getX() + width - 5, y + ROW_HEIGHT - 1, selected ? 0xFF29313A : 0xFF181D23);
            graphics.fill(left + 4, y + 4, left + 12, y + 12, library.chipColor(chipName));
            graphics.text(Minecraft.getInstance().font, truncate(chipName, 17), left + 17, y + 5, selected ? 0xFFFFFFFF : 0xFFC8D0DA, false);
            graphics.text(Minecraft.getInstance().font, "EDIT", getX() + width - 34, y + 5, 0xFF697583, false);
            rows.add(new RowHit(RowType.CHIP, chipName, null, y, ROW_HEIGHT));
        }
        return y + ROW_HEIGHT + 1;
    }

    private void drawFooter(GuiGraphicsExtractor graphics, int footerTop) {
        var font = Minecraft.getInstance().font;
        graphics.fill(getX() + 1, footerTop, getX() + width - 1, getY() + height - 1, 0xFF11151A);
        graphics.fill(getX() + 5, footerTop + 5, getX() + 59, footerTop + 23, 0xFF20262D);
        graphics.outline(getX() + 5, footerTop + 5, 54, 18, 0xFF46515D);
        graphics.text(font, "+ FOLDER", getX() + 12, footerTop + 10, 0xFFD7DEE8, false);

        graphics.fill(getX() + 63, footerTop + 5, getX() + 116, footerTop + 23, 0xFF20262D);
        graphics.outline(getX() + 63, footerTop + 5, 53, 18, 0xFF46515D);
        graphics.text(font, "RENAME", getX() + 68, footerTop + 10, 0xFFD7DEE8, false);

        graphics.fill(getX() + 120, footerTop + 5, getX() + width - 5, footerTop + 23, 0xFF20262D);
        graphics.outline(getX() + 120, footerTop + 5, width - 125, 18, 0xFF654247);
        graphics.text(font, "DELETE", getX() + 125, footerTop + 10, 0xFFE7B0B5, false);

        graphics.text(font, selectedChip != null ? "CHIP COLOR" : selectedFolder != null ? "FOLDER COLOR" : "SELECT CHIP/FOLDER", getX() + 7, footerTop + 29, 0xFF7F8A99, false);
        int swatchY = footerTop + 43;
        int swatchW = Math.max(12, (width - 18) / PALETTE.length);
        for (int i = 0; i < PALETTE.length; i++) {
            int x = getX() + 6 + i * swatchW;
            graphics.fill(x, swatchY, x + swatchW - 3, swatchY + 14, PALETTE[i]);
            graphics.outline(x, swatchY, swatchW - 3, 14, 0xFF0B0D10);
        }
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        if (!contains(mx, my)) return;

        int footerTop = getY() + height - 68;
        if (my >= footerTop) {
            handleFooterClick(mx, my, footerTop, event.button());
            return;
        }

        RowHit row = rowAt(mx, my);
        if (row == null) return;

        if (row.type == RowType.COMPONENT && event.button() == 0) {
            selectedChip = null;
            selectedFolder = null;
            canvas.setPlacement(row.nodeKind);
            return;
        }

        if ((row.type == RowType.FOLDER || row.type == RowType.OTHER) && event.button() == 0) {
            selectedChip = null;
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
            if (event.button() == 1) {
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
        if (event.button() == 0 && draggingChip != null && (Math.abs(dx) + Math.abs(dy) > 0.01)) {
            dragMoved = true;
        }
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
                    status.accept(target.name.isBlank()
                            ? "Moved " + draggingChip + " to OTHER"
                            : "Moved " + draggingChip + " to " + target.name);
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

    private void handleFooterClick(double mx, double my, int footerTop, int button) {
        if (button != 0) return;

        if (my >= footerTop + 5 && my < footerTop + 23) {
            if (mx >= getX() + 5 && mx < getX() + 59) {
                try {
                    library.createFolder(folderNameSupplier.get());
                    selectedFolder = folderNameSupplier.get().trim();
                    selectedChip = null;
                    folderNameSetter.accept("");
                    status.accept("Folder created: " + selectedFolder);
                } catch (IOException | RuntimeException exception) {
                    status.accept("Create folder failed: " + exception.getMessage());
                }
                return;
            }
            if (mx >= getX() + 63 && mx < getX() + 116) {
                if (selectedFolder == null || selectedFolder.isBlank()) {
                    status.accept("Select a user folder to rename");
                    return;
                }
                try {
                    String newName = folderNameSupplier.get().trim();
                    library.renameFolder(selectedFolder, newName);
                    selectedFolder = newName;
                    folderNameSetter.accept("");
                    status.accept("Folder renamed to " + newName);
                } catch (IOException | RuntimeException exception) {
                    status.accept("Rename failed: " + exception.getMessage());
                }
                return;
            }
            if (mx >= getX() + 120) {
                if (selectedFolder == null || selectedFolder.isBlank()) {
                    status.accept("Select a user folder to delete");
                    return;
                }
                try {
                    String removed = selectedFolder;
                    library.deleteFolder(removed);
                    selectedFolder = null;
                    status.accept("Deleted folder " + removed + " (chips moved to OTHER)");
                } catch (IOException | RuntimeException exception) {
                    status.accept("Delete failed: " + exception.getMessage());
                }
                return;
            }
        }

        int swatchY = footerTop + 43;
        if (my >= swatchY && my < swatchY + 14) {
            int swatchW = Math.max(12, (width - 18) / PALETTE.length);
            int index = (int) ((mx - (getX() + 6)) / swatchW);
            if (index < 0 || index >= PALETTE.length) return;
            try {
                if (selectedChip != null) {
                    library.setChipColor(selectedChip, PALETTE[index]);
                    status.accept("Colored chip " + selectedChip);
                } else if (selectedFolder != null && !selectedFolder.isBlank()) {
                    library.setFolderColor(selectedFolder, PALETTE[index]);
                    status.accept("Colored folder " + selectedFolder);
                }
            } catch (IOException | RuntimeException exception) {
                status.accept("Color failed: " + exception.getMessage());
            }
        }
    }

    private RowHit rowAt(double mx, double my) {
        if (mx < getX() || mx >= getX() + width) return null;
        for (int i = rows.size() - 1; i >= 0; i--) {
            RowHit row = rows.get(i);
            if (my >= row.y && my < row.y + row.height) return row;
        }
        return null;
    }

    private boolean visibleRow(int y, int h, int top, int bottom) {
        return y + h > top && y < bottom;
    }

    private boolean contains(double x, double y) {
        return x >= getX() && x < getX() + width && y >= getY() && y < getY() + height;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, Math.max(0, max - 1)) + "…";
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput builder) {
    }

    private enum RowType {
        COMPONENT,
        FOLDER,
        OTHER,
        CHIP
    }

    private record RowHit(RowType type, String name, NodeKind nodeKind, int y, int height) {
    }
}
