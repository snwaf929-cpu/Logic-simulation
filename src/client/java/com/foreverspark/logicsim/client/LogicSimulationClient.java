package com.foreverspark.logicsim.client;

import com.foreverspark.logicsim.LogicSimulationMod;
import com.foreverspark.logicsim.block.ModBlockEntities;
import com.foreverspark.logicsim.client.chip.ClientChipLibrary;
import com.foreverspark.logicsim.client.device.BuiltinDevices;
import com.foreverspark.logicsim.client.render.DisplayBlockEntityRenderer;
import com.foreverspark.logicsim.client.screen.CircuitEditorScreen;
import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.platform.ClientEditorBridge;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

import java.io.IOException;

public final class LogicSimulationClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ensureBuiltInDevices();
        BlockEntityRenderers.register(ModBlockEntities.DISPLAY, DisplayBlockEntityRenderer::new);
        ClientEditorBridge.installEditorOpener(() -> Minecraft.getInstance().gui.setScreen(new CircuitEditorScreen()));
        ClientBoardNetworking.initialize();
        CircuitBlockUseHandler.register();
    }

    private static void ensureBuiltInDevices() {
        try {
            ClientChipLibrary library = new ClientChipLibrary();
            if (!library.exists(BuiltinDevices.DISPLAY)) {
                ChipDefinition display = BuiltinDevices.find(BuiltinDevices.DISPLAY);
                library.save(display.name, display.circuit, display.color, display.visual, "");
            }
        } catch (IOException | RuntimeException exception) {
            LogicSimulationMod.LOGGER.error("Could not install built-in display peripheral", exception);
        }
    }
}
