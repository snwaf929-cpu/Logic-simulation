package com.foreverspark.logicsim.block;

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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public final class CircuitBlockEntity extends BlockEntity {
    public static final int MAX_BOARD_JSON = 2_000_000;
    private static final int MAX_PROGRAM_JSON = 2_000_000;
    private static final Gson BOARD_GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final long CLOCK_WALL_BUDGET_NANOS = 2_000_000L;
    private static final long CLOCK_HARD_EDGE_LIMIT_PER_TICK = 500_000L;
    private static final long CLOCK_CHUNK_EDGES_PER_CLOCK = 256L;

    /** Editable CAD board stored independently from the compiled/running program. */
    private String boardJson = "";
    private String programJson = "";
    private CircuitProgramRuntime runtime;
    private String runtimeError = "";
    private long lastClockNanos;
    private long lastClockExecutedEdges;
    private long lastClockPendingEdges;
    private long lastClockWallNanos;

    public CircuitBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CIRCUIT, pos, state);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, CircuitBlockEntity circuit) {
        if (level.isClientSide()) return;
        long now = System.nanoTime();
        if (circuit.lastClockNanos == 0L) {
            circuit.lastClockNanos = now;
            if (circuit.runtime != null) circuit.publishOutputs();
            return;
        }
        long elapsed = Math.max(0L, now - circuit.lastClockNanos);
        circuit.lastClockNanos = now;
        if (circuit.runtime == null) return;
        try {
            if (elapsed > 0L) circuit.advanceClocksAdaptive(elapsed);
            // Static/combinational outputs must physically drive adjacent cables too.  Do not rely on
            // a display or another device to lazily pull the Circuit Block value from the cable cache.
            circuit.publishOutputs();
            circuit.runtimeError = "";
        } catch (RuntimeException error) {
            circuit.runtimeError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        }
    }

    public boolean isProgrammed() { return runtime != null; }
    public String runtimeError() { return runtimeError; }
    public String programName() { return runtime == null ? "" : runtime.program().root.name; }
    public String boardJson() { return boardJson; }
    public long lastClockExecutedEdges() { return lastClockExecutedEdges; }
    public long lastClockPendingEdges() { return lastClockPendingEdges; }
    public long lastClockWallNanos() { return lastClockWallNanos; }

    public CircuitPortCatalog portCatalog() {
        return runtime == null
                ? new CircuitPortCatalog("", java.util.List.of(), java.util.List.of())
                : new CircuitPortCatalog(programName(), runtime.inputPorts(), runtime.outputPorts());
    }

    public PortSpec portSpec(String name, PortDirection direction) {
        return runtime == null ? null : runtime.port(name, direction);
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
        this.programJson = parsed.toJson();
        this.runtime = compiled;
        this.runtimeError = "";
        this.lastClockNanos = System.nanoTime();
        this.lastClockExecutedEdges = 0L;
        this.lastClockPendingEdges = 0L;
        this.lastClockWallNanos = 0L;
        setChanged();
        publishOutputs();
    }

    private void advanceClocksAdaptive(long elapsedNanos) {
        runtime.advanceClocksNanos(elapsedNanos, 0L, this::publishOutputs);

        int clockCount = runtime.timing().clocks().size();
        if (clockCount == 0) {
            lastClockExecutedEdges = 0L;
            lastClockPendingEdges = 0L;
            lastClockWallNanos = 0L;
            return;
        }

        long started = System.nanoTime();
        long emittedTotal = 0L;
        while (emittedTotal < CLOCK_HARD_EDGE_LIMIT_PER_TICK) {
            long remaining = CLOCK_HARD_EDGE_LIMIT_PER_TICK - emittedTotal;
            long fairRemainingPerClock = Math.max(1L, remaining / clockCount);
            long chunkPerClock = Math.min(CLOCK_CHUNK_EDGES_PER_CLOCK, fairRemainingPerClock);
            long emitted = runtime.advanceClocksNanos(0L, chunkPerClock, this::publishOutputs);
            if (emitted <= 0L) break;
            emittedTotal = saturatingAdd(emittedTotal, emitted);
            if (System.nanoTime() - started >= CLOCK_WALL_BUDGET_NANOS) break;
        }

        lastClockExecutedEdges = emittedTotal;
        lastClockPendingEdges = pendingClockEdges();
        lastClockWallNanos = Math.max(0L, System.nanoTime() - started);
    }

    private long pendingClockEdges() {
        long total = 0L;
        for (var address : runtime.timing().clocks()) {
            total = saturatingAdd(total, runtime.timing().pendingEdges(address.scopePath(), address.nodeId()));
        }
        return total;
    }

    private static long saturatingAdd(long a, long b) {
        if (b > 0L && a > Long.MAX_VALUE - b) return Long.MAX_VALUE;
        return a + b;
    }

    public void acceptExternalInput(String portName, long value) {
        if (runtime == null) return;
        try {
            runtime.driveInput(portName, value);
            runtimeError = "";
            publishOutputs();
        } catch (RuntimeException error) {
            runtimeError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        }
    }

    public long outputValue(String portName) {
        if (runtime == null) return 0L;
        return runtime.outputValue(portName);
    }

    public void publishSocket(CircuitPortBlockEntity socket) {
        if (runtime == null || level == null || socket == null || !socket.isBound()) return;
        if (!worldPosition.equals(socket.circuitPos()) || socket.direction() != PortDirection.OUTPUT) return;
        long value;
        try {
            value = runtime.outputValue(socket.portName());
        } catch (RuntimeException ignored) {
            return;
        }
        for (Direction direction : Direction.values()) {
            BlockPos cablePos = socket.getBlockPos().relative(direction);
            BlockState state = level.getBlockState(cablePos);
            if (state.getBlock() instanceof CableBlock cable && socket.accepts(cable)) {
                long normalized = cable.bitWidth() >= 64 ? value : value & ((1L << cable.bitWidth()) - 1L);
                if (CableRuntime.value(level, cablePos) != normalized) CableRuntime.setValue(level, cablePos, normalized);
            }
        }
    }

    public void publishOutputs() {
        if (runtime == null || level == null) return;
        for (BlockPos socketPos : CircuitPortLinks.sockets(level, worldPosition)) {
            if (level.getBlockEntity(socketPos) instanceof CircuitPortBlockEntity socket) publishSocket(socket);
        }
        publishDirectOutputs();
    }

    /**
     * If the Circuit Block has exactly one compatible external port for an adjacent cable width, the
     * direct physical connection is real electrically as well as visually.  OUTPUT ports drive the cable
     * every server tick; INPUT ports continue to be driven from CableRuntime.notifyDevices().
     */
    private void publishDirectOutputs() {
        for (Direction direction : Direction.values()) {
            BlockPos cablePos = worldPosition.relative(direction);
            BlockState state = level.getBlockState(cablePos);
            if (!(state.getBlock() instanceof CableBlock cable)) continue;

            PortSpec port = DirectPortResolver.unique(this, cable.cableKind(), cable.bitWidth());
            if (port == null || port.direction() != PortDirection.OUTPUT) continue;

            long value;
            try {
                value = runtime.outputValue(port.name());
            } catch (RuntimeException ignored) {
                continue;
            }
            long normalized = cable.bitWidth() >= 64 ? value : value & ((1L << cable.bitWidth()) - 1L);
            CableRuntime.setValue(level, cablePos, normalized);
        }
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
        runtime = null;
        runtimeError = "";
        lastClockNanos = 0L;
        lastClockExecutedEdges = 0L;
        lastClockPendingEdges = 0L;
        lastClockWallNanos = 0L;
        if (programJson.isBlank()) return;
        try {
            runtime = new CircuitProgramRuntime(CircuitProgram.fromJson(programJson));
        } catch (RuntimeException error) {
            runtimeError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        }
    }
}
