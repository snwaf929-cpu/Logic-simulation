package com.foreverspark.logicsim.block;

import com.foreverspark.logicsim.LogicSimulationMod;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.PortDirection;
import com.foreverspark.logicsim.editor.model.PortSpec;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CircuitBlockEntity extends BlockEntity {
    public static final int MAX_BOARD_JSON = 2_000_000;
    private static final int MAX_PROGRAM_JSON = 2_000_000;
    private static final Gson BOARD_GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Worker budgets are wall-clock fairness limits, NOT Minecraft-tick limits. */
    private static final long WORKER_SLICE_WALL_BUDGET_NANOS = 1_500_000L;
    private static final long WORKER_HARD_EDGE_LIMIT_PER_SLICE = 500_000L;
    private static final long WORKER_CHUNK_EDGES_PER_CLOCK = 1_024L;
    private static final int MAX_PENDING_OUTPUT_EVENTS = 2_000_000;
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

    /** Preserve physical output transitions generated between Minecraft/server-world updates. */
    private final ArrayDeque<OutputEvent> pendingOutputEvents = new ArrayDeque<>();
    private final Map<String, Long> lastCapturedOutputs = new HashMap<>();
    private final AtomicBoolean worldFlushScheduled = new AtomicBoolean();
    private volatile MinecraftServer asyncServer;

    /** Independent-worker throughput accounting. */
    private long benchmarkStartNanos;
    private long benchmarkEdges;
    private long benchmarkCpuNanos;

    public CircuitBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CIRCUIT, pos, state);
    }

    /**
     * Minecraft's block-entity ticker now performs lifecycle/world-I/O duties only.
     * It does NOT advance simulated clocks.
     */
    public static void tick(Level level, BlockPos pos, BlockState state, CircuitBlockEntity circuit) {
        if (level.isClientSide()) return;
        circuit.bindServer(level);
        if (circuit.isProgrammed()) CircuitSimulationWorker.register(circuit);
        circuit.requestWorldFlush();
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
            pendingOutputEvents.clear();
            lastCapturedOutputs.clear();
            captureOutputChangesLocked();
        }
        bindServer(level);
        if (level != null && !level.isClientSide()) CircuitSimulationWorker.register(this);
        setChanged();
        requestWorldFlush();
    }

    /** Called only from CircuitSimulationWorker's dedicated daemon thread. */
    boolean runClockWorkerSlice(long now) {
        boolean needsWorldFlush;
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
                needsWorldFlush = !pendingOutputEvents.isEmpty();
            } else {
                if (lastClockNanos == 0L) resetClockStateLocked(now);
                long elapsed = Math.max(0L, now - lastClockNanos);
                lastClockNanos = now;

                // First convert real elapsed wall time into pending virtual clock edges.
                if (elapsed > 0L) current.advanceClocksNanos(elapsed, 0L, this::captureOutputChangesLocked);

                long started = System.nanoTime();
                long emittedTotal = 0L;
                while (emittedTotal < WORKER_HARD_EDGE_LIMIT_PER_SLICE
                        && pendingOutputEvents.size() < MAX_PENDING_OUTPUT_EVENTS) {
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
                needsWorldFlush = !pendingOutputEvents.isEmpty();
            }
        }
        if (needsWorldFlush) requestWorldFlush();
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
                pendingOutputEvents.size(),
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

    /** Capture every changed external output after a settled simulated edge. */
    private void captureOutputChangesLocked() {
        CircuitProgramRuntime current = runtime;
        if (current == null) return;
        for (PortSpec port : current.outputPorts()) {
            long value;
            try {
                value = current.outputValue(port.name());
            } catch (RuntimeException ignored) {
                continue;
            }
            Long previous = lastCapturedOutputs.put(port.name(), value);
            if (previous == null || previous.longValue() != value) {
                pendingOutputEvents.addLast(new OutputEvent(port.name(), value));
            }
        }
    }

    private static long saturatingAdd(long a, long b) {
        if (b > 0L && a > Long.MAX_VALUE - b) return Long.MAX_VALUE;
        return a + b;
    }

    public void acceptExternalInput(String portName, long value) {
        boolean changed = false;
        synchronized (runtimeLock) {
            if (runtime == null) return;
            try {
                runtime.driveInput(portName, value);
                runtimeError = "";
                int before = pendingOutputEvents.size();
                captureOutputChangesLocked();
                changed = pendingOutputEvents.size() != before;
            } catch (RuntimeException error) {
                runtimeError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            }
        }
        if (changed) requestWorldFlush();
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

    /** Publishes the current snapshot; transition history is handled by the independent output queue. */
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

    private void bindServer(Level candidate) {
        if (candidate instanceof ServerLevel serverLevel) asyncServer = serverLevel.getServer();
    }

    /**
     * Safe bridge from the high-rate simulation worker to Minecraft's world thread.
     * One queued server task drains thousands of output transitions at once.
     */
    private void requestWorldFlush() {
        MinecraftServer server = asyncServer;
        if (server == null || pendingOutputEvents.isEmpty()) return;
        if (!worldFlushScheduled.compareAndSet(false, true)) return;
        server.execute(this::flushQueuedOutputsOnServerThread);
    }

    private void flushQueuedOutputsOnServerThread() {
        worldFlushScheduled.set(false);
        Level currentLevel = level;
        if (currentLevel == null || currentLevel.isClientSide() || isRemoved()) return;

        long started = System.nanoTime();
        int processed = 0;
        while (processed < WORLD_FLUSH_BATCH_EVENTS) {
            OutputEvent event;
            synchronized (runtimeLock) {
                event = pendingOutputEvents.pollFirst();
            }
            if (event == null) break;
            publishOutputValue(event);
            processed++;
            if (System.nanoTime() - started >= WORLD_FLUSH_WALL_BUDGET_NANOS) break;
        }

        synchronized (runtimeLock) {
            if (!pendingOutputEvents.isEmpty()) requestWorldFlush();
        }
    }

    @Override
    public void setRemoved() {
        CircuitSimulationWorker.unregister(this);
        super.setRemoved();
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        bindServer(level);
        if (level != null && !level.isClientSide() && runtime != null) CircuitSimulationWorker.register(this);
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
            pendingOutputEvents.clear();
            lastCapturedOutputs.clear();
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
}
