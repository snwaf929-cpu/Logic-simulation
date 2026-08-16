package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.chip.ClientChipLibrary;
import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.CircuitEditorScreen;
import com.foreverspark.logicsim.client.screen.v2.EditorDocumentSnapshot;
import com.foreverspark.logicsim.client.screen.v2.EditorWorkspaceAccess;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * V2.1E CHIP safety guard. Named reusable CHIP edits get Save / Discard / Cancel before destructive navigation.
 * Editable BOARD workspaces are deliberately excluded because their established workflow autosaves project state.
 */
@Mixin(value = CircuitEditorScreen.class, priority = 2350)
public abstract class CircuitEditorDirtyGuardV21EMixin {
    @Shadow private CircuitCanvasWidget canvas;
    @Shadow @Final private ClientChipLibrary library;
    @Shadow private String currentChipName;
    @Shadow private void openSaveModal() { throw new AssertionError(); }
    @Shadow private void openChip(String name) { throw new AssertionError(); }
    @Shadow private void newCircuit() { throw new AssertionError(); }
    @Shadow private void setStatus(String status) { throw new AssertionError(); }
    @Shadow private void setEditorEnabled(boolean enabled) { throw new AssertionError(); }

    @Unique private final Map<String, CircuitDocument> logic$savedBaselines = new LinkedHashMap<>();
    @Unique private LogicPendingAction logic$pendingAction = LogicPendingAction.NONE;
    @Unique private String logic$pendingChip;
    @Unique private boolean logic$promptVisible;
    @Unique private boolean logic$bypassGuard;
    @Unique private boolean logic$guardSaveContinuation;
    @Unique private boolean logic$performingGuardSave;

    @Inject(method = "init", at = @At("RETURN"))
    private void logic$rememberInitialChip(CallbackInfo ci) {
        logic$ensureBaselineForCurrentChip();
    }

    @Inject(method = "openChip", at = @At("HEAD"), cancellable = true)
    private void logic$guardOpenChip(String name, CallbackInfo ci) {
        if (logic$bypassGuard || name == null || name.isBlank()) return;
        if (logic$needsChipPrompt()) {
            logic$showPrompt(LogicPendingAction.OPEN_CHIP, name);
            ci.cancel();
        }
    }

    @Inject(method = "openChip", at = @At("RETURN"))
    private void logic$baselineOpenedChip(String name, CallbackInfo ci) {
        logic$replaceBaselineFromCanvas();
    }

    @Inject(method = "newCircuit", at = @At("HEAD"), cancellable = true)
    private void logic$guardNewCircuit(CallbackInfo ci) {
        if (logic$bypassGuard) return;
        if (logic$needsChipPrompt()) {
            logic$showPrompt(LogicPendingAction.NEW_WORKSPACE, null);
            ci.cancel();
        }
    }

    @Inject(method = "newCircuit", at = @At("RETURN"))
    private void logic$baselineNewWorkspace(CallbackInfo ci) {
        logic$ensureBaselineForCurrentChip();
    }

    @Inject(method = "onCanvasNavigationChanged", at = @At("RETURN"))
    private void logic$baselineNavigation(CircuitCanvasWidget.NavigationState state, CallbackInfo ci) {
        logic$ensureBaselineForCurrentChip();
    }

    @Inject(method = "applySave", at = @At("HEAD"))
    private void logic$guardSaveStarted(CallbackInfo ci) {
        logic$performingGuardSave = logic$guardSaveContinuation;
    }

    @Inject(method = "applySave", at = @At("RETURN"))
    private void logic$guardSaveFinished(CallbackInfo ci) {
        logic$replaceBaselineFromCanvas();
        if (!logic$guardSaveContinuation) {
            logic$performingGuardSave = false;
            return;
        }
        logic$guardSaveContinuation = false;
        logic$performingGuardSave = false;
        logic$performPendingAction();
    }

    @Inject(method = "applyModal", at = @At("RETURN"))
    private void logic$guardApplyModalFinished(CallbackInfo ci) {
        // applyModal catches save failures; ensure a later Cancel is not mistaken for the successful applySave closeModal.
        logic$performingGuardSave = false;
    }

