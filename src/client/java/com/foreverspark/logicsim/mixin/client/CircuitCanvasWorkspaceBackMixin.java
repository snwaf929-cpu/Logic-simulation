package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.EditorWorkspaceRuntime;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Extends the existing Back action beyond nested-chip inspection to saved board/chip workspaces. */
@Mixin(value = CircuitCanvasWidget.class, priority = 1450)
public abstract class CircuitCanvasWorkspaceBackMixin {
    @Shadow @Final private List<?> navigationStack;

    @Inject(method = "canNavigateBack", at = @At("RETURN"), cancellable = true)
    private void logic$workspaceCanGoBack(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue() && EditorWorkspaceRuntime.canGoBack((CircuitCanvasWidget)(Object)this)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "navigateBack", at = @At("HEAD"), cancellable = true)
    private void logic$workspaceGoBack(CallbackInfoReturnable<Boolean> cir) {
        if (!navigationStack.isEmpty()) return;
        if (EditorWorkspaceRuntime.goBack((CircuitCanvasWidget)(Object)this)) {
            cir.setReturnValue(true);
        }
    }
}
