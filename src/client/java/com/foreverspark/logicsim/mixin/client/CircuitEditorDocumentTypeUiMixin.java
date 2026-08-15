package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.CircuitEditorScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Removes the misleading "Save chip" label while Ctrl+S is actually saving a BOARD. */
@Mixin(value = CircuitEditorScreen.class, priority = 1600)
public abstract class CircuitEditorDocumentTypeUiMixin {
    @Shadow private CircuitCanvasWidget canvas;
    @Shadow private String currentChipName;

    @ModifyConstant(method = "init", constant = @Constant(stringValue = "Save chip  Ctrl+S"))
    private String logic$correctSaveTooltip(String original) {
        boolean chip = currentChipName != null || (canvas != null && canvas.isNestedView());
        return chip ? "Save CHIP  Ctrl+S" : "Save BOARD  Ctrl+S";
    }

    @ModifyConstant(method = "init", constant = @Constant(stringValue = "New circuit"))
    private String logic$correctNewTooltip(String original) {
        return "New BOARD";
    }
}
