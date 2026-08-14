package com.foreverspark.logicsim.block;

import com.foreverspark.logicsim.LogicSimulationMod;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

public final class ProgrammableCircuitBlock extends BaseEntityBlock {
    private static final long DIAGNOSTIC_WINDOW_NANOS = 1_000_000_000L;
    private static final Map<CircuitBlockEntity, ClockDiagnostics> CLOCK_DIAGNOSTICS = new WeakHashMap<>();

    public ProgrammableCircuitBlock(Properties properties) { super(properties); }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return simpleCodec(ProgrammableCircuitBlock::new); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new CircuitBlockEntity(pos, state); }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, ModBlockEntities.CIRCUIT, ProgrammableCircuitBlock::tickCircuit);
    }

    private static void tickCircuit(Level level, BlockPos pos, BlockState state, CircuitBlockEntity circuit) {
        CircuitBlockEntity.tick(level, pos, state, circuit);
        if (level.isClientSide() || !circuit.isProgrammed()) return;

        long now = System.nanoTime();
        ClockDiagnostics diagnostics = CLOCK_DIAGNOSTICS.computeIfAbsent(circuit, ignored -> new ClockDiagnostics(now));
        diagnostics.record(now, pos, circuit);
    }

    private static final class ClockDiagnostics {
        private long windowStartNanos;
        private long executedEdges;
        private long simulatorWallNanos;
        private long minecraftTickCalls;

        private ClockDiagnostics(long windowStartNanos) {
            this.windowStartNanos = windowStartNanos;
        }

        private void record(long now, BlockPos pos, CircuitBlockEntity circuit) {
            executedEdges = saturatingAdd(executedEdges, circuit.lastClockExecutedEdges());
            simulatorWallNanos = saturatingAdd(simulatorWallNanos, circuit.lastClockWallNanos());
            minecraftTickCalls++;

            long windowNanos = Math.max(0L, now - windowStartNanos);
            if (windowNanos < DIAGNOSTIC_WINDOW_NANOS) return;

            double seconds = windowNanos / 1_000_000_000.0;
            long actualEdgesPerSecond = seconds <= 0.0 ? 0L : Math.round(executedEdges / seconds);
            long actualCyclesPerSecond = actualEdgesPerSecond / 2L;
            double simulatorCpuMs = simulatorWallNanos / 1_000_000.0;

            LogicSimulationMod.LOGGER.info(
                    "[CLOCK BENCH/world] pos={} actualHz={} edgesPerSec={} pendingEdges={} simCpuMs={} minecraftTickCalls={}",
                    pos,
                    actualCyclesPerSecond,
                    actualEdgesPerSecond,
                    circuit.lastClockPendingEdges(),
                    String.format(java.util.Locale.ROOT, "%.3f", simulatorCpuMs),
                    minecraftTickCalls
            );

            windowStartNanos = now;
            executedEdges = 0L;
            simulatorWallNanos = 0L;
            minecraftTickCalls = 0L;
        }

        private static long saturatingAdd(long a, long b) {
            if (b > 0L && a > Long.MAX_VALUE - b) return Long.MAX_VALUE;
            return a + b;
        }
    }
}
