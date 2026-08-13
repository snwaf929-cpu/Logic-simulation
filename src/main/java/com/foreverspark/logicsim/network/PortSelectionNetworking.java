package com.foreverspark.logicsim.network;

import com.foreverspark.logicsim.block.CableVisualRefresh;
import com.foreverspark.logicsim.block.CircuitBlockEntity;
import com.foreverspark.logicsim.block.CircuitPortBlockEntity;
import com.foreverspark.logicsim.editor.model.PortDirection;
import com.foreverspark.logicsim.editor.model.PortSpec;
import com.foreverspark.logicsim.interconnect.PhysicalPortBinding;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

public final class PortSelectionNetworking {
    private static final int RANGE = 12;
    private static final double PLAYER_RANGE_SQUARED = 100.0;

    private PortSelectionNetworking() {}

    public static void initialize() {
        PayloadTypeRegistry.serverboundPlay().register(RequestCircuitPortsPayload.TYPE, RequestCircuitPortsPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(BindCircuitPortPayload.TYPE, BindCircuitPortPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CircuitPortsPayload.TYPE, CircuitPortsPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(RequestCircuitPortsPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            BlockPos socketPos = payload.socketPos();
            if (!near(player, socketPos)) return;
            if (!(player.level().getBlockEntity(socketPos) instanceof CircuitPortBlockEntity socket)) return;
            BlockPos circuitPos = nearestProgrammedCircuit(player, socketPos, socket);
            if (circuitPos == null) {
                player.sendSystemMessage(Component.literal("No programmed Circuit Block within " + RANGE + " blocks"));
                return;
            }
            CircuitBlockEntity circuit = (CircuitBlockEntity) player.level().getBlockEntity(circuitPos);
            String json = circuit.portCatalog().toJson();
            if (json.length() <= CircuitPortsPayload.MAX_CATALOG_JSON) {
                ServerPlayNetworking.send(player, new CircuitPortsPayload(socketPos, circuitPos, json));
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(BindCircuitPortPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!near(player, payload.socketPos()) || !near(player, payload.circuitPos())) return;
            if (distanceSquared(payload.socketPos(), payload.circuitPos()) > RANGE * RANGE) return;
            if (!(player.level().getBlockEntity(payload.socketPos()) instanceof CircuitPortBlockEntity socket)) return;
            if (!(player.level().getBlockEntity(payload.circuitPos()) instanceof CircuitBlockEntity circuit)) return;
            try {
                PortDirection direction = PortDirection.valueOf(payload.direction().trim().toUpperCase(Locale.ROOT));
                PortSpec spec = circuit.portSpec(payload.portName(), direction);
                if (spec == null) throw new IllegalArgumentException("Port no longer exists");
                new PhysicalPortBinding(spec);
                socket.bind(payload.circuitPos(), spec);
                CableVisualRefresh.aroundSocket(player.level(), payload.socketPos(), socket);
                if (direction == PortDirection.OUTPUT) circuit.publishSocket(socket);
                player.sendSystemMessage(Component.literal("I/O Connector = " + spec.name() + " [" + spec.width() + "] " + direction.name()));
            } catch (RuntimeException error) {
                String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                player.sendSystemMessage(Component.literal("Cannot bind I/O Connector: " + message));
            }
        });
    }

    private static BlockPos nearestProgrammedCircuit(ServerPlayer player, BlockPos socketPos, CircuitPortBlockEntity socket) {
        if (socket.isBound() && socket.circuitPos() != null && distanceSquared(socketPos, socket.circuitPos()) <= RANGE * RANGE
                && player.level().getBlockEntity(socket.circuitPos()) instanceof CircuitBlockEntity bound && bound.isProgrammed()) {
            return socket.circuitPos();
        }
        BlockPos best = null;
        long bestDistance = Long.MAX_VALUE;
        for (int dx = -RANGE; dx <= RANGE; dx++) {
            for (int dy = -RANGE; dy <= RANGE; dy++) {
                for (int dz = -RANGE; dz <= RANGE; dz++) {
                    long distance = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                    if (distance > RANGE * RANGE || distance >= bestDistance) continue;
                    BlockPos candidate = socketPos.offset(dx, dy, dz);
                    if (player.level().getBlockEntity(candidate) instanceof CircuitBlockEntity circuit && circuit.isProgrammed()) {
                        best = candidate.immutable();
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private static boolean near(ServerPlayer player, BlockPos pos) {
        return player.distanceToSqr(Vec3.atCenterOf(pos)) <= PLAYER_RANGE_SQUARED;
    }

    private static long distanceSquared(BlockPos a, BlockPos b) {
        long dx = (long) a.getX() - b.getX();
        long dy = (long) a.getY() - b.getY();
        long dz = (long) a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
