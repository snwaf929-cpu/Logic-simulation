package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.ComponentLibraryWidget;
import com.foreverspark.logicsim.editor.model.NodeKind;
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

import java.util.function.Consumer;

/** Adds Phase 2 electrical-routing primitives directly after the MERGER library row. */
@Mixin(value = ComponentLibraryWidget.class, priority = 1300)
public abstract class ComponentLibraryPhase2RoutingMixin {
    @Unique private static final int ROW_H = 18;
    @Unique private static final int ROW_STEP = 19;
    @Shadow private CircuitCanvasWidget canvas;
    @Shadow private Consumer<String> status;

    @Unique private int logic$sliceRowY = Integer.MIN_VALUE;
    @Unique private int logic$netRowY = Integer.MIN_VALUE;

    @Inject(method = "drawComponent", at = @At("RETURN"), cancellable = true)
    private void logic$phase2Rows(GuiGraphicsExtractor graphics, String name, NodeKind kind, int color,
                                  int y, int clipTop, int clipBottom, CallbackInfoReturnable<Integer> cir) {
        if (kind != NodeKind.MERGER) return;
        int row = cir.getReturnValue();
        logic$sliceRowY = row;
        logic$drawRow(graphics, row, clipTop, clipBottom, "BUS SLICE", 0xFF55AFC2, "1-64");
        row += ROW_STEP;
        logic$netRowY = row;
        logic$drawRow(graphics, row, clipTop, clipBottom, "NET LABEL", 0xFF8E73D8, "named net");
        cir.setReturnValue(row + ROW_STEP);
    }

    @Unique
    private void logic$drawRow(GuiGraphicsExtractor graphics, int rowY, int clipTop, int clipBottom,
                               String title, int accent, String badge) {
        if (rowY + ROW_H <= clipTop || rowY >= clipBottom) return;
        ComponentLibraryWidget self = (ComponentLibraryWidget)(Object)this;
        int left = self.getX() + 5;
        int right = self.getX() + self.getWidth() - 5;
        graphics.fill(left, rowY, right, rowY + ROW_H - 1, 0xFF1C2229);
        graphics.fill(left, rowY, left + 3, rowY + ROW_H - 1, accent);
        graphics.text(Minecraft.getInstance().font, title, self.getX() + 13, rowY + 5, 0xFFD7DEE8, false);
        int bx = right - 6 - Minecraft.getInstance().font.width(badge);
        graphics.text(Minecraft.getInstance().font, badge, Math.max(self.getX() + 82, bx), rowY + 5, 0xFF7FA7C8, false);
    }

    @Inject(method = "onClick", at = @At("HEAD"), cancellable = true)
    private void logic$phase2Click(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        if (event.button() != 0) return;
        ComponentLibraryWidget self = (ComponentLibraryWidget)(Object)this;
        int left = self.getX() + 5;
        int right = self.getX() + self.getWidth() - 5;
        int clipTop = self.getY() + 25;
        int clipBottom = self.getY() + self.getHeight() - 30;
        if (event.x() < left || event.x() >= right) return;

        if (logic$visible(logic$sliceRowY, event.y(), clipTop, clipBottom)) {
            canvas.setPlacement(NodeKind.BUS_SLICE);
            status.accept("Place BUS SLICE — double-click it or press W to define ranges like OPCODE=12:4, OPERAND=0:12");
            ci.cancel();
            return;
        }
        if (logic$visible(logic$netRowY, event.y(), clipTop, clipBottom)) {
            canvas.setPlacement(NodeKind.NET_LABEL);
            status.accept("Place NET LABEL — double-click it or press W to name the electrical net and set its width");
            ci.cancel();
        }
    }

    @Unique
    private static boolean logic$visible(int rowY, double mouseY, int top, int bottom) {
        return rowY != Integer.MIN_VALUE && rowY + ROW_H > top && rowY < bottom && mouseY >= rowY && mouseY < rowY + ROW_H;
    }
}
