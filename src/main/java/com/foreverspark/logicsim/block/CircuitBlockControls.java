package com.foreverspark.logicsim.block;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

/** Optional world control surface for programmed circuit clocks. */
public final class CircuitBlockControls {
    private CircuitBlockControls() {}

    public static void initialize() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (hand != InteractionHand.MAIN_HAND || !player.isShiftKeyDown()) return InteractionResult.PASS;
            if (level.getBlockState(hit.getBlockPos()).getBlock() != ModBlocks.CIRCUIT_BLOCK) return InteractionResult.PASS;

            if (!level.isClientSide() && level.getBlockEntity(hit.getBlockPos()) instanceof CircuitBlockEntity circuit) {
                boolean redstoneGate = circuit.toggleRedstoneClockGate();
                if (redstoneGate) {
                    boolean powered = level.hasNeighborSignal(hit.getBlockPos());
                    player.sendSystemMessage(Component.literal(
                            "Circuit CLOCK mode: REDSTONE ENABLE (currently " + (powered ? "RUNNING" : "PAUSED") + ")"
                    ));
                } else {
                    player.sendSystemMessage(Component.literal("Circuit CLOCK mode: ALWAYS RUN"));
                }
            }
            return InteractionResult.SUCCESS;
        });
    }
}
