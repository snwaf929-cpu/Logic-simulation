package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.chip.CircuitExchange;
import com.foreverspark.logicsim.client.chip.ClientChipLibrary;
import com.foreverspark.logicsim.client.screen.ComponentLibraryWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** Adds portable circuit exchange actions directly above MY CHIPS. */
@Mixin(value = ComponentLibraryWidget.class, priority = 1250)
public abstract class ComponentLibraryExchangeMixin {
    private static final int ROW_HEIGHT = 18;
    private static final int ROW_STEP = 19;
    private static final int SECTION_HEIGHT = 14;
    private static final int CONTENT_TOP_OFFSET = 25;

    @Shadow @Final private ClientChipLibrary library;
    @Shadow @Final private Consumer<String> status;
    @Shadow private String selectedChip;

    @Unique private int logic$importRowY = Integer.MIN_VALUE;
    @Unique private int logic$exportRowY = Integer.MIN_VALUE;

    /** Search mode does not render MY CHIPS, so never leave clickable exchange coordinates from a previous frame. */
    @Inject(method = "extractWidgetRenderState", at = @At("HEAD"))
    private void logic$resetExchangeRows(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        logic$importRowY = Integer.MIN_VALUE;
        logic$exportRowY = Integer.MIN_VALUE;
    }

    @Inject(method = "drawSection", at = @At("HEAD"), cancellable = true)
    private void logic$insertExchangeSection(GuiGraphicsExtractor graphics, String text, int y, int clipTop, int clipBottom,
                                             CallbackInfoReturnable<Integer> cir) {
        if (!"MY CHIPS".equals(text)) return;

        ComponentLibraryWidget self = (ComponentLibraryWidget)(Object)this;
        int cursor = y;
        logic$drawSection(graphics, self, "EXCHANGE", cursor, clipTop, clipBottom);
        cursor += SECTION_HEIGHT;

        logic$importRowY = cursor;
        logic$drawRow(graphics, self, cursor, clipTop, clipBottom,
                "IMPORT FILE", 0xFF55B96B, "OPEN...", 0xFF91D79F);
        cursor += ROW_STEP;

        logic$exportRowY = cursor;
        logic$drawRow(graphics, self, cursor, clipTop, clipBottom,
                "EXPORT SELECTED", 0xFF63A9D8, selectedChip == null ? "SELECT" : "SAVE...", 0xFFA8D8F3);
        cursor += ROW_STEP + 4;

        logic$drawSection(graphics, self, "MY CHIPS", cursor, clipTop, clipBottom);
        cir.setReturnValue(cursor + SECTION_HEIGHT);
    }

    @Inject(method = "onClick", at = @At("HEAD"), cancellable = true)
    private void logic$exchangeClick(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        ComponentLibraryWidget self = (ComponentLibraryWidget)(Object)this;
        int left = self.getX() + 5;
        int right = self.getX() + self.getWidth() - 5;
        int clipTop = self.getY() + CONTENT_TOP_OFFSET;
        int clipBottom = self.getY() + self.getHeight() - 30;
        if (event.x() < left || event.x() >= right) return;

        if (logic$visibleHit(logic$importRowY, event.y(), clipTop, clipBottom)) {
            if (event.button() == 0) logic$importNative();
            else if (event.button() == 1) logic$importInboxFallback();
            else return;
            ci.cancel();
            return;
        }

        if (logic$visibleHit(logic$exportRowY, event.y(), clipTop, clipBottom)) {
            if (event.button() == 0) logic$exportNative();
            else if (event.button() == 1) logic$showExportFolder();
            else return;
            ci.cancel();
        }
    }

    @Unique
    private void logic$importNative() {
        try {
            CircuitExchange.ensureDirectories();
            String chosen = TinyFileDialogs.tinyfd_openFileDialog(
                    "Import Logic Simulation circuit",
                    CircuitExchange.importDirectory().toAbsolutePath().toString(),
                    null,
                    "Logic Simulation exchange (.logicbundle.json / .logicchip.json)",
                    false
            );
            if (chosen == null || chosen.isBlank()) {
                status.accept("Import cancelled");
                return;
            }
            CircuitExchange.ImportResult result = CircuitExchange.importFiles(library, List.of(Path.of(chosen)));
            logic$reportImport(result);
        } catch (LinkageError error) {
            status.accept("Native Open dialog unavailable: " + logic$message(error)
                    + ". Right-click IMPORT FILE to use the inbox fallback.");
        } catch (IOException | RuntimeException exception) {
            status.accept("Import failed: " + logic$message(exception));
        }
    }

