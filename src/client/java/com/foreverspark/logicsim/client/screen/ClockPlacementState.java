package com.foreverspark.logicsim.client.screen;

import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class ClockPlacementState {
    public static final long DEFAULT_FREQUENCY_HZ = 1_000_000L;
    public static final long MAX_FREQUENCY_HZ = 100_000_000L;
    private static boolean armed;
    private static long frequencyHz = DEFAULT_FREQUENCY_HZ;
    private static CircuitCanvasWidget canvas;
    private static int nodeCountBefore;

    static { ClientTickEvents.END_CLIENT_TICK.register(client -> pollPlacement()); }
    private ClockPlacementState() {}
    public static void arm(CircuitCanvasWidget target) { canvas = target; nodeCountBefore = target == null ? 0 : target.document().nodes.size(); armed = target != null; }
    public static void arm() { armed = true; }
    public static boolean armed() { return armed; }
    public static long frequencyHz() { return frequencyHz; }
    public static void setFrequencyHz(long hz) { frequencyHz = Math.max(1L, Math.min(MAX_FREQUENCY_HZ, hz)); }
    public static void disarm() { armed = false; canvas = null; }

    private static void pollPlacement() {
        if (!armed || canvas == null) return;
        if (canvas.document().nodes.size() <= nodeCountBefore) return;
        EditorNode node = canvas.document().nodes.getLast();
        if (node.kind != NodeKind.CONSTANT) { disarm(); return; }
        node.clockSource = true;
        node.clockFrequencyHz = frequencyHz;
        node.width = 1;
        node.constantValue = 0L;
        canvas.refreshLiveRuntime();
        EditorClockRuntime.attach(canvas);
        disarm();
    }
}
