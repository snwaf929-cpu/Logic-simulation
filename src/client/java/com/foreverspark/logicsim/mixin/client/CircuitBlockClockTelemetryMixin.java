package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.block.CircuitBlockEntity;
import com.foreverspark.logicsim.client.screen.ClockRuntimeTelemetry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Publishes the same one-second world-worker sample used by [CLOCK BENCH/world] for editor hover diagnostics. */
@Mixin(value = CircuitBlockEntity.class, priority = 900)
public abstract class CircuitBlockClockTelemetryMixin {
    @Shadow private long benchmarkStartNanos;
    @Shadow private long benchmarkEdges;
    @Shadow private long benchmarkCpuNanos;
    @Shadow private long targetClockHzLocked() { throw new AssertionError(); }
    @Shadow private long pendingClockEdgesLocked() { throw new AssertionError(); }

    @Inject(method = "updateBenchmarkLocked", at = @At("HEAD"))
    private void logic$publishClockTelemetry(long now, CallbackInfo ci) {
        long start = benchmarkStartNanos;
        if (start == 0L) return;
        long windowNanos = Math.max(0L, now - start);
        if (windowNanos < 1_000_000_000L) return;

        double seconds = windowNanos / 1_000_000_000.0;
        long edgesPerSecond = seconds <= 0.0 ? 0L : Math.round(benchmarkEdges / seconds);
        long actualHz = edgesPerSecond / 2L;

        CircuitBlockEntity self = (CircuitBlockEntity)(Object)this;
        ClockRuntimeTelemetry.publish(
                self.getBlockPos(),
                targetClockHzLocked(),
                actualHz,
                pendingClockEdgesLocked(),
                benchmarkCpuNanos,
                windowNanos
        );
    }
}
