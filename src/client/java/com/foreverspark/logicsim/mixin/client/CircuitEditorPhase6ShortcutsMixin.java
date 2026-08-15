package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.CircuitEditorScreen;
import com.foreverspark.logicsim.client.screen.v2.EditorLayoutTools;
import com.foreverspark.logicsim.client.screen.v2.EditorPhase6Access;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keyboard access for the first Phase 6 CAD polish tools. */
@Mixin(value = CircuitEditorScreen.class, priority = 2140)
public abstract class CircuitEditorPhase6ShortcutsMixin {
    @Shadow private CircuitCanvasWidget canvas;

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void logic$phase6Shortcuts(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (canvas == null || !canvas.active) return;
        boolean ctrl = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean shift = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        boolean alt = (event.modifiers() & GLFW.GLFW_MOD_ALT) != 0;
        int key = event.key();
        EditorPhase6Access phase6 = (EditorPhase6Access)(Object)canvas;

        if (ctrl && !alt && key == GLFW.GLFW_KEY_L) {
            phase6.logic$toggleSelectedLocks();
            cir.setReturnValue(true);
            return;
        }

        if (alt && shift && !ctrl) {
            EditorLayoutTools.Alignment alignment = switch (key) {
                case GLFW.GLFW_KEY_LEFT -> EditorLayoutTools.Alignment.LEFT;
                case GLFW.GLFW_KEY_RIGHT -> EditorLayoutTools.Alignment.RIGHT;
                case GLFW.GLFW_KEY_UP -> EditorLayoutTools.Alignment.TOP;
                case GLFW.GLFW_KEY_DOWN -> EditorLayoutTools.Alignment.BOTTOM;
                case GLFW.GLFW_KEY_H -> EditorLayoutTools.Alignment.CENTER_X;
                case GLFW.GLFW_KEY_V -> EditorLayoutTools.Alignment.CENTER_Y;
                default -> null;
            };
            if (alignment != null) {
                phase6.logic$alignSelected(alignment);
                cir.setReturnValue(true);
                return;
            }
            if (key == GLFW.GLFW_KEY_P) {
                phase6.logic$alignSelectedPinRows();
                cir.setReturnValue(true);
                return;
            }
        }

        if (ctrl && alt && !shift) {
            if (key == GLFW.GLFW_KEY_H) {
                phase6.logic$distributeSelected(EditorLayoutTools.Axis.HORIZONTAL);
                cir.setReturnValue(true);
                return;
            }
            if (key == GLFW.GLFW_KEY_V) {
                phase6.logic$distributeSelected(EditorLayoutTools.Axis.VERTICAL);
                cir.setReturnValue(true);
            }
        }
    }
}
