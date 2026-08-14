package com.foreverspark.logicsim.interconnect;

import com.foreverspark.logicsim.block.CircuitPortBlockEntity;
import com.foreverspark.logicsim.block.DisplayBlock;
import com.foreverspark.logicsim.block.DisplayBlockEntity;
import com.foreverspark.logicsim.block.DisplayPorts;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Event-driven world cable values backed by cached physical nets. */
public final class CableRuntime {
    private CableRuntime() {}

    public static synchronized void setValue(Level level, BlockPos start, long value) {
        CableNetworkCache.Network network = CableNetworkCache.network(level, start);
        if (network == null) return;
        long normalized = value & mask(network.width());
        boolean changed = !network.initialized() || network.value() != normalized;
        boolean needsRefresh = network.fresh();
        if (!changed && !needsRefresh) return;
        network.store(normalized);
        notifyDevices(level, network, normalized);
    }

    public static synchronized long value(Level level, BlockPos pos) {
        CableNetworkCache.Network network = CableNetworkCache.network(level, pos);
        if (network == null) return 0L;
        if (network.fresh() && network.initialized()) notifyDevices(level, network, network.value());
        return network.value();
    }

    public static void invalidateTopology(Level level, BlockPos changedPos) {
        CableNetworkCache.invalidateAround(level, changedPos);
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
