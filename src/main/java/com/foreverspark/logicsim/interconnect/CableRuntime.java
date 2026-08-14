package com.foreverspark.logicsim.interconnect;

import com.foreverspark.logicsim.block.CircuitBlockEntity;
import com.foreverspark.logicsim.block.CircuitPortBlockEntity;
import com.foreverspark.logicsim.block.DisplayBlock;
import com.foreverspark.logicsim.block.DisplayBlockEntity;
import com.foreverspark.logicsim.block.DisplayPorts;
import com.foreverspark.logicsim.editor.model.PortDirection;
import com.foreverspark.logicsim.editor.model.PortSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class CableRuntime {
    private CableRuntime() {}

    public static synchronized void setValue(Level level, BlockPos start, long value) {
        CableNetworkCache.Network network = CableNetworkCache.network(level, start);
        if (network == null) return;
        long normalized = value & mask(network.width());
        boolean changed = !network.initialized() || network.value() != normalized;
        boolean refresh = network.fresh();
        if (!changed && !refresh) return;
        network.store(normalized);
        notifyDevices(level, network, normalized);
    }

    public static synchronized long value(Level level, BlockPos pos) {
        CableNetworkCache.Network network = CableNetworkCache.network(level, pos);
        if (network == null) return 0L;
        refreshDirectSource(level, network);
        if (network.fresh() && network.initialized()) notifyDevices(level, network, network.value());
        return network.value();
    }

    public static void invalidateTopology(Level level, BlockPos changedPos) {
        CableNetworkCache.invalidateAround(level, changedPos);
    }

    private static void refreshDirectSource(Level level, CableNetworkCache.Network network) {
        Long source = null;
        for (CableNetworkCache.Endpoint endpoint : network.endpoints()) {
            if (endpoint.kind() != CableNetworkCache.EndpointKind.CIRCUIT_DIRECT) continue;
            if (!(level.getBlockEntity(endpoint.devicePos()) instanceof CircuitBlockEntity circuit)) continue;
            PortSpec port = DirectPortResolver.unique(circuit, network.kind(), network.width());
            if (port == null || port.direction() != PortDirection.OUTPUT) continue;
            long value = circuit.outputValue(port.name()) & mask(network.width());
            if (source == null) source = value;
            else if (source.longValue() != value) {
                source = 0L;
                break;
            }
        }
        if (source != null && (!network.initialized() || network.value() != source.longValue())) {
            network.store(source);
            notifyDevices(level, network, source);
        }
    }

    private static void notifyDevices(Level level, CableNetworkCache.Network network, long value) {
        network.markObserved();
        for (CableNetworkCache.Endpoint endpoint : network.endpoints()) {
            switch (endpoint.kind()) {
                case CIRCUIT_SOCKET -> {
                    if (!(level.getBlockEntity(endpoint.devicePos()) instanceof CircuitPortBlockEntity socket)) continue;
                    PhysicalPortBinding binding = socket.binding();
                    if (binding != null && binding.accepts(network.kind(), network.width())) socket.acceptCableValue(value);
                }
                case CIRCUIT_DIRECT -> {
                    if (!(level.getBlockEntity(endpoint.devicePos()) instanceof CircuitBlockEntity circuit)) continue;
                    PortSpec port = DirectPortResolver.unique(circuit, network.kind(), network.width());
                    if (port != null && port.direction() == PortDirection.INPUT) circuit.acceptExternalInput(port.name(), value);
                }
                case DISPLAY -> {
                    BlockState state = level.getBlockState(endpoint.devicePos());
                    if (!(state.getBlock() instanceof DisplayBlock)) continue;
                    if (!DisplayPorts.accepts(state, endpoint.deviceFace(), network.kind(), network.width())) continue;
                    if (level.getBlockEntity(endpoint.devicePos()) instanceof DisplayBlockEntity display) {
                        display.acceptCableValue(endpoint.deviceFace(), value);
                    }
                }
            }
        }
    }

    private static long mask(int width) {
        return width >= 64 ? -1L : (1L << width) - 1L;
    }
}
