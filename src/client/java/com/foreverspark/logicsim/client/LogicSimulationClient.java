package com.foreverspark.logicsim.client;

import com.foreverspark.logicsim.block.ModBlockEntities;
import com.foreverspark.logicsim.client.render.DisplayBlockEntityRenderer;
import com.foreverspark.logicsim.client.render.DisplayTextureCache;
import com.foreverspark.logicsim.client.render.RealtimeDisplayTextureCache;
import com.foreverspark.logicsim.client.screen.CircuitEditorScreen;
import com.foreverspark.logicsim.platform.ClientEditorBridge;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

public final class LogicSimulationClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        BlockEntityRenderers.register(ModBlockEntities.DISPLAY, DisplayBlockEntityRenderer::new);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            DisplayTextureCache.clientTick();
            RealtimeDisplayTextureCache.clientTick();
        });
        ClientEditorBridge.installEditorOpener(() -> Minecraft.getInstance().gui.setScreen(new CircuitEditorScreen()));
        ClientBoardNetworking.initialize();
        CircuitBlockUseHandler.register();
    }
}
