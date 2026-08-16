package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.chip.ClientChipLibrary;
import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.ClockPlacementState;
import com.foreverspark.logicsim.client.screen.ComponentLibraryWidget;
import com.foreverspark.logicsim.client.screen.RandomPlacementState;
import com.foreverspark.logicsim.client.screen.v2.ClientEditorPreferences;
import com.foreverspark.logicsim.editor.model.NodeKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** V2.1E recent CHIP + component QoL. All state is editor-only preference metadata. */
@Mixin(value = ComponentLibraryWidget.class, priority = 1320)
public abstract class ComponentLibraryRecentsV21EMixin {
    @Shadow @Final private ClientChipLibrary library;
    @Shadow @Final private ClientEditorPreferences preferences;
    @Shadow @Final private CircuitCanvasWidget canvas;
    @Shadow @Final private Consumer<String> openChip;
    @Shadow @Final private Consumer<String> status;
    @Shadow private String selectedChip;
    @Shadow private String selectedFolder;
    @Shadow private String selectedBoard;
    @Shadow private boolean visibleChip(String chipName) { throw new AssertionError(); }

    @Unique private final List<LogicRecentChipRow> logic$recentChipRows = new ArrayList<>();
    @Unique private final List<LogicRecentComponentRow> logic$recentComponentRows = new ArrayList<>();

    @Inject(method = "extractWidgetRenderState", at = @At("HEAD"))
    private void logic$resetRows(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        logic$recentChipRows.clear();
        logic$recentComponentRows.clear();
    }

    @Inject(method = "selectChip", at = @At("RETURN"))
    private void logic$rememberChip(String name, CallbackInfo ci) {
        if (name == null || name.isBlank() || !library.exists(name)) return;
        try { preferences.recordRecentChip(name); }
        catch (IOException exception) { status.accept("Recent chip history could not be saved: " + logic$message(exception)); }
    }

    /** Every normal/special component click ultimately selects a NodeKind placement after CLOCK/RANDOM are armed. */
    @Redirect(
            method = "handleComponentClick",
            at = @At(value = "INVOKE", target = "Lcom/foreverspark/logicsim/client/screen/CircuitCanvasWidget;setPlacement(Lcom/foreverspark/logicsim/editor/model/NodeKind;)V")
    )
    private void logic$rememberComponent(CircuitCanvasWidget target, NodeKind kind) {
        String id = logic$componentId(kind);
        target.setPlacement(kind);
        try { preferences.recordRecentComponent(id); }
        catch (IOException exception) { status.accept("Recent component history could not be saved: " + logic$message(exception)); }
    }

    @Inject(method = "drawSection", at = @At("HEAD"), cancellable = true)
    private void logic$insertRecents(GuiGraphicsExtractor graphics, String text, int y, int clipTop, int clipBottom,
                                     CallbackInfoReturnable<Integer> cir) {
        if (!"BOARDS".equals(text)) return;
        List<String> components = logic$recentComponents();
        List<String> chips = logic$recentChips();
        if (components.isEmpty() && chips.isEmpty()) return;

        ComponentLibraryWidget self = (ComponentLibraryWidget)(Object)this;
        int cursor = y;
        if (!components.isEmpty()) {
            logic$section(graphics, self, "RECENT COMPONENTS", cursor, clipTop, clipBottom);
            cursor += 14;
            for (String component : components) {
                logic$component(graphics, self, component, cursor, clipTop, clipBottom);
                cursor += 19;
            }
            cursor += 4;
        }
        if (!chips.isEmpty()) {
            logic$section(graphics, self, "RECENT CHIPS", cursor, clipTop, clipBottom);
            cursor += 14;
            for (String chip : chips) {
                logic$chip(graphics, self, chip, cursor, clipTop, clipBottom);
                cursor += 19;
            }
            cursor += 4;
        }
        logic$section(graphics, self, "BOARDS", cursor, clipTop, clipBottom);
        cir.setReturnValue(cursor + 14);
    }

    @Inject(method = "onClick", at = @At("HEAD"), cancellable = true)
    private void logic$clickRecent(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        if (event.button() != 0 && event.button() != 1) return;
        ComponentLibraryWidget self = (ComponentLibraryWidget)(Object)this;
        if (!self.isMouseOver(event.x(), event.y())) return;

        for (LogicRecentComponentRow row : logic$recentComponentRows) {
            if (event.y() < row.y() || event.y() >= row.y() + 18) continue;
            if (event.button() == 0) {
                selectedChip = null;
                selectedFolder = null;
                selectedBoard = null;
                logic$placeRecentComponent(row.id());
            } else {
                status.accept("Recent component " + row.id() + " — left-click places it");
            }
            ci.cancel();
            return;
        }

        for (LogicRecentChipRow row : logic$recentChipRows) {
            if (event.y() < row.y() || event.y() >= row.y() + 18) continue;
            if (!library.exists(row.name()) || !visibleChip(row.name())) return;
            selectedChip = row.name();
            selectedFolder = null;
            selectedBoard = null;
            if (event.button() == 1 || doubleClick) {
                canvas.cancelPlacement();
                openChip.accept(row.name());
            } else {
                canvas.setCustomChipPlacement(row.name());
                status.accept("Place recent chip " + row.name() + " — double-click/right-click opens it");
            }
            ci.cancel();
            return;
        }
    }

    @Unique private List<String> logic$recentChips() {
        ArrayList<String> result = new ArrayList<>();
        for (String chip : preferences.recentChipNames()) {
            if (chip != null && !chip.isBlank() && library.exists(chip) && visibleChip(chip)) result.add(chip);
        }
        return List.copyOf(result);
    }

    @Unique private List<String> logic$recentComponents() {
        ArrayList<String> result = new ArrayList<>();
        for (String id : preferences.recentComponentIds()) if (logic$validComponentId(id)) result.add(id);
        return List.copyOf(result);
    }