    @Unique
    private void logic$importInboxFallback() {
        try {
            CircuitExchange.ImportResult result = CircuitExchange.importInbox(library);
            if (!result.importedAnything() && !result.hasFailures()) {
                status.accept("Fallback inbox is empty: " + result.importDirectory().toAbsolutePath());
                return;
            }
            logic$reportImport(result);
        } catch (IOException | RuntimeException exception) {
            status.accept("Inbox import failed: " + logic$message(exception));
        }
    }

    @Unique
    private void logic$reportImport(CircuitExchange.ImportResult result) {
        if (result.hasFailures()) {
            String first = result.failures().getFirst();
            status.accept("Imported " + result.chipCount() + " chip(s) from " + result.fileCount()
                    + " file(s); " + result.failures().size() + " failed. First: " + first);
            return;
        }
        String root = result.roots().isEmpty() ? "" : " Root: " + String.join(", ", result.roots());
        status.accept("Imported " + result.chipCount() + " chip(s) from " + result.fileCount()
                + " exchange file(s)." + root);
    }

    @Unique
    private void logic$exportNative() {
        if (selectedChip == null || selectedChip.isBlank()) {
            status.accept("Select a saved chip under MY CHIPS first, then click EXPORT SELECTED.");
            return;
        }
        try {
            CircuitExchange.ensureDirectories();
            Path suggested = CircuitExchange.suggestedExportPath(selectedChip).toAbsolutePath();
            String chosen = TinyFileDialogs.tinyfd_saveFileDialog(
                    "Export Logic Simulation circuit",
                    suggested.toString(),
                    null,
                    "Logic Simulation bundle (.logicbundle.json)"
            );
            if (chosen == null || chosen.isBlank()) {
                status.accept("Export cancelled");
                return;
            }
            CircuitExchange.ExportResult result = CircuitExchange.exportChipTo(library, selectedChip, Path.of(chosen));
            status.accept("Exported " + result.root() + " + " + Math.max(0, result.chipCount() - 1)
                    + " dependency chip(s) -> " + result.path().toAbsolutePath());
        } catch (LinkageError error) {
            status.accept("Native Save dialog unavailable: " + logic$message(error)
                    + ". Right-click EXPORT SELECTED for the fallback folder path.");
        } catch (IOException | RuntimeException exception) {
            status.accept("Export failed: " + logic$message(exception));
        }
    }

    @Unique
    private void logic$showExportFolder() {
        try {
            CircuitExchange.ensureDirectories();
            status.accept("Fallback EXPORT folder: " + CircuitExchange.exportDirectory().toAbsolutePath());
        } catch (IOException exception) {
            status.accept("Cannot create exchange folder: " + logic$message(exception));
        }
    }

    @Unique
    private static void logic$drawSection(GuiGraphicsExtractor graphics, ComponentLibraryWidget self, String text,
                                          int rowY, int clipTop, int clipBottom) {
        if (rowY + SECTION_HEIGHT <= clipTop || rowY >= clipBottom) return;
        graphics.text(Minecraft.getInstance().font, text, self.getX() + 7, rowY + 3, 0xFF737E8B, false);
    }

    @Unique
    private static void logic$drawRow(GuiGraphicsExtractor graphics, ComponentLibraryWidget self, int rowY,
                                      int clipTop, int clipBottom, String title, int accent, String badge, int badgeColor) {
        if (rowY + ROW_HEIGHT <= clipTop || rowY >= clipBottom) return;
        int left = self.getX() + 5;
        int right = self.getX() + self.getWidth() - 5;
        graphics.fill(left, rowY, right, rowY + ROW_HEIGHT - 1, 0xFF1C2229);
        graphics.fill(left, rowY, left + 3, rowY + ROW_HEIGHT - 1, accent);
        graphics.text(Minecraft.getInstance().font, title, self.getX() + 13, rowY + 5, 0xFFD7DEE8, false);
        int badgeX = right - 6 - Minecraft.getInstance().font.width(badge);
        graphics.text(Minecraft.getInstance().font, badge, Math.max(self.getX() + 94, badgeX), rowY + 5, badgeColor, false);
    }

    @Unique
    private static boolean logic$visibleHit(int rowY, double mouseY, int clipTop, int clipBottom) {
        return rowY != Integer.MIN_VALUE
                && rowY + ROW_HEIGHT > clipTop
                && rowY < clipBottom
                && mouseY >= rowY
                && mouseY < rowY + ROW_HEIGHT;
    }

    @Unique
    private static String logic$message(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