    @Inject(method = "closeModal", at = @At("RETURN"))
    private void logic$cancelContinuationWhenSaveModalIsCancelled(CallbackInfo ci) {
        if (!logic$guardSaveContinuation || logic$performingGuardSave) return;
        logic$guardSaveContinuation = false;
        logic$pendingAction = LogicPendingAction.NONE;
        logic$pendingChip = null;
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void logic$dirtyKeys(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (logic$promptVisible) {
            switch (event.key()) {
                case GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> logic$chooseSave();
                case GLFW.GLFW_KEY_D -> logic$chooseDiscard();
                case GLFW.GLFW_KEY_C, GLFW.GLFW_KEY_ESCAPE -> logic$chooseCancel();
                default -> { }
            }
            cir.setReturnValue(true);
            return;
        }

        if (canvas == null || !canvas.active || event.key() != GLFW.GLFW_KEY_ESCAPE || logic$bypassGuard) return;
        if (!logic$needsChipPrompt()) return;
        logic$showPrompt(LogicPendingAction.CLOSE_EDITOR, null);
        cir.setReturnValue(true);
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void logic$dirtyMouse(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (!logic$promptVisible) return;
        if (event.button() == 0) {
            int x = logic$dialogX();
            int y = logic$dialogY();
            if (logic$inside(event.x(), event.y(), x + 18, y + 76, 82, 22)) logic$chooseSave();
            else if (logic$inside(event.x(), event.y(), x + 109, y + 76, 82, 22)) logic$chooseDiscard();
            else if (logic$inside(event.x(), event.y(), x + 200, y + 76, 82, 22)) logic$chooseCancel();
        }
        cir.setReturnValue(true);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void logic$drawDirtyPrompt(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!logic$promptVisible || canvas == null) return;
        int x = logic$dialogX();
        int y = logic$dialogY();
        int w = 300;
        int h = 112;
        graphics.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xE0000000);
        graphics.fill(x, y, x + w, y + h, 0xFF171D24);
        graphics.outline(x, y, w, h, 0xFF5C6B7A);
        graphics.text(Minecraft.getInstance().font, "UNSAVED CHIP CHANGES", x + 16, y + 14, 0xFFFFD56A, true);
        String chip = currentChipName == null || currentChipName.isBlank() ? "this CHIP" : currentChipName;
        graphics.text(Minecraft.getInstance().font, "Save changes to " + logic$truncate(chip, 27) + " before continuing?",
                x + 16, y + 38, 0xFFD7DEE8, false);
        graphics.text(Minecraft.getInstance().font, "S/Enter = Save    D = Discard    C/Esc = Cancel",
                x + 16, y + 56, 0xFF8F9BA8, false);
        logic$button(graphics, x + 18, y + 76, 82, "SAVE", 0xFF55B96B);
        logic$button(graphics, x + 109, y + 76, 82, "DISCARD", 0xFFE0A452);
        logic$button(graphics, x + 200, y + 76, 82, "CANCEL", 0xFF6F7A87);
    }

    @Unique
    private boolean logic$needsChipPrompt() {
        if (canvas == null || currentChipName == null || currentChipName.isBlank()) return false;
        Object self = this;
        if (self instanceof EditorWorkspaceAccess workspace && workspace.logic$isBoardWorkspace()) return false;
        CircuitDocument baseline = logic$baselineFor(currentChipName);
        return baseline != null && !EditorDocumentSnapshot.same(baseline, canvas.document());
    }

    @Unique
    private CircuitDocument logic$baselineFor(String chipName) {
        if (chipName == null || chipName.isBlank()) return null;
        String key = logic$key(chipName);
        CircuitDocument existing = logic$savedBaselines.get(key);
        if (existing != null) return existing;
        try {
            if (!library.exists(chipName)) return null;
            CircuitDocument loaded = library.copyDocument(library.load(chipName).circuit);
            logic$savedBaselines.put(key, EditorDocumentSnapshot.copy(loaded));
            return logic$savedBaselines.get(key);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    @Unique
    private void logic$ensureBaselineForCurrentChip() {
        if (currentChipName != null && !currentChipName.isBlank()) logic$baselineFor(currentChipName);
    }

    @Unique
    private void logic$replaceBaselineFromCanvas() {
        if (canvas == null || currentChipName == null || currentChipName.isBlank()) return;
        logic$savedBaselines.put(logic$key(currentChipName), EditorDocumentSnapshot.copy(canvas.document()));
    }

    @Unique
    private void logic$showPrompt(LogicPendingAction action, String chipName) {
        logic$pendingAction = action;
        logic$pendingChip = chipName;
        logic$promptVisible = true;
        setEditorEnabled(false);
        setStatus("Unsaved CHIP changes — Save, Discard, or Cancel before continuing");
    }

    @Unique
    private void logic$chooseSave() {
        if (!logic$promptVisible) return;
        logic$promptVisible = false;
        setEditorEnabled(true);
        logic$guardSaveContinuation = true;
        logic$performingGuardSave = false;
        openSaveModal();
    }

    @Unique
    private void logic$chooseDiscard() {
        if (!logic$promptVisible) return;
        logic$promptVisible = false;
        setEditorEnabled(true);
        logic$performPendingAction();
    }

    @Unique
    private void logic$chooseCancel() {
        logic$promptVisible = false;
        logic$guardSaveContinuation = false;
        logic$performingGuardSave = false;
        logic$pendingAction = LogicPendingAction.NONE;
        logic$pendingChip = null;
        setEditorEnabled(true);
        setStatus("Unsaved-change navigation cancelled");
    }

    @Unique
    private void logic$performPendingAction() {
        LogicPendingAction action = logic$pendingAction;
        String chip = logic$pendingChip;
        logic$pendingAction = LogicPendingAction.NONE;
        logic$pendingChip = null;
        logic$bypassGuard = true;
        try {
            switch (action) {
                case OPEN_CHIP -> openChip(chip);
                case NEW_WORKSPACE -> newCircuit();
                case CLOSE_EDITOR -> Minecraft.getInstance().gui.setScreen(null);
                case NONE -> { }
            }
        } finally {
            logic$bypassGuard = false;
        }
    }

    @Unique private int logic$dialogX() {
        return canvas == null ? 20 : canvas.getX() + Math.max(0, (canvas.getWidth() - 300) / 2);
    }

    @Unique private int logic$dialogY() {
        return canvas == null ? 40 : canvas.getY() + Math.max(0, (canvas.getHeight() - 112) / 2);
    }

    @Unique private static boolean logic$inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Unique private static void logic$button(GuiGraphicsExtractor graphics, int x, int y, int w, String label, int color) {
        graphics.fill(x, y, x + w, y + 22, 0xFF202831);
        graphics.outline(x, y, w, 22, color);
        int tw = Minecraft.getInstance().font.width(label);
        graphics.text(Minecraft.getInstance().font, label, x + (w - tw) / 2, y + 7, color, true);
    }

    @Unique private static String logic$truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, Math.max(1, max - 1)) + "…";
    }

    @Unique private static String logic$key(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    @Unique private enum LogicPendingAction { NONE, OPEN_CHIP, NEW_WORKSPACE, CLOSE_EDITOR }
}
