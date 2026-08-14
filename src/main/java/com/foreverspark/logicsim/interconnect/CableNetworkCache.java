package com.foreverspark.logicsim.interconnect;

import com.foreverspark.logicsim.block.CableBlock;
import com.foreverspark.logicsim.block.CircuitBlockEntity;
import com.foreverspark.logicsim.block.CircuitPortBlockEntity;
import com.foreverspark.logicsim.block.DisplayBlock;
import com.foreverspark.logicsim.block.DisplayPorts;
import com.foreverspark.logicsim.block.IoConnectorBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Cached physical cable topology. Networks persist across normal world ticks and are invalidated by topology
 * changes. A short safety TTL repairs any missed external topology mutation without paying a full cable BFS every
 * tick. Values remain live inside the cached Network object, so this changes discovery cost rather than logic.
 */
public final class CableNetworkCache {
    private static final int MAX_SEGMENTS = 8192;
    private static final long TOPOLOGY_SAFETY_TTL_TICKS = 20L;
    private static final Map<Level, State> STATES = new WeakHashMap<>();

    private CableNetworkCache() {}

    public static synchronized Network network(Level level, BlockPos start) {
        if (level == null || start == null) return null;
        State state = STATES.computeIfAbsent(level, ignored -> new State());
        Network cached = state.bySegment.get(start);
        if (cached != null && cached.matches(level, start)) return cached;
        if (cached != null) invalidateNetwork(state, cached);
        return build(level, state, start);
    }

    public static synchronized void invalidateAround(Level level, BlockPos changedPos) {
        if (level == null || changedPos == null) return;
        State state = STATES.get(level);
        if (state == null) return;
        Set<Network> affected = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Network direct = state.bySegment.get(changedPos);
        if (direct != null) affected.add(direct);
        for (Direction direction : Direction.values()) {
            Network neighbor = state.bySegment.get(changedPos.relative(direction));
            if (neighbor != null) affected.add(neighbor);
        }
        for (Network network : affected) invalidateNetwork(state, network);
    }

    public static synchronized void clear(Level level) {
        if (level != null) STATES.remove(level);
    }

    private static Network build(Level level, State state, BlockPos start) {
        BlockState startState = level.getBlockState(start);
        if (!(startState.getBlock() instanceof CableBlock startCable)) return null;
        Set<BlockPos> segments = CableRun.collect(level, start, MAX_SEGMENTS);
        if (segments.isEmpty()) return null;

        Set<Network> overlapping = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (BlockPos segment : segments) {
            Network old = state.bySegment.get(segment);
            if (old != null) overlapping.add(old);
        }
        for (Network old : overlapping) invalidateNetwork(state, old);

        Long seed = null;
        boolean conflict = false;
        for (BlockPos segment : segments) {
            Long value = state.seeds.remove(segment);
            if (value == null) continue;
            if (seed == null) seed = value;
            else if (seed.longValue() != value.longValue()) conflict = true;
        }

        List<Endpoint> endpoints = discoverEndpoints(level, segments, startCable);
        Network network = new Network(
                startCable.cableKind(),
                startCable.bitWidth(),
                segments,
                endpoints,
                conflict || seed == null ? 0L : seed,
                !conflict && seed != null,
                true,
                level.getGameTime()
        );
        for (BlockPos segment : segments) state.bySegment.put(segment, network);
        return network;
    }

    private static List<Endpoint> discoverEndpoints(Level level, Set<BlockPos> segments, CableBlock cable) {
        ArrayList<Endpoint> endpoints = new ArrayList<>();
        for (BlockPos cablePos : segments) {
            for (Direction direction : Direction.values()) {
                BlockPos devicePos = cablePos.relative(direction);
                if (segments.contains(devicePos)) continue;
                BlockState state = level.getBlockState(devicePos);
                Direction deviceFace = direction.getOpposite();
                if (state.getBlock() instanceof IoConnectorBlock
                        && level.getBlockEntity(devicePos) instanceof CircuitPortBlockEntity socket
                        && socket.accepts(cable)) {
                    endpoints.add(new Endpoint(devicePos.immutable(), deviceFace, EndpointKind.CIRCUIT_SOCKET));
                } else if (level.getBlockEntity(devicePos) instanceof CircuitBlockEntity circuit
                        && DirectPortResolver.unique(circuit, cable.cableKind(), cable.bitWidth()) != null) {
                    endpoints.add(new Endpoint(devicePos.immutable(), deviceFace, EndpointKind.CIRCUIT_DIRECT));
                } else if (state.getBlock() instanceof DisplayBlock
                        && DisplayPorts.accepts(state, deviceFace, cable.cableKind(), cable.bitWidth())) {
                    endpoints.add(new Endpoint(devicePos.immutable(), deviceFace, EndpointKind.DISPLAY));
                }
            }
        }
        return List.copyOf(endpoints);
    }

    private static void invalidateNetwork(State state, Network network) {
        for (BlockPos segment : network.segments) {
            if (state.bySegment.get(segment) == network) state.bySegment.remove(segment);
            if (network.initialized) state.seeds.put(segment, network.value);
        }
    }

    private static final class State {
        private final Map<BlockPos, Network> bySegment = new LinkedHashMap<>();
        private final Map<BlockPos, Long> seeds = new LinkedHashMap<>();
    }

    public enum EndpointKind { CIRCUIT_SOCKET, CIRCUIT_DIRECT, DISPLAY }
    public record Endpoint(BlockPos devicePos, Direction deviceFace, EndpointKind kind) {}

    public static final class Network {
        private final CableKind kind;
        private final int width;
        private final Set<BlockPos> segments;
        private final List<Endpoint> endpoints;
        private final long builtAtGameTime;
        private long value;
        private boolean initialized;
        private boolean fresh;

        private Network(CableKind kind, int width, Set<BlockPos> segments, List<Endpoint> endpoints, long value,
                        boolean initialized, boolean fresh, long builtAtGameTime) {
            this.kind = kind;
            this.width = width;
            this.segments = segments;
            this.endpoints = endpoints;
            this.value = value;
            this.initialized = initialized;
            this.fresh = fresh;
            this.builtAtGameTime = builtAtGameTime;
        }

        public CableKind kind() { return kind; }
        public int width() { return width; }
        public Set<BlockPos> segments() { return segments; }
        public List<Endpoint> endpoints() { return endpoints; }
        public long value() { return initialized ? value : 0L; }
        public boolean initialized() { return initialized; }
        public boolean fresh() { return fresh; }

        public void store(long value) {
            this.value = value;
            this.initialized = true;
        }

        public void markObserved() { fresh = false; }

        private boolean matches(Level level, BlockPos start) {
            long age = level.getGameTime() - builtAtGameTime;
            if (age < 0L || age >= TOPOLOGY_SAFETY_TTL_TICKS) return false;
            BlockState state = level.getBlockState(start);
            return state.getBlock() instanceof CableBlock cable && cable.cableKind() == kind && cable.bitWidth() == width;
        }
    }
}
