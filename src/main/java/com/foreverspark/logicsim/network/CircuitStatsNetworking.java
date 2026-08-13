package com.foreverspark.logicsim.network;

import com.foreverspark.logicsim.block.CircuitBlockEntity;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

public final class CircuitStatsNetworking {
    private static final double MAX_DISTANCE_SQUARED = 100.0;

    private CircuitStatsNetworking() {}

    public static void initialize() {
        PayloadTypeRegistry.serverboundPlay().register(CircuitStatsRequest.TYPE, CircuitStatsRequest.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(CircuitStatsRequest.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (player.distanceToSqr(Vec3.atCenterOf(payload.circuitPos())) > MAX_DISTANCE_SQUARED) return;
            if (!(player.level().getBlockEntity(payload.circuitPos()) instanceof CircuitBlockEntity circuit)) return;
            if (!circuit.isProgrammed()) {
                player.sendSystemMessage(Component.literal("Circuit Block is not programmed"));
                return;
            }

            long executed = circuit.lastClockExecutedEdges();
            long pending = circuit.lastClockPendingEdges();
            double wallMs = circuit.lastClockWallNanos() / 1_000_000.0;
            String state = circuit.runtimeError().isBlank()
                    ? (pending == 0L ? "KEEPING UP / IDLE" : "BEHIND")
                    : "ERROR";
            String message = circuit.programName()
                    + " | clock edges: " + executed
                    + " | backlog: " + pending
                    + " | CPU: " + String.format(java.util.Locale.ROOT, "%.3f", wallMs) + " ms"
                    + " | " + state;
            player.sendSystemMessage(Component.literal(message));
            if (!circuit.runtimeError().isBlank()) {
                player.sendSystemMessage(Component.literal("Circuit runtime error: " + circuit.runtimeError()));
            }
        });
    }
}
