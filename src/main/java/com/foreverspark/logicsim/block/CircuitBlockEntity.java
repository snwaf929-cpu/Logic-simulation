package com.foreverspark.logicsim.block;

import com.foreverspark.logicsim.LogicSimulationMod;
import com.foreverspark.logicsim.display.DisplayCommandCodec;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.ExternalDeviceDescriptor;
import com.foreverspark.logicsim.editor.model.ExternalDeviceType;
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
import com.foreverspark.logicsim.interconnect.ExternalDeviceDiscovery;
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
    /**
     * NBT String payloads are written with modified UTF and therefore have a 65,535-byte ceiling. A Java char can
     * occupy up to three bytes there, so 20k chars keeps every persisted chunk safely below the format limit even
     * when labels contain non-ASCII text.
     */
    private static final int NBT_STRING_CHUNK_CHARS = 20_000;
    private static final int MAX_NBT_STRING_CHUNKS = 256;

    /** Worker budgets are wall-clock fairness limits, NOT Minecraft-tick limits. */
    private static final long WORKER_SLICE_WALL_BUDGET_NANOS = 2_500_000L;
    private static final long WORKER_HARD_EDGE_LIMIT_PER_SLICE = 1_000_000L;
    private static final long WORKER_CHUNK_EDGES_PER_CLOCK = 4_096L;
    /** Dense framebuffer intent comfortably covers 1080p/1440p without HashMap allocation on every simulated edge. */
    private static final int MAX_DENSE_DISPLAY_PIXELS = 4_194_304;
    private static final long BENCHMARK_WINDOW_NANOS = 1_000_000_000L;
    /** Physical cable/device topology does not need a full network scan 20 times per second. */
    private static final int DISPLAY_DISCOVERY_INTERVAL_TICKS = 20;

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
     * Per-output arrays are compiled once when a program is installed. The MHz callback never allocates a map key,
     * iterator, decoded command record, or OutputEvent. Ordinary outputs keep only the latest state; display streams
     * retain final framebuffer intent in a primitive buffer.
     */
    private MutableOutput[] pendingLatestOutputs = new MutableOutput[0];
    private DisplayCommandBuffer[] pendingDisplayCommands = new DisplayCommandBuffer[0];
    private long[] lastCapturedOutputValues = new long[0];
    private boolean[] lastCapturedOutputInitialized = new boolean[0];
    private volatile DisplayStreamTarget[] displayStreamTargets = new DisplayStreamTarget[0];

    /**
     * V2.1A physical DEVICE bindings are a second boundary: the schematic owns user-friendly typed pins while the
     * world endpoint is resolved by stable id. Worker-thread input capture remains primitive; world I/O happens only
     * on the server thread. DISPLAY writes are framebuffer-coalesced exactly like the legacy DATA64 output path.
     */
    private DeviceInputBuffer[] pendingDeviceInputs = new DeviceInputBuffer[0];
    private DisplayCommandBuffer[] pendingDeviceDisplayCommands = new DisplayCommandBuffer[0];
    private boolean[] lastDeviceWriteHigh = new boolean[0];
    private boolean[] lastDeviceResetHigh = new boolean[0];
    private volatile ExternalDeviceTarget[] externalDeviceTargets = new ExternalDeviceTarget[0];
    private int displayDiscoveryCountdown;

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

        // Cable/device geometry changes slowly relative to MHz logic. A 1-second fallback discovery avoids doing a
        // full network + display-wall scan on every Minecraft tick.
        if (circuit.displayDiscoveryCountdown <= 0) {
            circuit.refreshDisplayStreamPorts();
            circuit.refreshExternalDeviceTargets();
            circuit.displayDiscoveryCountdown = DISPLAY_DISCOVERY_INTERVAL_TICKS;
        } else {
            circuit.displayDiscoveryCountdown--;
        }

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
            resetOutputBuffersLocked(compiled);
            captureOutputChangesLocked();
        }
        // Resolve physical endpoints before starting a freshly programmed high-rate stream.
        if (level != null && !level.isClientSide()) {
            refreshDisplayStreamPorts();
            refreshExternalDeviceTargets();
            displayDiscoveryCountdown = DISPLAY_DISCOVERY_INTERVAL_TICKS;
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
                "[CLOCK BENCH/world] pos={} targetHz={} actualHz={} edgesPerSec={} pendingEdges={} outputQueue={} filteredDisplay={} workerCpuMs={} turbo={} minecraftTickIndependent=true",
                worldPosition,
                targetHz,
                actualCyclesPerSecond,
                actualEdgesPerSecond,
                pending,
                pendingWorldOutputsLocked(),
                benchmarkFilteredDisplayCommands,
                String.format(java.util.Locale.ROOT, "%.3f", workerCpuMs),
                runtime != null && runtime.compiled().simulator().turboMode()
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
        long total = 0L;
        for (MutableOutput output : pendingLatestOutputs) {
            if (output.initialized) total++;
        }
        for (DisplayCommandBuffer buffer : pendingDisplayCommands) {
            total += buffer.size();
            if (total >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        }
        for (DisplayCommandBuffer buffer : pendingDeviceDisplayCommands) {
            total += buffer.size();
            if (total >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        }
        for (DeviceInputBuffer buffer : pendingDeviceInputs) {
            total += buffer.pendingCount();
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

    private void resetOutputBuffersLocked(CircuitProgramRuntime current) {
        int count = current == null ? 0 : current.outputPortCount();
        pendingLatestOutputs = new MutableOutput[count];
        pendingDisplayCommands = new DisplayCommandBuffer[count];
        lastCapturedOutputValues = new long[count];
        lastCapturedOutputInitialized = new boolean[count];
        for (int index = 0; index < count; index++) {
            pendingLatestOutputs[index] = new MutableOutput();
            pendingDisplayCommands[index] = new DisplayCommandBuffer();
        }
        displayStreamTargets = new DisplayStreamTarget[count];

        int deviceCount = current == null ? 0 : current.externalDeviceCount();
        pendingDeviceInputs = new DeviceInputBuffer[deviceCount];
        pendingDeviceDisplayCommands = new DisplayCommandBuffer[deviceCount];
        lastDeviceWriteHigh = new boolean[deviceCount];
        lastDeviceResetHigh = new boolean[deviceCount];
        for (int deviceIndex = 0; deviceIndex < deviceCount; deviceIndex++) {
            pendingDeviceInputs[deviceIndex] = new DeviceInputBuffer(current.externalDeviceInputCount(deviceIndex));
            pendingDeviceDisplayCommands[deviceIndex] = new DisplayCommandBuffer();
        }
        externalDeviceTargets = new ExternalDeviceTarget[deviceCount];
    }

    /** Capture changed external outputs and explicitly bound DEVICE inputs without world access in the MHz hot path. */
    private void captureOutputChangesLocked() {
        CircuitProgramRuntime current = runtime;
        if (current == null) return;

        int count = current.outputPortCount();
        long dirtyMask = current.consumeDirtyOutputMask();
        boolean useDirtyMask = count <= 64;
        if (useDirtyMask && dirtyMask == 0L) return;
        DisplayStreamTarget[] streamTargets = displayStreamTargets;

        for (int index = 0; index < count; index++) {
            if (useDirtyMask && (dirtyMask & (1L << index)) == 0L) continue;

            long value;
            try {
                value = current.outputValue(index);
            } catch (RuntimeException ignored) {
                continue;
            }

            if (lastCapturedOutputInitialized[index] && lastCapturedOutputValues[index] == value) continue;
            lastCapturedOutputInitialized[index] = true;
            lastCapturedOutputValues[index] = value;

            PortSpec port = current.outputPort(index);
            DisplayStreamTarget target = index < streamTargets.length && port.width() == DisplayBlockEntity.DISPLAY_BUS_WIDTH
                    ? streamTargets[index]
                    : null;
            if (target != null) {
                // DATA64 raw decode: do not allocate DisplayCommandCodec.Command millions of times per second.
                int opcode = (int) ((value >>> 48) & 0xFFL);
                if (opcode == DisplayCommandCodec.OP_CLEAR) {
                    pendingDisplayCommands[index].recordClear(value, target);
                } else if (opcode == DisplayCommandCodec.OP_PIXEL) {
                    int x = (int) ((value >>> 16) & 0xFFFFL);
                    int y = (int) ((value >>> 32) & 0xFFFFL);
                    if (target.contains(x, y)) {
                        pendingDisplayCommands[index].recordPixel(value, x, y, target);
                    } else {
                        benchmarkFilteredDisplayCommands = saturatingAdd(benchmarkFilteredDisplayCommands, 1L);
                    }
                }
                // The display stream's cable level is sampled once per server tick below; do not allocate an
                // ordinary world-output event for every DATA64 transition.
                continue;
            }

            pendingLatestOutputs[index].set(value);
        }

        if (current.externalDeviceInputsDirty(dirtyMask)) captureExternalDeviceInputsLocked(current);
    }

    /**
     * DEVICE pins are compiled sink signals. DISPLAY strobes retain every simulated write edge; other peripheral
     * inputs are level-like today and coalesce to the newest value before server-thread host delivery.
     */
    private void captureExternalDeviceInputsLocked(CircuitProgramRuntime current) {
        ExternalDeviceTarget[] targets = externalDeviceTargets;
        int count = Math.min(current.externalDeviceCount(), pendingDeviceInputs.length);
        for (int deviceIndex = 0; deviceIndex < count; deviceIndex++) {
            ExternalDeviceType type = current.externalDeviceType(deviceIndex);
            ExternalDeviceTarget target = deviceIndex < targets.length ? targets[deviceIndex] : null;

            if (type == ExternalDeviceType.DISPLAY) {
                boolean resetHigh = readDeviceBit(current, deviceIndex, 4, lastDeviceResetHigh[deviceIndex]);
                boolean resetRising = resetHigh && !lastDeviceResetHigh[deviceIndex];
                lastDeviceResetHigh[deviceIndex] = resetHigh;

                boolean writeHigh = readDeviceBit(current, deviceIndex, 3, lastDeviceWriteHigh[deviceIndex]);
                boolean writeRising = writeHigh && !lastDeviceWriteHigh[deviceIndex];
                lastDeviceWriteHigh[deviceIndex] = writeHigh;

                DisplayStreamTarget displayTarget = target == null ? null : target.displayTarget();
                if (displayTarget == null) continue;
                if (resetRising) {
                    pendingDeviceDisplayCommands[deviceIndex].recordClear(DisplayCommandCodec.clear(), displayTarget);
                    continue;
                }
                if (!writeRising) continue;

                try {
                    int x = (int) (current.externalDeviceInputValue(deviceIndex, 0) & 0xFFFFL);
                    int y = (int) (current.externalDeviceInputValue(deviceIndex, 1) & 0xFFFFL);
                    int color = (int) (current.externalDeviceInputValue(deviceIndex, 2) & 0xFFFFL);
                    if (displayTarget.contains(x, y)) {
                        long raw = DisplayCommandCodec.pixel(x, y, color);
                        pendingDeviceDisplayCommands[deviceIndex].recordPixel(raw, x, y, displayTarget);
                    } else {
                        benchmarkFilteredDisplayCommands = saturatingAdd(benchmarkFilteredDisplayCommands, 1L);
                    }
                } catch (RuntimeException ignored) {
                    // A partially unwired DEVICE may contain UNKNOWN; no host write is emitted until its data is valid.
                }
                continue;
            }

            DeviceInputBuffer buffer = pendingDeviceInputs[deviceIndex];
            int ports = Math.min(current.externalDeviceInputCount(deviceIndex), buffer.portCount());
            for (int portIndex = 0; portIndex < ports; portIndex++) {
                try {
                    buffer.capture(portIndex, current.externalDeviceInputValue(deviceIndex, portIndex));
                } catch (RuntimeException ignored) {
                    // Host input is not updated from UNKNOWN/floating schematic data.
                }
            }
        }
    }

    private static boolean readDeviceBit(
            CircuitProgramRuntime current,
            int deviceIndex,
            int portIndex,
            boolean fallback
    ) {
        try {
            return (current.externalDeviceInputValue(deviceIndex, portIndex) & 1L) != 0L;
        } catch (RuntimeException ignored) {
            return fallback;
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
            for (int index = 0; index < runtime.outputPortCount(); index++) {
                try {
                    snapshot.add(new OutputEvent(runtime.outputPort(index).name(), runtime.outputValue(index)));
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

    /** Determine the physical Pixel Display target and bounds for each connected legacy 64-bit root output. */
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

        synchronized (runtimeLock) {
            CircuitProgramRuntime current = runtime;
            if (current == null) {
                displayStreamTargets = new DisplayStreamTarget[0];
                return;
            }

            DisplayStreamTarget[] resolved = new DisplayStreamTarget[current.outputPortCount()];
            for (int index = 0; index < resolved.length; index++) {
                PortSpec port = current.outputPort(index);
                DisplayStreamTarget target = port.width() == DisplayBlockEntity.DISPLAY_BUS_WIDTH
                        ? targets.get(port.name())
                        : null;
                resolved[index] = target;
                if (target == null) {
                    pendingDisplayCommands[index].discard();
                } else {
                    // Once this output is recognized as a command stream, it must not also leak as an ordinary
                    // electrical event captured before topology discovery completed.
                    pendingLatestOutputs[index].clear();
                }
            }
            displayStreamTargets = resolved;
        }
    }

    /** Resolve stable-id DEVICE references to endpoints actually reachable from this Circuit Block. Server thread only. */
    private void refreshExternalDeviceTargets() {
        Level currentLevel = level;
        if (currentLevel == null || currentLevel.isClientSide()) return;

        CircuitProgramRuntime snapshot;
        String[] ids;
        ExternalDeviceType[] types;
        synchronized (runtimeLock) {
            snapshot = runtime;
            if (snapshot == null) {
                externalDeviceTargets = new ExternalDeviceTarget[0];
                return;
            }
            int count = snapshot.externalDeviceCount();
            ids = new String[count];
            types = new ExternalDeviceType[count];
            for (int index = 0; index < count; index++) {
                ids[index] = snapshot.externalDeviceId(index);
                types[index] = snapshot.externalDeviceType(index);
            }
        }

        Map<String, ExternalDeviceDescriptor> discovered = new HashMap<>();
        for (ExternalDeviceDescriptor descriptor : ExternalDeviceDiscovery.discover(currentLevel, worldPosition)) {
            if (descriptor == null || descriptor.deviceId() == null) continue;
            discovered.putIfAbsent(descriptor.deviceId(), descriptor);
        }

        ExternalDeviceTarget[] resolved = new ExternalDeviceTarget[ids.length];
        for (int index = 0; index < ids.length; index++) {
            ExternalDeviceDescriptor descriptor = discovered.get(ids[index]);
            if (descriptor == null || descriptor.type() != types[index]) continue;
            BlockPos devicePos = new BlockPos(descriptor.x(), descriptor.y(), descriptor.z());

            if (types[index] == ExternalDeviceType.DISPLAY) {
                BlockState displayState = currentLevel.getBlockState(devicePos);
                if (!(displayState.getBlock() instanceof DisplayBlock)
                        || !(currentLevel.getBlockEntity(devicePos) instanceof DisplayBlockEntity)) continue;
                DisplayBlockEntity.WallInfo info = DisplayBlockEntity.wallInfo(currentLevel, devicePos, displayState);
                if (info == null || info.pixelWidth() <= 0 || info.pixelHeight() <= 0) continue;
                resolved[index] = new ExternalDeviceTarget(
                        ids[index], types[index], devicePos.immutable(),
                        new DisplayStreamTarget(devicePos.immutable(), info.pixelWidth(), info.pixelHeight())
                );
                continue;
            }

            if (!(currentLevel.getBlockEntity(devicePos) instanceof ExternalDeviceBlockEntity physical)) continue;
            if (!ids[index].equals(physical.stableId()) || physical.deviceType() != types[index]) continue;
            resolved[index] = new ExternalDeviceTarget(ids[index], types[index], devicePos.immutable(), null);
        }

        synchronized (runtimeLock) {
            if (runtime != snapshot || pendingDeviceInputs.length != resolved.length) return;
            ExternalDeviceTarget[] previous = externalDeviceTargets;
            for (int index = 0; index < resolved.length; index++) {
                ExternalDeviceTarget old = index < previous.length ? previous[index] : null;
                ExternalDeviceTarget next = resolved[index];
                if (next == null) {
                    pendingDeviceDisplayCommands[index].discard();
                } else if (!next.equals(old)) {
                    if (next.type() == ExternalDeviceType.DISPLAY) {
                        // Treat an asserted strobe as one fresh command when a display is first/re-connected.
                        lastDeviceWriteHigh[index] = false;
                        lastDeviceResetHigh[index] = false;
                    } else {
                        pendingDeviceInputs[index].forcePendingSnapshot();
                    }
                }
            }
            externalDeviceTargets = resolved;
            // Prime current pin levels immediately; do not wait for another clock transition after plugging a device.
            captureExternalDeviceInputsLocked(snapshot);
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
        List<DeviceInputEvent> deviceInputs = new ArrayList<>();

        synchronized (runtimeLock) {
            CircuitProgramRuntime current = runtime;
            if (current == null) return;
            DisplayStreamTarget[] targets = displayStreamTargets;
            int count = current.outputPortCount();

            for (int index = 0; index < count; index++) {
                DisplayStreamTarget target = index < targets.length ? targets[index] : null;
                if (target != null) {
                    long[] commands = pendingDisplayCommands[index].drain();
                    if (commands.length > 0) displayBatches.add(new DisplayBatch(target, commands));
                    // Display cables are level-triggered in the Minecraft world. Publish only the newest bus value
                    // once per server tick; complete pixel history is already in the framebuffer batch.
                    try {
                        latestOutputs.add(new OutputEvent(current.outputPort(index).name(), current.outputValue(index)));
                    } catch (RuntimeException ignored) {
                    }
                    pendingLatestOutputs[index].clear();
                    continue;
                }

                MutableOutput pending = pendingLatestOutputs[index];
                if (pending.initialized) {
                    latestOutputs.add(new OutputEvent(current.outputPort(index).name(), pending.value));
                    pending.clear();
                }
            }

            ExternalDeviceTarget[] deviceTargets = externalDeviceTargets;
            int devices = Math.min(current.externalDeviceCount(), pendingDeviceInputs.length);
            for (int deviceIndex = 0; deviceIndex < devices; deviceIndex++) {
                ExternalDeviceTarget target = deviceIndex < deviceTargets.length ? deviceTargets[deviceIndex] : null;
                if (target == null) continue;

                if (target.type() == ExternalDeviceType.DISPLAY && target.displayTarget() != null) {
                    long[] commands = pendingDeviceDisplayCommands[deviceIndex].drain();
                    if (commands.length > 0) displayBatches.add(new DisplayBatch(target.displayTarget(), commands));
                    continue;
                }

                DeviceInputBuffer pending = pendingDeviceInputs[deviceIndex];
                int ports = Math.min(current.externalDeviceInputCount(deviceIndex), pending.portCount());
                for (int portIndex = 0; portIndex < ports; portIndex++) {
                    if (!pending.pending(portIndex)) continue;
                    deviceInputs.add(new DeviceInputEvent(
                            target.devicePos(),
                            target.deviceId(),
                            target.type(),
                            current.externalDeviceInputPort(deviceIndex, portIndex).name(),
                            pending.take(portIndex)
                    ));
                }
            }
        }

        for (DisplayBatch batch : displayBatches) {
            DisplayBatchRuntime.apply(currentLevel, batch.target().displayPos(), batch.commands());
        }
        for (OutputEvent event : latestOutputs) publishOutputValue(event);
        for (DeviceInputEvent event : deviceInputs) {
            if (!(currentLevel.getBlockEntity(event.devicePos()) instanceof ExternalDeviceBlockEntity physical)) continue;
            if (!event.deviceId().equals(physical.stableId()) || event.type() != physical.deviceType()) continue;
            physical.acceptSchematicInput(event.portName(), event.value());
        }
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
        putChunkedString(output, "board", boardJson);
        putChunkedString(output, "program", programJson);
        super.saveAdditional(output);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        boardJson = getChunkedString(input, "board");
        programJson = getChunkedString(input, "program");
        synchronized (runtimeLock) {
            runtime = null;
            runtimeError = "";
            resetOutputBuffersLocked(null);
            resetClockStateLocked(0L);
            displayDiscoveryCountdown = 0;
            if (programJson.isBlank()) return;
            try {
                runtime = new CircuitProgramRuntime(CircuitProgram.fromJson(programJson));
                resetOutputBuffersLocked(runtime);
                captureOutputChangesLocked();
            } catch (RuntimeException error) {
                runtimeError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            }
        }
    }

    private static void putChunkedString(ValueOutput output, String key, String value) {
        if (value == null || value.isBlank()) return;
        if (value.length() <= NBT_STRING_CHUNK_CHARS) {
            output.putString(key, value);
            return;
        }

        int chunkCount = (value.length() + NBT_STRING_CHUNK_CHARS - 1) / NBT_STRING_CHUNK_CHARS;
        if (chunkCount > MAX_NBT_STRING_CHUNKS) {
            throw new IllegalStateException("Circuit persistence payload has too many NBT string chunks: " + chunkCount);
        }
        output.putString(key + "_chunk_count", Integer.toString(chunkCount));
        for (int index = 0; index < chunkCount; index++) {
            int start = index * NBT_STRING_CHUNK_CHARS;
            int end = Math.min(value.length(), start + NBT_STRING_CHUNK_CHARS);
            output.putString(key + "_chunk_" + index, value.substring(start, end));
        }
    }

    private static String getChunkedString(ValueInput input, String key) {
        String countText = input.getStringOr(key + "_chunk_count", "");
        if (countText.isBlank()) return input.getStringOr(key, "");

        final int chunkCount;
        try {
            chunkCount = Integer.parseInt(countText);
        } catch (NumberFormatException ignored) {
            return input.getStringOr(key, "");
        }
        if (chunkCount <= 0 || chunkCount > MAX_NBT_STRING_CHUNKS) return input.getStringOr(key, "");

        StringBuilder joined = new StringBuilder(Math.min(
                MAX_PROGRAM_JSON,
                chunkCount * NBT_STRING_CHUNK_CHARS
        ));
        for (int index = 0; index < chunkCount; index++) {
            String chunk = input.getStringOr(key + "_chunk_" + index, "");
            if (chunk.isEmpty()) return input.getStringOr(key, "");
            joined.append(chunk);
        }
        return joined.toString();
    }

    private record OutputEvent(String portName, long value) {}
    private record DisplayBatch(DisplayStreamTarget target, long[] commands) {}
    private record DeviceInputEvent(
            BlockPos devicePos,
            String deviceId,
            ExternalDeviceType type,
            String portName,
            long value
    ) {}

    private record DisplayStreamTarget(BlockPos displayPos, int width, int height) {
        private boolean contains(int x, int y) {
            return x >= 0 && y >= 0 && x < width && y < height;
        }
    }

    private record ExternalDeviceTarget(
            String deviceId,
            ExternalDeviceType type,
            BlockPos devicePos,
            DisplayStreamTarget displayTarget
    ) {}

    /** Mutable holder avoids allocating a new OutputEvent/Long object on every high-rate output transition. */
    private static final class MutableOutput {
        private boolean initialized;
        private long value;

        private void set(long value) {
            this.initialized = true;
            this.value = value;
        }

        private void clear() {
            initialized = false;
        }
    }

    /** Latest level-like inputs for UIB/INTERNET/STORAGE, delivered once per server tick. */
    private static final class DeviceInputBuffer {
        private final long[] lastValues;
        private final boolean[] lastInitialized;
        private final long[] pendingValues;
        private final boolean[] pending;

        private DeviceInputBuffer(int ports) {
            int size = Math.max(0, ports);
            lastValues = new long[size];
            lastInitialized = new boolean[size];
            pendingValues = new long[size];
            pending = new boolean[size];
        }

        private int portCount() { return pending.length; }

        private void capture(int port, long value) {
            if (port < 0 || port >= pending.length) return;
            if (lastInitialized[port] && lastValues[port] == value) return;
            lastInitialized[port] = true;
            lastValues[port] = value;
            pendingValues[port] = value;
            pending[port] = true;
        }

        private void forcePendingSnapshot() {
            for (int port = 0; port < pending.length; port++) {
                if (!lastInitialized[port]) continue;
                pendingValues[port] = lastValues[port];
                pending[port] = true;
            }
        }

        private boolean pending(int port) {
            return port >= 0 && port < pending.length && pending[port];
        }

        private long take(int port) {
            long value = pendingValues[port];
            pending[port] = false;
            return value;
        }

        private int pendingCount() {
            int count = 0;
            for (boolean value : pending) if (value) count++;
            return count;
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

        private void recordClear(long raw, DisplayStreamTarget target) {
            ensureGeometry(target.width(), target.height());
            clearPending = true;
            clearRaw = raw;
            resetPixels();
        }

        private void recordPixel(long raw, int x, int y, DisplayStreamTarget target) {
            ensureGeometry(target.width(), target.height());
            if (!target.contains(x, y)) return;

            if (dense) {
                int index = y * width + x;
                denseRaw[index] = raw;
                if (denseMarks[index] != generation) {
                    denseMarks[index] = generation;
                    dirtyIndices[dirtyCount++] = index;
                }
                return;
            }

            long key = ((long) x << 32) | (y & 0xFFFFFFFFL);
            sparsePixels.put(key, raw);
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

        private void discard() {
            resetPixels();
            clearPending = false;
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
