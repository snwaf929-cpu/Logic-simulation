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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CircuitBlockEntity extends BlockEntity {
    public static final int MAX_BOARD_JSON = 2_000_000;
    private static final int MAX_PROGRAM_JSON = 2_000_000;
    private static final Gson BOARD_GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Worker budgets are wall-clock fairness limits, NOT Minecraft-tick limits. */
    private static final long WORKER_SLICE_WALL_BUDGET_NANOS = 2_500_000L;
    private static final long WORKER_HARD_EDGE_LIMIT_PER_SLICE = 1_000_000L;
    private static final long WORKER_CHUNK_EDGES_PER_CLOCK = 4_096L;
    /** Dense framebuffer intent comfortably covers 1080p/1440p without HashMap allocation on every simulated edge. */
    private static final int MAX_DENSE_DISPLAY_PIXELS = 4_194_304;
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
     * Minecraft-facing outputs are sampled, not replayed edge-by-edge. Ordinary electrical outputs keep only the
     * latest value. Display DATA64 streams keep final framebuffer intent in a dense primitive buffer and are applied
     * to the physical display as one server-tick batch.
     */
    private final Map<String, MutableOutput> pendingLatestOutputs = new LinkedHashMap<>();
    private final Map<String, DisplayCommandBuffer> pendingDisplayCommands = new HashMap<>();
    private final Map<String, MutableOutput> lastCapturedOutputs = new HashMap<>();
    private volatile Map<String, DisplayStreamTarget> displayStreamTargets = Map.of();

    /** Independent-worker throughput accounting. */
    private long benchmarkStartNanos;
    private long benchmarkEdges;
    private long benchmarkCpuNanos;
    private long benchmarkFilteredDisplayCommands;

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
        circuit.refreshDisplayStreamPorts();
        CircuitSimulationWorker.register(circuit);
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
        // Discover the physical display before starting a freshly programmed high-rate stream.
        if (level != null && !level.isClientSide()) {
            refreshDisplayStreamPorts();
            CircuitSimulationWorker.register(this);
        }
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
                "[CLOCK BENCH/world] pos={} targetHz={} actualHz={} edgesPerSec={} pendingEdges={} outputQueue={} filteredDisplay={} workerCpuMs={} minecraftTickIndependent=true",
                worldPosition,
                targetHz,
                actualCyclesPerSecond,
                actualEdgesPerSecond,
                pending,
                pendingWorldOutputsLocked(),
                benchmarkFilteredDisplayCommands,
                String.format(java.util.Locale.ROOT, "%.3f", workerCpuMs)
        );

        benchmarkStartNanos = now;
        benchmarkEdges = 0L;
        benchmarkCpuNanos = 0L;
        benchmarkFilteredDisplayCommands = 0L;
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
        benchmarkFilteredDisplayCommands = 0L;
    }

    /** Capture changed external outputs without creating one Java/Minecraft event per simulated edge. */
    private void captureOutputChangesLocked() {
        CircuitProgramRuntime current = runtime;
        if (current == null) return;
        Map<String, DisplayStreamTarget> streamTargets = displayStreamTargets;

        for (PortSpec port : current.outputPorts()) {
            long value;
            try {
                value = current.outputValue(port.name());
            } catch (RuntimeException ignored) {
                continue;
            }

            MutableOutput captured = lastCapturedOutputs.computeIfAbsent(port.name(), ignored -> new MutableOutput());
            if (captured.initialized && captured.value == value) continue;
            captured.initialized = true;
            captured.value = value;

            DisplayStreamTarget target = port.width() == DisplayBlockEntity.DISPLAY_BUS_WIDTH
                    ? streamTargets.get(port.name())
                    : null;
            if (target != null) {
                DisplayCommandCodec.Command command = DisplayCommandCodec.decode(value);
                if (command.isClear()) {
                    pendingDisplayCommands
                            .computeIfAbsent(port.name(), ignored -> new DisplayCommandBuffer())
                            .record(command, target);
                } else if (command.isPixel()) {
                    if (target.contains(command.x(), command.y())) {
                        pendingDisplayCommands
                                .computeIfAbsent(port.name(), ignored -> new DisplayCommandBuffer())
                                .record(command, target);
                    } else {
                        benchmarkFilteredDisplayCommands = saturatingAdd(benchmarkFilteredDisplayCommands, 1L);
                    }
                }
                // The display stream's cable level is sampled once per server tick below; do not allocate an
                // ordinary world-output event for every DATA64 transition.
                continue;
            }

            pendingLatestOutputs.computeIfAbsent(port.name(), ignored -> new MutableOutput()).set(value);
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

    /** Publishes the current snapshot. High-rate display history is separately framebuffer-coalesced. */
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

    /** Determine the physical Pixel Display target and bounds for each connected 64-bit output. Server thread only. */
    private void refreshDisplayStreamPorts() {
        Level currentLevel = level;
        if (currentLevel == null || currentLevel.isClientSide()) return;

        Map<String, DisplayStreamTarget> targets = new HashMap<>();

        for (BlockPos socketPos : CircuitPortLinks.sockets(currentLevel, worldPosition)) {
            if (!(currentLevel.getBlockEntity(socketPos) instanceof CircuitPortBlockEntity socket)) continue;
            if (socket.direction() != PortDirection.OUTPUT || socket.width() != DisplayBlockEntity.DISPLAY_BUS_WIDTH) continue;

            for (Direction direction : Direction.values()) {
                BlockPos cablePos = socketPos.relative(direction);
                BlockState state = currentLevel.getBlockState(cablePos);
                if (!(state.getBlock() instanceof CableBlock cable)) continue;
                if (cable.cableKind() != CableKind.BUS || cable.bitWidth() != DisplayBlockEntity.DISPLAY_BUS_WIDTH) continue;
                if (!socket.accepts(cable)) continue;
                DisplayStreamTarget target = displayTarget(currentLevel, cablePos);
                if (target != null) targets.putIfAbsent(socket.portName(), target);
            }
        }

        for (Direction direction : Direction.values()) {
            BlockPos cablePos = worldPosition.relative(direction);
            BlockState state = currentLevel.getBlockState(cablePos);
            if (!(state.getBlock() instanceof CableBlock cable)) continue;
            if (cable.cableKind() != CableKind.BUS || cable.bitWidth() != DisplayBlockEntity.DISPLAY_BUS_WIDTH) continue;

            PortSpec port = DirectPortResolver.unique(this, cable.cableKind(), cable.bitWidth());
            if (port == null || port.direction() != PortDirection.OUTPUT) continue;
            DisplayStreamTarget target = displayTarget(currentLevel, cablePos);
            if (target != null) targets.putIfAbsent(port.name(), target);
        }

        Map<String, DisplayStreamTarget> immutable = Map.copyOf(targets);
        displayStreamTargets = immutable;
        synchronized (runtimeLock) {
            pendingDisplayCommands.keySet().removeIf(name -> !immutable.containsKey(name));
        }
    }

    private static DisplayStreamTarget displayTarget(Level level, BlockPos cablePos) {
        CableNetworkCache.Network network = CableNetworkCache.network(level, cablePos);
        if (network == null) return null;
        for (CableNetworkCache.Endpoint endpoint : network.endpoints()) {
            if (endpoint.kind() != CableNetworkCache.EndpointKind.DISPLAY) continue;
            BlockState displayState = level.getBlockState(endpoint.devicePos());
            if (!(displayState.getBlock() instanceof DisplayBlock)) continue;
            DisplayBlockEntity.WallInfo info = DisplayBlockEntity.wallInfo(level, endpoint.devicePos(), displayState);
            if (info == null || info.pixelWidth() <= 0 || info.pixelHeight() <= 0) continue;
            return new DisplayStreamTarget(endpoint.devicePos().immutable(), info.pixelWidth(), info.pixelHeight());
        }
        return null;
    }

    /**
     * Minecraft world I/O is sampled once per normal server tick. Valid display commands are applied as a single
     * framebuffer batch, so thousands of simulated pixel writes can become visible together on the next client
     * display sync instead of leaking out one cable event at a time.
     */
    private void flushPendingOutputsOnServerThread() {
        Level currentLevel = level;
        if (currentLevel == null || currentLevel.isClientSide() || isRemoved()) return;

        List<DisplayBatch> displayBatches = new ArrayList<>();
        List<OutputEvent> latestOutputs = new ArrayList<>();

        synchronized (runtimeLock) {
            Map<String, DisplayStreamTarget> targets = displayStreamTargets;
            for (Map.Entry<String, DisplayCommandBuffer> entry : pendingDisplayCommands.entrySet()) {
                DisplayStreamTarget target = targets.get(entry.getKey());
                if (target == null) continue;
                long[] commands = entry.getValue().drain();
                if (commands.length > 0) displayBatches.add(new DisplayBatch(target, commands));
            }
            pendingDisplayCommands.clear();

            for (Map.Entry<String, MutableOutput> entry : pendingLatestOutputs.entrySet()) {
                latestOutputs.add(new OutputEvent(entry.getKey(), entry.getValue().value));
            }
            pendingLatestOutputs.clear();

            // Display cables are level-triggered in the Minecraft world. Publish only the newest bus value once
            // per server tick; the complete pixel history has already been consumed by DisplayBatchRuntime.
            CircuitProgramRuntime current = runtime;
            if (current != null) {
                for (String portName : targets.keySet()) {
                    try {
                        latestOutputs.add(new OutputEvent(portName, current.outputValue(portName)));
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }

        for (DisplayBatch batch : displayBatches) {
            DisplayBatchRuntime.apply(currentLevel, batch.target().displayPos(), batch.commands());
        }
        for (OutputEvent event : latestOutputs) publishOutputValue(event);
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
            displayStreamTargets = Map.of();
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
    private record DisplayBatch(DisplayStreamTarget target, long[] commands) {}

    private record DisplayStreamTarget(BlockPos displayPos, int width, int height) {
        private boolean contains(int x, int y) {
            return x >= 0 && y >= 0 && x < width && y < height;
        }
    }

    /** Mutable holder avoids allocating a new OutputEvent/Long object on every high-rate output transition. */
    private static final class MutableOutput {
        private boolean initialized;
        private long value;

        private void set(long value) {
            this.initialized = true;
            this.value = value;
        }
    }

    /**
     * Final-frame display intent. Up to 1440p uses primitive dense arrays: no HashMap key/value allocation for
     * every simulated pixel command. Larger experimental walls fall back to a sparse map until the future GPU/
     * texture-backed display path replaces block-entity framebuffer transport entirely.
     */
    private static final class DisplayCommandBuffer {
        private int width;
        private int height;
        private boolean dense;
        private long[] denseRaw = new long[0];
        private int[] denseMarks = new int[0];
        private int[] dirtyIndices = new int[0];
        private int generation = 1;
        private int dirtyCount;
        private final LinkedHashMap<Long, Long> sparsePixels = new LinkedHashMap<>();
        private boolean clearPending;
        private long clearRaw;

        private void record(DisplayCommandCodec.Command command, DisplayStreamTarget target) {
            ensureGeometry(target.width(), target.height());
            if (command.isClear()) {
                clearPending = true;
                clearRaw = command.raw();
                resetPixels();
                return;
            }
            if (!command.isPixel() || !target.contains(command.x(), command.y())) return;

            if (dense) {
                int index = command.y() * width + command.x();
                denseRaw[index] = command.raw();
                if (denseMarks[index] != generation) {
                    denseMarks[index] = generation;
                    dirtyIndices[dirtyCount++] = index;
                }
                return;
            }

            long key = ((long) command.x() << 32) | (command.y() & 0xFFFFFFFFL);
            sparsePixels.put(key, command.raw());
        }

        private void ensureGeometry(int newWidth, int newHeight) {
            if (newWidth == width && newHeight == height) return;
            width = Math.max(1, newWidth);
            height = Math.max(1, newHeight);
            long area = (long) width * (long) height;
            dense = area <= MAX_DENSE_DISPLAY_PIXELS;
            if (dense) {
                int size = (int) area;
                denseRaw = new long[size];
                denseMarks = new int[size];
                dirtyIndices = new int[size];
                generation = 1;
            } else {
                denseRaw = new long[0];
                denseMarks = new int[0];
                dirtyIndices = new int[0];
            }
            dirtyCount = 0;
            sparsePixels.clear();
            clearPending = false;
        }

        private void resetPixels() {
            if (dense) {
                dirtyCount = 0;
                nextGeneration();
            } else {
                sparsePixels.clear();
            }
        }

        private void nextGeneration() {
            generation++;
            if (generation == 0) {
                Arrays.fill(denseMarks, 0);
                generation = 1;
            }
        }

        private long[] drain() {
            int pixelCount = dense ? dirtyCount : sparsePixels.size();
            int count = pixelCount + (clearPending ? 1 : 0);
            if (count == 0) return new long[0];

            long[] commands = new long[count];
            int out = 0;
            if (clearPending) {
                commands[out++] = clearRaw;
                clearPending = false;
            }

            if (dense) {
                for (int i = 0; i < dirtyCount; i++) commands[out++] = denseRaw[dirtyIndices[i]];
                dirtyCount = 0;
                nextGeneration();
            } else {
                for (long raw : sparsePixels.values()) commands[out++] = raw;
                sparsePixels.clear();
            }
            return commands;
        }

        private int size() {
            return (dense ? dirtyCount : sparsePixels.size()) + (clearPending ? 1 : 0);
        }
    }
}
