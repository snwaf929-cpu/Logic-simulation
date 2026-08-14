package com.foreverspark.logicsim.client.screen;

import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/** Arms the next placed CONSTANT node as a rising-edge RANDOM source. */
public final class RandomPlacementState {
    public static final int DEFAULT_CHANCE_PERCENT = 50;

    private static boolean armed;
    private static int chancePercent = DEFAULT_CHANCE_PERCENT;
    private static CircuitCanvasWidget canvas;
    private static int nodeCountBefore;

    static {
        ClientTickEvents.END_CLIENT_TICK.register(client -> pollPlacement());
    }

    private RandomPlacementState() {}

    public static void arm(CircuitCanvasWidget target) {
        canvas = target;
        nodeCountBefore = target == null ? 0 : target.document().nodes.size();
        armed = target != null;
    }

    public static boolean armed() { return armed; }
    public static int chancePercent() { return chancePercent; }
    public static void setChancePercent(int chance) { chancePercent = Math.max(0, Math.min(100, chance)); }

    public static void disarm() {
        armed = false;
        canvas = null;
    }

    private static void pollPlacement() {
        if (!armed || canvas == null) return;
        if (canvas.document().nodes.size() <= nodeCountBefore) return;
        EditorNode node = canvas.document().nodes.getLast();
        if (node.kind != NodeKind.CONSTANT) {
            disarm();
            return;
        }
        node.randomSource = true;
        node.clockSource = false;
        node.randomChancePercent = chancePercent;
        node.width = 1;
        node.constantValue = 0L;
        canvas.refreshLiveRuntime();
        EditorClockRuntime.attach(canvas);
        disarm();
    }
}
