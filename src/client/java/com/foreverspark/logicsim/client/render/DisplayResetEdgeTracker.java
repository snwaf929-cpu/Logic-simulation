package com.foreverspark.logicsim.client.render;

import com.foreverspark.logicsim.display.DisplayCommandCodec;
import com.foreverspark.logicsim.interconnect.CircuitProgramRuntime;

/** Single-worker rising-edge detector for a compiled physical DISPLAY RESET signal. */
public final class DisplayResetEdgeTracker {
    private final CircuitProgramRuntime runtime;
    private final int signalId;
    private boolean lastHigh;

    public DisplayResetEdgeTracker(CircuitProgramRuntime runtime, int signalId) {
        if (runtime == null) throw new IllegalArgumentException("runtime is required");
        if (signalId < 0) throw new IllegalArgumentException("signalId must be >= 0");
        this.runtime = runtime;
        this.signalId = signalId;
    }

    public int signalId() {
        return signalId;
    }

    /** Returns OP_CLEAR exactly once for every LOW->HIGH transition; otherwise returns OP_NOP (zero). */
    public long pollCommand() {
        boolean high = runtime.compiled().simulator().isHighFast(signalId);
        boolean rising = high && !lastHigh;
        lastHigh = high;
        return rising ? DisplayCommandCodec.clear() : 0L;
    }

    /** Re-arms after runtime/device rebinding so a currently asserted RESET is treated as a fresh command. */
    public void rearm() {
        lastHigh = false;
    }
}
