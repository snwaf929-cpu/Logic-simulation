package com.foreverspark.logicsim.block;

import net.fabricmc.api.ModInitializer;

public final class CircuitBlockControlsInitializer implements ModInitializer {
    @Override
    public void onInitialize() {
        CircuitBlockControls.initialize();
    }
}
