package com.foreverspark.logicsim;

import com.foreverspark.logicsim.block.ModBlocks;
import com.foreverspark.logicsim.block.entity.ModBlockEntities;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LogicSimulationMod implements ModInitializer {
    public static final String MOD_ID = "logicsimulation";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ModBlocks.initialize();
        ModBlockEntities.initialize();
        LOGGER.info("Logic Simulation initialized on Minecraft 26.2.");
    }
}
