package com.foreverspark.logicsim.network;

import com.foreverspark.logicsim.block.CircuitBlockEntity;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

public final class ModNetworking {
    private static final double MAX_PROGRAM_DISTANCE_SQUARED = 100.0;

    private ModNetworking() {}

    public static void initialize() {
        PayloadTypeRegistry.serverboundPlay().register(ProgramCircuitPayload.TYPE, ProgramCircuitPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ProgramCircuitPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (payload.programJson() == null || payload.programJson().isBlank()
                    || payload.programJson().length() > ProgramCircuitPayload.MAX_JSON_LENGTH) return;
            if (player.distanceToSqr(Vec3.atCenterOf(payload.pos())) > MAX_PROGRAM_DISTANCE_SQUARED) return;
            if (!(player.level().getBlockEntity(payload.pos()) instanceof CircuitBlockEntity circuit)) return;

            if (ProgramCircuitPayload.CONTROL_TOGGLE_REDSTONE_CLOCK_GATE.equals(payload.programJson())) {
                boolean redstoneGate = circuit.toggleRedstoneClockGate();
                if (redstoneGate) {
                    boolean powered = player.level().hasNeighborSignal(payload.pos());
                    player.sendSystemMessage(Component.literal(
                            "Circuit CLOCK mode: REDSTONE ENABLE (currently " + (powered ? "RUNNING" : "PAUSED") + ")"
                    ));
                } else {
                    player.sendSystemMessage(Component.literal("Circuit CLOCK mode: ALWAYS RUN"));
                }
                return;
            }

            try {
                circuit.installProgramJson(payload.programJson());
                player.sendSystemMessage(Component.literal("Programmed Circuit Block with " + circuit.programName()));
            } catch (RuntimeException error) {
                String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                player.sendSystemMessage(Component.literal("Circuit program rejected: " + message));
            }
        });
    }
}
