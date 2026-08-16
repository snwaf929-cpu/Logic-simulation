package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.chip.ClientChipLibrary;
import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.ComponentLibraryWidget;
import com.foreverspark.logicsim.client.screen.v2.ClientEditorPreferences;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

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

    @Unique private final List<LogicRecentRow> logic$recentRows = new ArrayList<>();

    @Inject(method = "extractWidgetRenderState", at = @At("HEAD"))
    private void logic$resetRows(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        logic$recentRows.clear();
    }

    @Inject(method = "selectChip", at = @At("RETURN"))
    private void logic$rememberChip(String name, CallbackInfo ci) {
        if (name == null || name.isBlank() || !library.exists(name)) return;
        try { preferences.recordRecentChip(name); }
        catch (IOException exception) { status.accept("Recent chip history could not be saved: " + exception.getMessage()); }
    }

    @Inject(method = "drawSection", at = @At("HEAD"), cancellable = true)
    private void logic$insertRecents(GuiGraphicsExtractor graphics, String text, int y, int clipTop, int clipBottom,
                                     CallbackInfoReturnable<Integer> cir) {
        if (!"BOARDS".equals(text)) return;
        List<String> recents = logic$recents();
        if (recents.isEmpty()) return;
        ComponentLibraryWidget self = (ComponentLibraryWidget)(Object)this;
        int cursor = y;
        logic$section(graphics, self, "RECENT CHIPS", cursor, clipTop, clipBottom);
        cursor += 14;
        for (String chip : recents) {
            logic$chip(graphics, self, chip, cursor, clipTop, clipBottom);
            cursor += 19;
        }
        cursor += 4;
        logic$section(graphics, self, "BOARDS", cursor, clipTop, clipBottom);
        cir.setReturnValue(cursor + 14);
    }

    @Inject(method = "onClick", at = @At("HEAD"), cancellable = true)
    private void logic$clickRecent(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        if (event.button() != 0 && event.button() != 1) return;
        ComponentLibraryWidget self = (ComponentLibraryWidget)(Object)this;
        if (!self.isMouseOver(event.x(), event.y())) return;
        for (LogicRecentRow row : logic$recentRows) {
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

    @Unique private List<String> logic$recents() {
        ArrayList<String> result = new ArrayList<>();
        for (String chip : preferences.recentChipNames()) {
            if (chip != null && !chip.isBlank() && library.exists(chip) && visibleChip(chip)) result.add(chip);
        }
        return List.copyOf(result);
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
        logic$recentRows.add(new LogicRecentRow(chip, y));
    }

    @Unique private static void logic$section(GuiGraphicsExtractor graphics, ComponentLibraryWidget self, String text,
                                              int y, int clipTop, int clipBottom) {
        if (y + 14 <= clipTop || y >= clipBottom) return;
        graphics.text(Minecraft.getInstance().font, text, self.getX() + 7, y + 3, 0xFF737E8B, false);
    }

    @Unique private record LogicRecentRow(String name, int y) {}
}
