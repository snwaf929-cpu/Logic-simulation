package com.foreverspark.logicsim.client;

import com.foreverspark.logicsim.block.ConnectorBlocks;
import com.foreverspark.logicsim.block.ModBlocks;
import com.foreverspark.logicsim.network.CircuitStatsRequest;
import com.foreverspark.logicsim.network.RequestCircuitPortsPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

public final class CircuitBlockUseHandler {
    private CircuitBlockUseHandler() {}

    public static void register() {
        ClientPortNetworking.initialize();
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            var block = level.getBlockState(hit.getBlockPos()).getBlock();
            if (block == ModBlocks.CIRCUIT_BLOCK) {
                if (player.isShiftKeyDown()) {
                    ClientPlayNetworking.send(new CircuitStatsRequest(hit.getBlockPos()));
                } else {
                    // Ask the server for this block's editable board before opening the editor.
                    // This makes the board world-save data instead of a temporary client screen.
                    ClientBoardNetworking.requestOpen(hit.getBlockPos());
                }
                return InteractionResult.SUCCESS;
            }
            if (block == ConnectorBlocks.IO_CONNECTOR) {
                ClientPlayNetworking.send(new RequestCircuitPortsPayload(hit.getBlockPos()));
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
    }
}
