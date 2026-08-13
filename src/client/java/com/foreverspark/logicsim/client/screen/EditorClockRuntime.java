package com.foreverspark.logicsim.client.screen;

import com.foreverspark.logicsim.editor.runtime.CircuitTimingController;
import com.foreverspark.logicsim.editor.runtime.CompiledCircuit;
import com.foreverspark.logicsim.mixin.client.CanvasAccess;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Client editor scheduler. Frames only batch virtual elapsed time; they do not define clock frequency.
 * If accurate simulation cannot keep up, TimingDomain backlog is preserved instead of dropping edges.
 */
public final class EditorClockRuntime {
    private static final long EDGE_BUDGET_PER_CLOCK_PER_FRAME = 5_000L;
    private static final Map<CircuitCanvasWidget, State> STATES = new WeakHashMap<>();

    private EditorClockRuntime() {}

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
            state = new State(compiled, new CircuitTimingController(
                    compiled,
                    access.logic$getRuntimeRootDocument(),
                    access.logic$getChipLibrary()
            ), System.nanoTime());
            STATES.put(canvas, state);
            return;
        }

        long now = System.nanoTime();
        long elapsed = Math.max(0L, now - state.lastNanos);
        state.lastNanos = now;
        if (elapsed == 0L || state.timing.clocks().isEmpty()) return;
        state.timing.advanceNanos(elapsed, EDGE_BUDGET_PER_CLOCK_PER_FRAME);
    }

    public static synchronized CircuitTimingController timing(CircuitCanvasWidget canvas) {
        State state = STATES.get(canvas);
        return state == null ? null : state.timing;
    }

    public static synchronized void invalidate(CircuitCanvasWidget canvas) {
        if (canvas != null) STATES.remove(canvas);
    }

    private static final class State {
        private final CompiledCircuit compiled;
        private final CircuitTimingController timing;
        private long lastNanos;

        private State(CompiledCircuit compiled, CircuitTimingController timing, long lastNanos) {
            this.compiled = compiled;
            this.timing = timing;
            this.lastNanos = lastNanos;
        }
    }
}
