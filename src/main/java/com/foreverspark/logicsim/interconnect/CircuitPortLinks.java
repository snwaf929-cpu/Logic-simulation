package com.foreverspark.logicsim.interconnect;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class CircuitPortLinks {
    private static final Map<Level, Map<BlockPos, LinkedHashSet<BlockPos>>> LINKS = new WeakHashMap<>();

    private CircuitPortLinks() {}

    public static synchronized void register(Level level, BlockPos circuitPos, BlockPos socketPos) {
        if (level == null || circuitPos == null || socketPos == null) return;
        LINKS.computeIfAbsent(level, ignored -> new java.util.HashMap<>())
                .computeIfAbsent(circuitPos.immutable(), ignored -> new LinkedHashSet<>())
                .add(socketPos.immutable());
    }

    public static synchronized void unregister(Level level, BlockPos circuitPos, BlockPos socketPos) {
        Map<BlockPos, LinkedHashSet<BlockPos>> world = LINKS.get(level);
        if (world == null || circuitPos == null || socketPos == null) return;
        Set<BlockPos> sockets = world.get(circuitPos);
        if (sockets == null) return;
        sockets.remove(socketPos);
        if (sockets.isEmpty()) world.remove(circuitPos);
    }

    public static synchronized Set<BlockPos> sockets(Level level, BlockPos circuitPos) {
        Map<BlockPos, LinkedHashSet<BlockPos>> world = LINKS.get(level);
        if (world == null || circuitPos == null) return Set.of();
        Set<BlockPos> sockets = world.get(circuitPos);
        return sockets == null ? Set.of() : Set.copyOf(sockets);
    }
}
