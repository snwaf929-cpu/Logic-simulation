package com.foreverspark.logicsim.block;

import com.foreverspark.logicsim.LogicSimulationMod;
import com.foreverspark.logicsim.display.DisplayCommandCodec;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.PortDirection;
import com.foreverspark.logicsim.editor.model.PortSpec;
import com.foreverspark.logicsim.interconnect.CableKind;
import com.foreverspark.logicsim.interconnect.CableNetworkCache;
import com.foreverspark.logicsim.interconnect.CableRuntime;
import com.foreverspark.logicsim.interconnect.CircuitPortCatalog;
import com.foreverspark.logicsim.interconnect.CircuitPortLinks;
import com.foreverspark.logicsim.interconnect.CircuitProgram;
import com.foreverspark.logicsim.interconnect.CircuitProgramRuntime;
import com.foreverspark.logicsim.interconnect.DirectPortResolver;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CircuitBlockEntity extends BlockEntity {
    public static final int MAX_BOARD_JSON = 2_000_000;
    private static final int MAX_PROGRAM_JSON = 2_000_000;
    private static final Gson BOARD_GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Worker budgets are wall-clock fairness limits, NOT Minecraft-tick limits. */
    private static final long WORKER_SLICE_WALL_BUDGET_NANOS = 1_500_000L;
    private static final long WORKER_HARD_EDGE_LIMIT_PER_SLICE = 500_000L;
    private static final long WORKER_CHUNK_EDGES_PER_CLOCK = 1_024L;
    private static final int MAX_COALESCED_DISPLAY_PIXELS = 262_144;
    private static final int WORLD_FLUSH_BATCH_EVENTS = 16_384;
    private static final long WORLD_FLUSH_WALL_BUDGET_NANOS = 4_000_000L;
    private static final long BENCHMARK_WINDOW_NANOS = 1_000_000_000L;

    /** Editable CAD board stored independently from the compiled/running program. */
    private String boardJson = "";
    private String programJson = "";

    /** Every mutation/read of the compiled runtime is serialized through this lock. */
    private final Object runtimeLock = new Object();
    private volatile CircuitProgramRuntime runtime;
    private volatile String runtimeError = "";

    /** Clock state belongs to the dedicated CircuitSimulationWorker, not the block-entity ticker. */
    private long lastClockNanos;
    private volatile long lastClockExecutedEdges;
    private volatile long lastClockPendingEdges;
    private volatile long lastClockWallNanos;
    private volatile long lastClockTargetHz;
    private volatile long lastClockActualHz;

    /**
     * Minecraft-facing outputs are intentionally bounded/coalesced. Ordinary ports keep only the newest value.
     * Display DATA64 ports keep final framebuffer intent: CLEAR plus the newest write for each pixel coordinate.
     * This lets MHz simulation continue during /tick freeze without allocating millions of world events.
     */
    private final Map<String, OutputEvent> pendingLatestOutputs = new LinkedHashMap<>();
    private final Map<String, DisplayCommandBuffer> pendingDisplayCommands = new HashMap<>();
    private final Map<String, Long> lastCapturedOutputs = new HashMap<>();
    private volatile Set<String> displayStreamPorts = Set.of();

    /** Independent-worker throughput accounting. */
    private long benchmarkStartNanos;
    private long benchmarkEdges;
    private long benchmarkCpuNanos;

    public CircuitBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CIRCUIT, pos, state);
    }

    /**
     * Minecraft's block-entity ticker performs lifecycle/world-I/O duties only.
     * It does NOT advance simulated clocks.
     */
    public static void tick(Level level, BlockPos pos, BlockState state, CircuitBlockEntity circuit) {
        if (level.isClientSide()) return;
        if (!circuit.isProgrammed()) return;

        // Register only from a real server tick. Do not start MHz simulation from chunk restore/clearRemoved().
        CircuitSimulationWorker.register(circuit);
        circuit.refreshDisplayStreamPorts();
        circuit.flushPendingOutputsOnServerThread();
    }

    public boolean isProgrammed() { return runtime != null; }
    public String runtimeError() { return runtimeError; }
    public String programName() {
        synchronized (runtimeLock) {
            return runtime == null ? "" : runtime.program().root.name;
        }
    }
    public String boardJson() { return boardJson; }
    public long lastClockExecutedEdges() { return lastClockExecutedEdges; }
    public long lastClockPendingEdges() { return lastClockPendingEdges; }
    public long lastClockWallNanos() { return lastClockWallNanos; }
    public long lastClockTargetHz() { return lastClockTargetHz; }
    public long lastClockActualHz() { return lastClockActualHz; }

    public CircuitPortCatalog portCatalog() {
        synchronized (runtimeLock) {
            return runtime == null
                    ? new CircuitPortCatalog("", java.util.List.of(), java.util.List.of())
                    : new CircuitPortCatalog(runtime.program().root.name, runtime.inputPorts(), runtime.outputPorts());
        }
    }

    public PortSpec portSpec(String name, PortDirection direction) {
        synchronized (runtimeLock) {
            return runtime == null ? null : runtime.port(name, direction);
        }
    }

    /**
     * Stores the editable board itself. This is deliberately separate from installProgramJson():
     * closing the editor must never require compiling or naming a reusable chip just to preserve work.
     */
    public void installBoardJson(String json) {
        String source = json == null ? "" : json.trim();
        if (source.length() > MAX_BOARD_JSON) throw new IllegalArgumentException("Circuit board is too large");
        CircuitDocument board;
        if (source.isBlank()) {
            board = new CircuitDocument();
        } else {
            board = BOARD_GSON.fromJson(source, CircuitDocument.class);
            if (board == null) throw new IllegalArgumentException("Circuit board is empty");
        }
        board.normalize();
        String canonical = BOARD_GSON.toJson(board);
        if (canonical.length() > MAX_BOARD_JSON) throw new IllegalArgumentException("Circuit board is too large");
        this.boardJson = canonical;
        setChanged();
    }

    public void installProgramJson(String json) {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("Circuit program is empty");
        if (json.length() > MAX_PROGRAM_JSON) throw new IllegalArgumentException("Circuit program is too large");
        CircuitProgram parsed = CircuitProgram.fromJson(json);
        CircuitProgramRuntime compiled = new CircuitProgramRuntime(parsed);
        synchronized (runtimeLock) {
            this.programJson = parsed.toJson();
            this.runtime = compiled;
            this.runtimeError = "";
            resetClockStateLocked(System.nanoTime());
            pendingLatestOutputs.clear();
            pendingDisplayCommands.clear();
            lastCapturedOutputs.clear();
            captureOutputChangesLocked();
        }
        // A freshly programmed live block may start immediately. Restored blocks wait for their first real tick.
        if (level != null && !level.isClientSide()) CircuitSimulationWorker.register(this);
        setChanged();
    }

    /** Called only from CircuitSimulationWorker's dedicated daemon thread. */
    boolean runClockWorkerSlice(long now) {
        boolean didWork = false;
        synchronized (runtimeLock) {
            CircuitProgramRuntime current = runtime;
            if (current == null) return false;

            int clockCount = current.timing().clocks().size();
            if (clockCount == 0) {
                lastClockExecutedEdges = 0L;
                lastClockPendingEdges = 0L;
                lastClockWallNanos = 0L;
                lastClockTargetHz = 0L;
                lastClockActualHz = 0L;
                captureOutputChangesLocked();
                return false;
            }

            if (lastClockNanos == 0L) resetClockStateLocked(now);
            long elapsed = Math.max(0L, now - lastClockNanos);
            lastClockNanos = now;

            // Convert real elapsed wall time into pending virtual clock edges.
            if (elapsed > 0L) current.advanceClocksNanos(elapsed, 0L, this::captureOutputChangesLocked);

            long started = System.nanoTime();
            long emittedTotal = 0L;
            while (emittedTotal < WORKER_HARD_EDGE_LIMIT_PER_SLICE) {
                long remaining = WORKER_HARD_EDGE_LIMIT_PER_SLICE - emittedTotal;
                long fairRemainingPerClock = Math.max(1L, remaining / clockCount);
                long chunkPerClock = Math.min(WORKER_CHUNK_EDGES_PER_CLOCK, fairRemainingPerClock);
                long emitted = current.advanceClocksNanos(0L, chunkPerClock, this::captureOutputChangesLocked);
                if (emitted <= 0L) break;
                emittedTotal = saturatingAdd(emittedTotal, emitted);
                didWork = true;
                if (System.nanoTime() - started >= WORKER_SLICE_WALL_BUDGET_NANOS) break;
            }

            long cpuNanos = Math.max(0L, System.nanoTime() - started);
            lastClockExecutedEdges = emittedTotal;
            lastClockPendingEdges = pendingClockEdgesLocked();
            lastClockWallNanos = cpuNanos;
            lastClockTargetHz = targetClockHzLocked();

            benchmarkEdges = saturatingAdd(benchmarkEdges, emittedTotal);
            benchmarkCpuNanos = saturatingAdd(benchmarkCpuNanos, cpuNanos);
            updateBenchmarkLocked(now);
        }
        return didWork;
    }

    void recordWorkerFailure(Throwable error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        runtimeError = "Clock worker failure: " + message;
        CircuitSimulationWorker.unregister(this);
        LogicSimulationMod.LOGGER.error("Circuit clock worker failed at {}", worldPosition, error);
    }

    private void updateBenchmarkLocked(long now) {
        if (benchmarkStartNanos == 0L) benchmarkStartNanos = now;
        long windowNanos = Math.max(0L, now - benchmarkStartNanos);
        if (windowNanos < BENCHMARK_WINDOW_NANOS) return;

        double seconds = windowNanos / 1_000_000_000.0;
        long actualEdgesPerSecond = seconds <= 0.0 ? 0L : Math.round(benchmarkEdges / seconds);
        long actualCyclesPerSecond = actualEdgesPerSecond / 2L;
        lastClockActualHz = actualCyclesPerSecond;
        long targetHz = targetClockHzLocked();
        long pending = pendingClockEdgesLocked();
        double workerCpuMs = benchmarkCpuNanos / 1_000_000.0;

        LogicSimulationMod.LOGGER.info(
                "[CLOCK BENCH/world] pos={} targetHz={} actualHz={} edgesPerSec={} pendingEdges={} outputQueue={} workerCpuMs={} minecraftTickIndependent=true",
                worldPosition,
                targetHz,
                actualCyclesPerSecond,
                actualEdgesPerSecond,
                pending,
                pendingWorldOutputsLocked(),
                String.format(java.util.Locale.ROOT, "%.3f", workerCpuMs)
        );

        benchmarkStartNanos = now;
        benchmarkEdges = 0L;
        benchmarkCpuNanos = 0L;
    }

    private long targetClockHzLocked() {
        CircuitProgramRuntime current = runtime;
        if (current == null) return 0L;
        long total = 0L;
        for (var address : current.timing().clocks()) {
            if (!current.timing().active(address.scopePath(), address.nodeId())) continue;
            total = saturatingAdd(total, current.timing().frequencyHz(address.scopePath(), address.nodeId()));
        }
        return total;
    }

    private long pendingClockEdgesLocked() {
        CircuitProgramRuntime current = runtime;
        if (current == null) return 0L;
        long total = 0L;
        for (var address : current.timing().clocks()) {
            total = saturatingAdd(total, current.timing().pendingEdges(address.scopePath(), address.nodeId()));
        }
        return total;
    }

    private int pendingWorldOutputsLocked() {
        long total = pendingLatestOutputs.size();
        for (DisplayCommandBuffer buffer : pendingDisplayCommands.values()) {
            total += buffer.size();
            if (total >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        }
        return (int) total;
    }

    private void resetClockStateLocked(long now) {
        lastClockNanos = now;
        lastClockExecutedEdges = 0L;
        lastClockPendingEdges = 0L;
        lastClockWallNanos = 0L;
        lastClockTargetHz = 0L;
        lastClockActualHz = 0L;
        benchmarkStartNanos = now;
        benchmarkEdges = 0L;
        benchmarkCpuNanos = 0L;
    }

    /** Capture changed external outputs without creating one Minecraft event per simulated edge. */
    private void captureOutputChangesLocked() {
        CircuitProgramRuntime current = runtime;
        if (current == null) return;
        Set<String> streamPorts = displayStreamPorts;

        for (PortSpec port : current.outputPorts()) {
            long value;
            try {
                value = current.outputValue(port.name());
            } catch (RuntimeException ignored) {
                continue;
            }

            Long previous = lastCapturedOutputs.put(port.name(), value);
            if (previous != null && previous.longValue() == value) continue;

            // Ordinary world-facing electrical state is level-triggered: newest value wins.
            pendingLatestOutputs.put(port.name(), new OutputEvent(port.name(), value));

            // A physical display is a framebuffer, not a million-entry Minecraft event queue.
            if (port.width() == DisplayBlockEntity.DISPLAY_BUS_WIDTH && streamPorts.contains(port.name())) {
                DisplayCommandCodec.Command command = DisplayCommandCodec.decode(value);
                if (command.isPixel() || command.isClear()) {
                    pendingDisplayCommands
                            .computeIfAbsent(port.name(), ignored -> new DisplayCommandBuffer())
                            .record(command);
                }
            }
        }
    }

    private static long saturatingAdd(long a, long b) {
        if (b > 0L && a > Long.MAX_VALUE - b) return Long.MAX_VALUE;
        return a + b;
    }

    public void acceptExternalInput(String portName, long value) {
        synchronized (runtimeLock) {
            if (runtime == null) return;
            try {
                runtime.driveInput(portName, value);
                runtimeError = "";
                captureOutputChangesLocked();
            } catch (RuntimeException error) {
                runtimeError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            }
        }
    }

    public long outputValue(String portName) {
        synchronized (runtimeLock) {
            return runtime == null ? 0L : runtime.outputValue(portName);
        }
    }

    public void publishSocket(CircuitPortBlockEntity socket) {
        if (socket == null || !socket.isBound()) return;
        long value;
        synchronized (runtimeLock) {
            if (runtime == null) return;
            try {
                value = runtime.outputValue(socket.portName());
            } catch (RuntimeException ignored) {
                return;
            }
        }
        publishSocketValue(socket, value);
    }

    private void publishSocketValue(CircuitPortBlockEntity socket, long value) {
        Level currentLevel = level;
        if (currentLevel == null || currentLevel.isClientSide()) return;
        if (!worldPosition.equals(socket.circuitPos()) || socket.direction() != PortDirection.OUTPUT) return;
        for (Direction direction : Direction.values()) {
            BlockPos cablePos = socket.getBlockPos().relative(direction);
            BlockState state = currentLevel.getBlockState(cablePos);
            if (state.getBlock() instanceof CableBlock cable && socket.accepts(cable)) {
                long normalized = cable.bitWidth() >= 64 ? value : value & ((1L << cable.bitWidth()) - 1L);
                CableRuntime.setValue(currentLevel, cablePos, normalized);
            }
        }
    }

    /** Publishes the current snapshot. The high-rate display history is separately framebuffer-coalesced. */
    public void publishOutputs() {
        List<OutputEvent> snapshot = new ArrayList<>();
        synchronized (runtimeLock) {
            if (runtime == null) return;
            for (PortSpec port : runtime.outputPorts()) {
                try {
                    snapshot.add(new OutputEvent(port.name(), runtime.outputValue(port.name())));
                } catch (RuntimeException ignored) {
                }
            }
        }
        for (OutputEvent event : snapshot) publishOutputValue(event);
    }

    private void publishOutputValue(OutputEvent event) {
        Level currentLevel = level;
        if (currentLevel == null || currentLevel.isClientSide()) return;

        for (BlockPos socketPos : CircuitPortLinks.sockets(currentLevel, worldPosition)) {
            if (!(currentLevel.getBlockEntity(socketPos) instanceof CircuitPortBlockEntity socket)) continue;
            if (!event.portName().equals(socket.portName())) continue;
            publishSocketValue(socket, event.value());
        }

        for (Direction direction : Direction.values()) {
            BlockPos cablePos = worldPosition.relative(direction);
            BlockState state = currentLevel.getBlockState(cablePos);
            if (!(state.getBlock() instanceof CableBlock cable)) continue;

            PortSpec port = DirectPortResolver.unique(this, cable.cableKind(), cable.bitWidth());
            if (port == null || port.direction() != PortDirection.OUTPUT || !event.portName().equals(port.name())) continue;
            long normalized = cable.bitWidth() >= 64
                    ? event.value()
                    : event.value() & ((1L << cable.bitWidth()) - 1L);
            CableRuntime.setValue(currentLevel, cablePos, normalized);
        }
    }

    /** Determine which 64-bit output ports currently feed a physical Pixel Display network. Server thread only. */
    private void refreshDisplayStreamPorts() {
        Level currentLevel = level;
        if (currentLevel == null || currentLevel.isClientSide()) return;

        Set<String> streams = new HashSet<>();

        for (BlockPos socketPos : CircuitPortLinks.sockets(currentLevel, worldPosition)) {
            if (!(currentLevel.getBlockEntity(socketPos) instanceof CircuitPortBlockEntity socket)) continue;
            if (socket.direction() != PortDirection.OUTPUT || socket.width() != DisplayBlockEntity.DISPLAY_BUS_WIDTH) continue;

            for (Direction direction : Direction.values()) {
                BlockPos cablePos = socketPos.relative(direction);
                BlockState state = currentLevel.getBlockState(cablePos);
                if (!(state.getBlock() instanceof CableBlock cable)) continue;
                if (cable.cableKind() != CableKind.BUS || cable.bitWidth() != DisplayBlockEntity.DISPLAY_BUS_WIDTH) continue;
                if (!socket.accepts(cable)) continue;
                if (networkTouchesDisplay(currentLevel, cablePos)) streams.add(socket.portName());
            }
        }

        for (Direction direction : Direction.values()) {
            BlockPos cablePos = worldPosition.relative(direction);
            BlockState state = currentLevel.getBlockState(cablePos);
            if (!(state.getBlock() instanceof CableBlock cable)) continue;
            if (cable.cableKind() != CableKind.BUS || cable.bitWidth() != DisplayBlockEntity.DISPLAY_BUS_WIDTH) continue;

            PortSpec port = DirectPortResolver.unique(this, cable.cableKind(), cable.bitWidth());
            if (port == null || port.direction() != PortDirection.OUTPUT) continue;
            if (networkTouchesDisplay(currentLevel, cablePos)) streams.add(port.name());
        }

        Set<String> immutable = Set.copyOf(streams);
        displayStreamPorts = immutable;
        synchronized (runtimeLock) {
            pendingDisplayCommands.keySet().removeIf(name -> !immutable.contains(name));
        }
    }

    private static boolean networkTouchesDisplay(Level level, BlockPos cablePos) {
        CableNetworkCache.Network network = CableNetworkCache.network(level, cablePos);
        if (network == null) return false;
        for (CableNetworkCache.Endpoint endpoint : network.endpoints()) {
            if (endpoint.kind() == CableNetworkCache.EndpointKind.DISPLAY) return true;
        }
        return false;
    }

    /**
     * Minecraft world I/O is sampled from the independent simulation once per normal server tick.
     * During /tick freeze this method stops, while the worker continues and coalesces final display state safely.
     */
    private void flushPendingOutputsOnServerThread() {
        Level currentLevel = level;
        if (currentLevel == null || currentLevel.isClientSide() || isRemoved()) return;

        long started = System.nanoTime();
        int processed = 0;
        while (processed < WORLD_FLUSH_BATCH_EVENTS) {
            OutputEvent event;
            synchronized (runtimeLock) {
                event = pollPendingWorldOutputLocked();
            }
            if (event == null) break;

            publishOutputValue(event);
            processed++;
            if (System.nanoTime() - started >= WORLD_FLUSH_WALL_BUDGET_NANOS) break;
        }
    }

    private OutputEvent pollPendingWorldOutputLocked() {
        Iterator<Map.Entry<String, DisplayCommandBuffer>> streamIterator = pendingDisplayCommands.entrySet().iterator();
        while (streamIterator.hasNext()) {
            Map.Entry<String, DisplayCommandBuffer> entry = streamIterator.next();
            OutputEvent event = entry.getValue().poll(entry.getKey());
            if (entry.getValue().isEmpty()) streamIterator.remove();
            if (event != null) return event;
        }

        Iterator<Map.Entry<String, OutputEvent>> latestIterator = pendingLatestOutputs.entrySet().iterator();
        if (!latestIterator.hasNext()) return null;
        OutputEvent event = latestIterator.next().getValue();
        latestIterator.remove();
        return event;
    }

    @Override
    public void setRemoved() {
        CircuitSimulationWorker.unregister(this);
        super.setRemoved();
    }

    @Override
    public void clearRemoved() {
        // Do not register the clock worker here. clearRemoved() is called while chunks/spawn are still loading.
        super.clearRemoved();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        if (!boardJson.isBlank()) output.putString("board", boardJson);
        if (!programJson.isBlank()) output.putString("program", programJson);
        super.saveAdditional(output);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        boardJson = input.getStringOr("board", "");
        programJson = input.getStringOr("program", "");
        synchronized (runtimeLock) {
            runtime = null;
            runtimeError = "";
            pendingLatestOutputs.clear();
            pendingDisplayCommands.clear();
            lastCapturedOutputs.clear();
            displayStreamPorts = Set.of();
            resetClockStateLocked(0L);
            if (programJson.isBlank()) return;
            try {
                runtime = new CircuitProgramRuntime(CircuitProgram.fromJson(programJson));
                captureOutputChangesLocked();
            } catch (RuntimeException error) {
                runtimeError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            }
        }
    }

    private record OutputEvent(String portName, long value) {}

    /**
     * Bounded display-frame intent buffer. Pixel writes are coalesced by coordinate, so a MHz circuit cannot
     * create an unbounded Java object queue. CLEAR discards older pending pixels because only the final framebuffer
     * after that clear matters to the physical display.
     */
    private static final class DisplayCommandBuffer {
        private final LinkedHashMap<Long, Long> pixels = new LinkedHashMap<>();
        private boolean clearPending;
        private long clearRaw;

        private void record(DisplayCommandCodec.Command command) {
            if (command.isClear()) {
                clearPending = true;
                clearRaw = command.raw();
                pixels.clear();
                return;
            }
            if (!command.isPixel()) return;

            long key = ((long) command.x() << 32) | (command.y() & 0xFFFFFFFFL);
            if (!pixels.containsKey(key) && pixels.size() >= MAX_COALESCED_DISPLAY_PIXELS) {
                Iterator<Long> oldest = pixels.keySet().iterator();
                if (oldest.hasNext()) {
                    oldest.next();
                    oldest.remove();
                }
            }
            pixels.put(key, command.raw());
        }

        private OutputEvent poll(String portName) {
            if (clearPending) {
                clearPending = false;
                return new OutputEvent(portName, clearRaw);
            }
            Iterator<Map.Entry<Long, Long>> iterator = pixels.entrySet().iterator();
            if (!iterator.hasNext()) return null;
            long raw = iterator.next().getValue();
            iterator.remove();
            return new OutputEvent(portName, raw);
        }

        private int size() {
            return pixels.size() + (clearPending ? 1 : 0);
        }

        private boolean isEmpty() {
            return !clearPending && pixels.isEmpty();
        }
    }
}
