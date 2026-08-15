package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.chip.ChipDeletionAccess;
import com.foreverspark.logicsim.client.chip.ClientChipLibrary;
import com.foreverspark.logicsim.client.screen.ComponentLibraryWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/** Makes saved-chip deletion a normal library action instead of a config-folder operation. */
@Mixin(ComponentLibraryWidget.class)
public abstract class ComponentLibraryChipDeleteMixin {
    @Shadow private ClientChipLibrary library;
    @Shadow private Consumer<String> status;
    @Shadow private String selectedChip;
    @Shadow private boolean searchFocused;

    @Inject(method = "searchRight", at = @At("HEAD"), cancellable = true)
    private void logic$reserveChipDeleteSpace(CallbackInfoReturnable<Integer> cir) {
        if (selectedChip == null || selectedChip.isBlank()) return;
        ComponentLibraryWidget self = (ComponentLibraryWidget) (Object) this;
        cir.setReturnValue(self.getX() + self.getWidth() - 27);
    }

    @Inject(method = "drawFooter", at = @At("TAIL"))
    private void logic$drawChipDelete(GuiGraphicsExtractor graphics, int footerTop, CallbackInfo ci) {
        if (selectedChip == null || selectedChip.isBlank()) return;
        ComponentLibraryWidget self = (ComponentLibraryWidget) (Object) this;
        int by = footerTop + 5;
        int tx = self.getX() + self.getWidth() - 23;
        graphics.fill(tx, by, tx + 17, by + 20, 0xFF24181B);
        graphics.outline(tx, by, 17, 20, 0xFF8A4650);
        graphics.text(Minecraft.getInstance().font, "x", tx + 6, by + 7, 0xFFFFB7BE, false);
    }

    @Inject(method = "onClick", at = @At("HEAD"), cancellable = true)
    private void logic$deleteSelectedChip(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        if (event.button() != 0 || selectedChip == null || selectedChip.isBlank()) return;
        ComponentLibraryWidget self = (ComponentLibraryWidget) (Object) this;
        int footerTop = self.getY() + self.getHeight() - 30;
        int by = footerTop + 5;
        int tx = self.getX() + self.getWidth() - 23;
        if (event.x() < tx || event.x() >= tx + 17 || event.y() < by || event.y() >= by + 20) return;

        searchFocused = false;
        String deleting = selectedChip;
        try {
            List<String> dependents = ((ChipDeletionAccess) library).logic$dependentsOf(deleting);
            if (!dependents.isEmpty()) {
                String shown = String.join(", ", dependents.subList(0, Math.min(4, dependents.size())));
                if (dependents.size() > 4) shown += " +" + (dependents.size() - 4) + " more";
                status.accept("Cannot delete CHIP " + deleting + " — used by " + shown);
            } else {
                ((ChipDeletionAccess) library).logic$deleteChip(deleting);
                selectedChip = null;
                status.accept("Deleted CHIP " + deleting);
            }
        } catch (IOException | RuntimeException exception) {
            status.accept("Delete CHIP failed: " + logic$message(exception));
        }
        ci.cancel();
    }

    @Unique
    private static String logic$message(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
