package com.foreverspark.logicsim.block;

import com.foreverspark.logicsim.editor.model.PortDirection;
import com.foreverspark.logicsim.editor.model.PortSpec;
import com.foreverspark.logicsim.interconnect.CableRuntime;
import com.foreverspark.logicsim.interconnect.CircuitPortCatalog;
import com.foreverspark.logicsim.interconnect.CircuitPortLinks;
import com.foreverspark.logicsim.interconnect.CircuitProgram;
import com.foreverspark.logicsim.interconnect.CircuitProgramRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public final class CircuitBlockEntity extends BlockEntity {
    private static final int MAX_PROGRAM_JSON = 2_000_000;
    private static final long CLOCK_EDGE_BUDGET_PER_TICK = 5_000L;

    private String programJson = "";
    private CircuitProgramRuntime runtime;
    private String runtimeError = "";
    private long lastClockNanos;

    public CircuitBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CIRCUIT, pos, state);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, CircuitBlockEntity circuit) {
        if (level.isClientSide()) return;
        long now = System.nanoTime();
        if (circuit.lastClockNanos == 0L) {
            circuit.lastClockNanos = now;
            return;
        }
        long elapsed = Math.max(0L, now - circuit.lastClockNanos);
        circuit.lastClockNanos = now;
        if (circuit.runtime == null || elapsed == 0L) return;
        try {
            circuit.runtime.advanceClocksNanos(elapsed, CLOCK_EDGE_BUDGET_PER_TICK, circuit::publishOutputs);
            circuit.runtimeError = "";
        } catch (RuntimeException error) {
            circuit.runtimeError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        }
    }

    public boolean isProgrammed() { return runtime != null; }
    public String runtimeError() { return runtimeError; }
    public String programName() { return runtime == null ? "" : runtime.program().root.name; }

    public CircuitPortCatalog portCatalog() {
        return runtime == null
                ? new CircuitPortCatalog("", java.util.List.of(), java.util.List.of())
                : new CircuitPortCatalog(programName(), runtime.inputPorts(), runtime.outputPorts());
    }

    public PortSpec portSpec(String name, PortDirection direction) {
        return runtime == null ? null : runtime.port(name, direction);
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
        setChanged();
        publishOutputs();
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
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        if (!programJson.isBlank()) output.putString("program", programJson);
        super.saveAdditional(output);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        programJson = input.getStringOr("program", "");
        runtime = null;
        runtimeError = "";
        lastClockNanos = 0L;
        if (programJson.isBlank()) return;
        try {
            runtime = new CircuitProgramRuntime(CircuitProgram.fromJson(programJson));
        } catch (RuntimeException error) {
            runtimeError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        }
    }
}
