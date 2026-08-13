package com.foreverspark.logicsim.client;

import com.foreverspark.logicsim.client.screen.CircuitEditorScreen;
import com.foreverspark.logicsim.platform.ClientEditorBridge;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;

public final class LogicSimulationClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientEditorBridge.installEditorOpener(() ->
                Minecraft.getInstance().gui.setScreen(new CircuitEditorScreen())
        );
    }
}
