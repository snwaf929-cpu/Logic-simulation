package com.foreverspark.logicsim.interconnect;

import com.foreverspark.logicsim.block.CableBlock;
import com.foreverspark.logicsim.block.DisplayBlockEntity;
import com.foreverspark.logicsim.block.ExternalDeviceBlockEntity;
import com.foreverspark.logicsim.editor.model.ExternalDeviceDescriptor;
import com.foreverspark.logicsim.editor.model.ExternalDeviceType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves world peripherals reachable through the typed cable networks attached to one Circuit Block. */
public final class ExternalDeviceDiscovery {
    private ExternalDeviceDiscovery() {}

    public static List<ExternalDeviceDescriptor> discover(Level level, BlockPos circuitPos) {
        if (level == null || circuitPos == null) return List.of();
        Set<CableNetworkCache.Network> networks = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        collectAdjacentNetworks(level, circuitPos, networks);
        for (BlockPos socket : CircuitPortLinks.sockets(level, circuitPos)) collectAdjacentNetworks(level, socket, networks);

        Map<String, ExternalDeviceDescriptor> devices = new LinkedHashMap<>();
        String world = level.dimension().toString();
        for (CableNetworkCache.Network network : networks) {
            for (CableNetworkCache.Endpoint endpoint : network.endpoints()) {
                BlockPos pos = endpoint.devicePos();
                if (endpoint.kind() == CableNetworkCache.EndpointKind.EXTERNAL_DEVICE
                        && level.getBlockEntity(pos) instanceof ExternalDeviceBlockEntity physical) {
                    ExternalDeviceDescriptor descriptor = new ExternalDeviceDescriptor(
                            physical.stableId(), physical.deviceType(), world, pos.getX(), pos.getY(), pos.getZ());
                    devices.put(descriptor.deviceId(), descriptor);
                } else if (endpoint.kind() == CableNetworkCache.EndpointKind.DISPLAY
                        && level.getBlockEntity(pos) instanceof DisplayBlockEntity) {
                    String id = "display:" + world + ":" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
                    devices.putIfAbsent(id, new ExternalDeviceDescriptor(id, ExternalDeviceType.DISPLAY, world,
                            pos.getX(), pos.getY(), pos.getZ()));
                }
            }
        }
        return List.copyOf(new ArrayList<>(devices.values()));
    }

    private static void collectAdjacentNetworks(Level level, BlockPos origin, Set<CableNetworkCache.Network> networks) {
        for (Direction direction : Direction.values()) {
            BlockPos cablePos = origin.relative(direction);
            if (!(level.getBlockState(cablePos).getBlock() instanceof CableBlock)) continue;
            CableNetworkCache.Network network = CableNetworkCache.network(level, cablePos);
            if (network != null) networks.add(network);
        }
    }
}
