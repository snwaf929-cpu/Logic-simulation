package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.chip.CircuitExchange;
import com.foreverspark.logicsim.client.chip.ClientChipLibrary;
import com.foreverspark.logicsim.client.screen.ComponentLibraryWidget;
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
                "IMPORT FILES", 0xFF55B96B, "INBOX", 0xFF91D79F);
        cursor += ROW_STEP;

        logic$exportRowY = cursor;
        logic$drawRow(graphics, self, cursor, clipTop, clipBottom,
                "EXPORT SELECTED", 0xFF63A9D8, selectedChip == null ? "SELECT" : "BUNDLE", 0xFFA8D8F3);
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
            if (event.button() == 0) logic$importFiles();
            else if (event.button() == 1) logic$showImportFolder();
            else return;
            ci.cancel();
            return;
        }

        if (logic$visibleHit(logic$exportRowY, event.y(), clipTop, clipBottom)) {
            if (event.button() == 0) logic$exportSelected();
            else if (event.button() == 1) logic$showExportFolder();
            else return;
            ci.cancel();
        }
    }

    @Unique
    private void logic$importFiles() {
        try {
            CircuitExchange.ImportResult result = CircuitExchange.importInbox(library);
            if (!result.importedAnything() && !result.hasFailures()) {
                status.accept("Import inbox is empty. Put .logicbundle.json or .logicchip.json in "
                        + result.importDirectory().toAbsolutePath() + " then click IMPORT FILES again.");
                return;
            }
            if (result.hasFailures()) {
                String first = result.failures().get(0);
                status.accept("Imported " + result.chipCount() + " chip(s) from " + result.fileCount()
                        + " file(s); " + result.failures().size() + " failed. First: " + first);
                return;
            }
            String root = result.roots().isEmpty() ? "" : " Root: " + String.join(", ", result.roots());
            status.accept("Imported " + result.chipCount() + " chip(s) from " + result.fileCount()
                    + " exchange file(s)." + root);
        } catch (IOException | RuntimeException exception) {
            status.accept("Import failed: " + logic$message(exception));
        }
    }

    @Unique
    private void logic$exportSelected() {
        if (selectedChip == null || selectedChip.isBlank()) {
            status.accept("Select a saved chip under MY CHIPS first, then click EXPORT SELECTED.");
            return;
        }
        try {
            CircuitExchange.ExportResult result = CircuitExchange.exportChip(library, selectedChip);
            status.accept("Exported " + result.root() + " + " + Math.max(0, result.chipCount() - 1)
                    + " dependency chip(s) -> " + result.path().toAbsolutePath());
        } catch (IOException | RuntimeException exception) {
            status.accept("Export failed: " + logic$message(exception));
        }
    }

    @Unique
    private void logic$showImportFolder() {
        try {
            CircuitExchange.ensureDirectories();
            status.accept("IMPORT folder: " + CircuitExchange.importDirectory().toAbsolutePath());
        } catch (IOException exception) {
            status.accept("Cannot create exchange folder: " + logic$message(exception));
        }
    }

    @Unique
    private void logic$showExportFolder() {
        try {
            CircuitExchange.ensureDirectories();
            status.accept("EXPORT folder: " + CircuitExchange.exportDirectory().toAbsolutePath());
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
    private static String logic$message(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
