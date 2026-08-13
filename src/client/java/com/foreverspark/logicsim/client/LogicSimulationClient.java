package com.foreverspark.logicsim.client;

import com.foreverspark.logicsim.block.entity.ModBlockEntities;
import com.foreverspark.logicsim.client.display.DisplayPanelRenderer;
import com.foreverspark.logicsim.client.screen.CircuitEditorScreen;
import com.foreverspark.logicsim.platform.ClientEditorBridge;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

public final class LogicSimulationClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientEditorBridge.installEditorOpener(() ->
                Minecraft.getInstance().gui.setScreen(new CircuitEditorScreen())
        );
        BlockEntityRenderers.register(ModBlockEntities.DISPLAY_PANEL, DisplayPanelRenderer::new);
    }
}