    @Unique private void logic$placeRecentComponent(String id) {
        switch (id) {
            case "CLOCK" -> {
                RandomPlacementState.disarm();
                ClockPlacementState.arm(canvas);
                canvas.setPlacement(NodeKind.CONSTANT);
                status.accept("Place recent CLOCK");
            }
            case "RANDOM" -> {
                ClockPlacementState.disarm();
                RandomPlacementState.arm(canvas);
                canvas.setPlacement(NodeKind.CONSTANT);
                status.accept("Place recent RANDOM");
            }
            case "INPUT" -> logic$placeSimple(NodeKind.INPUT, id);
            case "OUTPUT" -> logic$placeSimple(NodeKind.OUTPUT, id);
            case "NAND" -> logic$placeSimple(NodeKind.NAND, id);
            case "CONSTANT" -> logic$placeSimple(NodeKind.CONSTANT, id);
            case "PROBE" -> logic$placeSimple(NodeKind.PROBE, id);
            case "BUS" -> logic$placeSimple(NodeKind.BUS, id);
            case "SPLITTER" -> logic$placeSimple(NodeKind.SPLITTER, id);
            case "MERGER" -> logic$placeSimple(NodeKind.MERGER, id);
            case "BUS_SLICE" -> logic$placeSimple(NodeKind.BUS_SLICE, id);
            case "NET_LABEL" -> logic$placeSimple(NodeKind.NET_LABEL, id);
            default -> status.accept("Unknown recent component " + id);
        }
    }

    @Unique private void logic$placeSimple(NodeKind kind, String id) {
        ClockPlacementState.disarm();
        RandomPlacementState.disarm();
        canvas.setPlacement(kind);
        status.accept("Place recent " + id);
    }

    @Unique private String logic$componentId(NodeKind kind) {
        if (kind == NodeKind.CONSTANT && ClockPlacementState.armed()) return "CLOCK";
        if (kind == NodeKind.CONSTANT && RandomPlacementState.armed()) return "RANDOM";
        return switch (kind) {
            case INPUT -> "INPUT";
            case OUTPUT -> "OUTPUT";
            case NAND -> "NAND";
            case CONSTANT -> "CONSTANT";
            case PROBE -> "PROBE";
            case BUS -> "BUS";
            case SPLITTER -> "SPLITTER";
            case MERGER -> "MERGER";
            case BUS_SLICE -> "BUS_SLICE";
            case NET_LABEL -> "NET_LABEL";
            default -> kind.name();
        };
    }

    @Unique private static boolean logic$validComponentId(String id) {
        return switch (id) {
            case "INPUT", "OUTPUT", "NAND", "CONSTANT", "CLOCK", "RANDOM", "PROBE", "BUS",
                    "SPLITTER", "MERGER", "BUS_SLICE", "NET_LABEL" -> true;
            default -> false;
        };
    }

    @Unique private void logic$component(GuiGraphicsExtractor graphics, ComponentLibraryWidget self, String id,
                                         int y, int clipTop, int clipBottom) {
        if (y + 18 <= clipTop || y >= clipBottom) return;
        int left = self.getX() + 5;
        int right = self.getX() + self.getWidth() - 5;
        graphics.fill(left, y, right, y + 17, 0xFF1C2229);
        graphics.fill(left, y, left + 3, y + 17, logic$componentColor(id));
        String label = id.replace('_', ' ');
        graphics.text(Minecraft.getInstance().font, label, left + 8, y + 5, 0xFFD7DEE8, false);
        logic$recentComponentRows.add(new LogicRecentComponentRow(id, y));
    }

    @Unique private void logic$chip(GuiGraphicsExtractor graphics, ComponentLibraryWidget self, String chip,
                                    int y, int clipTop, int clipBottom) {
        if (y + 18 <= clipTop || y >= clipBottom) return;
        int left = self.getX() + 17;
        int right = self.getX() + self.getWidth() - 5;
        graphics.fill(left, y, right, y + 17, chip.equals(selectedChip) ? 0xFF29313A : 0xFF181D23);
        graphics.fill(left + 4, y + 4, left + 12, y + 12, library.chipColor(chip));
        String shown = chip.length() <= 20 ? chip : chip.substring(0, 19) + "…";
        graphics.text(Minecraft.getInstance().font, shown, left + 17, y + 5, 0xFFC8D0DA, false);
        logic$recentChipRows.add(new LogicRecentChipRow(chip, y));
    }

    @Unique private static int logic$componentColor(String id) {
        return switch (id) {
            case "INPUT" -> 0xFF4C86D9;
            case "OUTPUT" -> 0xFF7B68D9;
            case "CONSTANT" -> 0xFFD29A45;
            case "CLOCK" -> 0xFF5FA8FF;
            case "RANDOM" -> 0xFFB06CE8;
            case "PROBE" -> 0xFF63A9D8;
            case "BUS", "SPLITTER", "MERGER" -> 0xFF4FA6A0;
            case "BUS_SLICE" -> 0xFF55AFC2;
            case "NET_LABEL" -> 0xFF8E73D8;
            default -> 0xFF7B8796;
        };
    }

    @Unique private static void logic$section(GuiGraphicsExtractor graphics, ComponentLibraryWidget self, String text,
                                              int y, int clipTop, int clipBottom) {
        if (y + 14 <= clipTop || y >= clipBottom) return;
        graphics.text(Minecraft.getInstance().font, text, self.getX() + 7, y + 3, 0xFF737E8B, false);
    }

    @Unique private static String logic$message(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    @Unique private record LogicRecentChipRow(String name, int y) {}
    @Unique private record LogicRecentComponentRow(String id, int y) {}
}
