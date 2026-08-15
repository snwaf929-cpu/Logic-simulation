package com.foreverspark.logicsim.client.screen.v2;

import com.foreverspark.logicsim.editor.model.BoardTemplateDefinition;
import com.foreverspark.logicsim.editor.model.BoardTemplateReplacementPreview;
import com.foreverspark.logicsim.editor.model.PortDirection;
import net.minecraft.client.gui.screens.Screen;

/** Implemented onto CircuitCanvasWidget by the Phase 4 template/socket mixin. */
public interface BoardTemplateCanvasAccess {
    void logic$beginSocketPlacement(PortDirection direction);
    boolean logic$configureSelectedSocket(Screen parent);
    int logic$selectedTemplateInstanceId();
    boolean logic$insertBoardTemplate(BoardTemplateDefinition template);
    BoardTemplateReplacementPreview logic$previewTemplateReplacement(BoardTemplateDefinition template);
    boolean logic$replaceSelectedTemplate(BoardTemplateDefinition template);
}
