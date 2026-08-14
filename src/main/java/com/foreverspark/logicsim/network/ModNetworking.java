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
        PayloadTypeRegistry.serverboundPlay().register(RequestCircuitBoardPayload.TYPE, RequestCircuitBoardPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SaveCircuitBoardPayload.TYPE, SaveCircuitBoardPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CircuitBoardPayload.TYPE, CircuitBoardPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(ProgramCircuitPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (payload.programJson() == null || payload.programJson().isBlank()
                    || payload.programJson().length() > ProgramCircuitPayload.MAX_JSON_LENGTH) return;
            if (!near(player, payload.pos())) return;
            if (!(player.level().getBlockEntity(payload.pos()) instanceof CircuitBlockEntity circuit)) return;
            long started = System.nanoTime();
            try {
                circuit.installProgramJson(payload.programJson());
                double elapsedMs = Math.max(0L, System.nanoTime() - started) / 1_000_000.0;
                player.sendSystemMessage(Component.literal(
                        "Programmed Circuit Block with " + circuit.programName()
                                + " in " + String.format(java.util.Locale.ROOT, "%.2f", elapsedMs) + " ms"
                ));
                if (elapsedMs >= 100.0) {
                    player.sendSystemMessage(Component.literal(
                            "Circuit performance warning: install/compile took over 100 ms. Shift+Right Click the block for runtime stats."
                    ));
                }
            } catch (RuntimeException error) {
                String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                player.sendSystemMessage(Component.literal("Circuit program rejected: " + message));
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(RequestCircuitBoardPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!near(player, payload.pos())) return;
            if (!(player.level().getBlockEntity(payload.pos()) instanceof CircuitBlockEntity circuit)) return;
            String json = circuit.boardJson();
            if (json.length() <= CircuitBlockEntity.MAX_BOARD_JSON) {
                ServerPlayNetworking.send(player, new CircuitBoardPayload(payload.pos(), json));
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(SaveCircuitBoardPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!near(player, payload.pos())) return;
            if (payload.boardJson() == null || payload.boardJson().length() > CircuitBlockEntity.MAX_BOARD_JSON) return;
            if (!(player.level().getBlockEntity(payload.pos()) instanceof CircuitBlockEntity circuit)) return;
            try {
                circuit.installBoardJson(payload.boardJson());
            } catch (RuntimeException error) {
                String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                player.sendSystemMessage(Component.literal("Board autosave failed: " + message));
            }
        });
    }

    private static boolean near(ServerPlayer player, net.minecraft.core.BlockPos pos) {
        return player.distanceToSqr(Vec3.atCenterOf(pos)) <= MAX_PROGRAM_DISTANCE_SQUARED;
    }
}
