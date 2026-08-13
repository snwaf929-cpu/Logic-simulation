package com.foreverspark.logicsim;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LogicSimulationMod implements ModInitializer {
    public static final String MOD_ID = "logicsimulation";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Logic Simulation initialized. NAND core ready on Minecraft 26.2.");
    }
}
