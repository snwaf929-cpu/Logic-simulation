package com.foreverspark.logicsim.client.screen;

import com.foreverspark.logicsim.LogicSimulationMod;
import com.foreverspark.logicsim.editor.runtime.CircuitTimingController;
import com.foreverspark.logicsim.editor.runtime.CompiledCircuit;
import com.foreverspark.logicsim.mixin.client.CanvasAccess;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class EditorClockRuntime {
    private static final long EDGE_BUDGET_PER_CLOCK_PER_FRAME = 5_000L;
    private static final long DIAGNOSTIC_WINDOW_NANOS = 1_000_000_000L;
    private static final Map<CircuitCanvasWidget, State> STATES = new WeakHashMap<>();

    static {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            for (CircuitCanvasWidget canvas : List.copyOf(STATES.keySet())) frame(canvas);
        });
    }

    private EditorClockRuntime() {}

    public static synchronized void attach(CircuitCanvasWidget canvas) { frame(canvas); }

    public static synchronized void frame(CircuitCanvasWidget canvas) {
        if (canvas == null) return;
        CanvasAccess access = (CanvasAccess)(Object)canvas;
        CompiledCircuit compiled = access.logic$getRuntime();
        if (compiled == null) {
            STATES.remove(canvas);
            return;
        }
        State state = STATES.get(canvas);
        if (state == null || state.compiled != compiled) {
            state = new State(compiled, new CircuitTimingController(compiled, access.logic$getRuntimeRootDocument(), access.logic$getChipLibrary()), System.nanoTime());
            STATES.put(canvas, state);
            return;
        }
        long now = System.nanoTime();
        long elapsed = Math.max(0L, now - state.lastNanos);
        state.lastNanos = now;
        if (elapsed == 0L || state.timing.clocks().isEmpty()) return;
        long emitted = state.timing.advanceNanos(elapsed, EDGE_BUDGET_PER_CLOCK_PER_FRAME);
        state.recordDiagnostics(now, emitted);
    }

    /** Process non-clock edge-triggered sources after a manual editor input changes. */
    public static synchronized int processRandomSources(CircuitCanvasWidget canvas) {
        if (canvas == null) return 0;
        frame(canvas);
        State state = STATES.get(canvas);
        return state == null ? 0 : state.timing.processRandomSources();
    }

    public static synchronized CircuitTimingController timing(CircuitCanvasWidget canvas) {
        State state = STATES.get(canvas);
        return state == null ? null : state.timing;
    }

    public static synchronized boolean toggleAll(CircuitCanvasWidget canvas) {
        CircuitTimingController timing = timing(canvas);
        if (timing == null || timing.clocks().isEmpty()) return false;
        boolean run = false;
        for (CircuitTimingController.ClockAddress address : timing.clocks()) {
            if (!timing.running(address.scopePath(), address.nodeId())) { run = true; break; }
        }
        for (CircuitTimingController.ClockAddress address : timing.clocks()) {
            timing.setRunning(address.scopePath(), address.nodeId(), run);
        }
        State state = STATES.get(canvas);
        if (state != null) state.resetTimingWindow(System.nanoTime());
        return run;
    }

    public static synchronized int stepAll(CircuitCanvasWidget canvas) {
        CircuitTimingController timing = timing(canvas);
        if (timing == null) return 0;
        int count = 0;
        for (CircuitTimingController.ClockAddress address : timing.clocks()) {
            timing.setRunning(address.scopePath(), address.nodeId(), false);
            timing.stepEdges(address.scopePath(), address.nodeId(), 1L);
            count++;
        }
        State state = STATES.get(canvas);
        if (state != null) state.resetTimingWindow(System.nanoTime());
        return count;
    }

    public static synchronized void invalidate(CircuitCanvasWidget canvas) {
        if (canvas != null) STATES.remove(canvas);
    }

    private static final class State {
        private final CompiledCircuit compiled;
        private final CircuitTimingController timing;
        private long lastNanos;
        private long diagnosticStartNanos;
        private long diagnosticEdges;
        private long diagnosticClientTicks;

        private State(CompiledCircuit compiled, CircuitTimingController timing, long lastNanos) {
            this.compiled = compiled;
            this.timing = timing;
            this.lastNanos = lastNanos;
            this.diagnosticStartNanos = lastNanos;
        }

        private void resetTimingWindow(long now) {
            lastNanos = now;
            diagnosticStartNanos = now;
            diagnosticEdges = 0L;
            diagnosticClientTicks = 0L;
        }

        private void recordDiagnostics(long now, long emittedEdges) {
            diagnosticEdges = saturatingAdd(diagnosticEdges, emittedEdges);
            diagnosticClientTicks++;
            long windowNanos = Math.max(0L, now - diagnosticStartNanos);
            if (windowNanos < DIAGNOSTIC_WINDOW_NANOS) return;

            long targetCyclesPerSecond = 0L;
            long pendingEdges = 0L;
            int activeClocks = 0;
            for (CircuitTimingController.ClockAddress address : timing.clocks()) {
                pendingEdges = saturatingAdd(pendingEdges, timing.pendingEdges(address.scopePath(), address.nodeId()));
                if (!timing.active(address.scopePath(), address.nodeId())) continue;
                activeClocks++;
                targetCyclesPerSecond = saturatingAdd(targetCyclesPerSecond, timing.frequencyHz(address.scopePath(), address.nodeId()));
            }

            double seconds = windowNanos / 1_000_000_000.0;
            long actualEdgesPerSecond = seconds <= 0.0 ? 0L : Math.round(diagnosticEdges / seconds);
            long actualCyclesPerSecond = actualEdgesPerSecond / 2L;
            LogicSimulationMod.LOGGER.info(
                    "[CLOCK BENCH/editor] activeClocks={} targetHz={} actualHz={} edgesPerSec={} pendingEdges={} clientTickCalls={}",
                    activeClocks,
                    targetCyclesPerSecond,
                    actualCyclesPerSecond,
                    actualEdgesPerSecond,
                    pendingEdges,
                    diagnosticClientTicks
            );

            diagnosticStartNanos = now;
            diagnosticEdges = 0L;
            diagnosticClientTicks = 0L;
        }

        private static long saturatingAdd(long a, long b) {
            if (b > 0L && a > Long.MAX_VALUE - b) return Long.MAX_VALUE;
            return a + b;
        }
    }
}
