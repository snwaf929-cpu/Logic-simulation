package com.foreverspark.logicsim.client;

import com.foreverspark.logicsim.block.ModBlocks;
import com.foreverspark.logicsim.platform.ClientEditorBridge;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

public final class CircuitBlockUseHandler {
    private CircuitBlockUseHandler() {}

    public static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (level.getBlockState(hit.getBlockPos()).getBlock() != ModBlocks.CIRCUIT_BLOCK) return InteractionResult.PASS;
            ClientEditorBridge.openEditor(hit.getBlockPos());
            return InteractionResult.SUCCESS;
        });
    }
}
