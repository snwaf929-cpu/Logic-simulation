package com.foreverspark.logicsim.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.WeakHashMap;

/** Adds vanilla world-power input to the normal circuit-port lifecycle without polling the circuit unnecessarily. */
public final class CircuitPortWorldTicker {
    private static final Map<CircuitPortBlockEntity, Long> LAST_WORLD_VALUES = new WeakHashMap<>();

    private CircuitPortWorldTicker() {}

    public static void tick(Level level, BlockPos pos, BlockState state, CircuitPortBlockEntity socket) {
        CircuitPortBlockEntity.tick(level, pos, state, socket);
        if (level.isClientSide() || !socket.isBound()) return;
        if (!WorldPowerInput.shouldDrive(socket, level, pos)) {
            LAST_WORLD_VALUES.remove(socket);
            return;
        }
        long next = WorldPowerInput.value(level, pos);
        Long previous = LAST_WORLD_VALUES.put(socket, next);
        if (previous == null || previous.longValue() != next) socket.acceptCableValue(next);
    }
}
