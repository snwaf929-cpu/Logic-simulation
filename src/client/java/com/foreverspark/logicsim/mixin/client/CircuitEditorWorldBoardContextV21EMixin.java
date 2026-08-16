package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitEditorScreen;
import com.foreverspark.logicsim.client.screen.WorldBoardContextAccess;
import com.foreverspark.logicsim.platform.ClientEditorBridge;
import org.spongepowered.asm.mixin.Mixin;

/** Concrete context query shared by CHIP-only QoL guards; physical BOARD sessions keep their autosave workflow. */
@Mixin(value = CircuitEditorScreen.class, priority = 1450)
public abstract class CircuitEditorWorldBoardContextV21EMixin implements WorldBoardContextAccess {
    @Override
    public boolean logic$isWorldBoardContext() {
        return ClientEditorBridge.activeCircuitPos() != null;
    }
}
