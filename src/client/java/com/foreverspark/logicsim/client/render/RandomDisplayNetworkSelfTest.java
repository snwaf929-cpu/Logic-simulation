package com.foreverspark.logicsim.client.render;

import com.foreverspark.logicsim.core.Signal;
import com.foreverspark.logicsim.display.DisplayCommandCodec;
import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.ExternalDeviceState;
import com.foreverspark.logicsim.editor.model.ExternalDeviceType;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.runtime.CompiledCircuit;
import com.foreverspark.logicsim.interconnect.CircuitProgram;
import com.foreverspark.logicsim.interconnect.CircuitProgramRuntime;

import java.util.Map;

/** Reproduces the real 48-RANDOM / 11-trigger-group / external-trigger physical DISPLAY workload. */
public final class RandomDisplayNetworkSelfTest {
    private RandomDisplayNetworkSelfTest() {}

    public static void main(String[] args) {
        CircuitDocument board = new CircuitDocument();

        EditorNode clock = board.addNode(NodeKind.CONSTANT, -180, 0);
        clock.width = 1;
        clock.clockSource = true;
        clock.randomSource = false;
        clock.clockFrequencyHz = 50_000_000L;

        // Matches the real board boundary: RESET is a live root INPUT.
        EditorNode resetInput = board.addNode(NodeKind.INPUT, -180, 80);
        resetInput.width = 1;
        resetInput.label = "RESET";

        // Reproduces the new log blocker: one RANDOM trigger group comes from a signal outside CLOCK/RANDOM outputs.
        EditorNode externalTrigger = board.addNode(NodeKind.INPUT, -180, 140);
        externalTrigger.width = 1;
        externalTrigger.label = "RANDOM_TRIGGER";

        EditorNode x = merger16(board, 180, 0);
        EditorNode y = merger16(board, 180, 180);
        EditorNode color = merger16(board, 180, 360);
        EditorNode[] mergers = {x, y, color};

        EditorNode[] randoms = new EditorNode[48];
        for (int lane = 0; lane < randoms.length; lane++) {
            int bank = lane >>> 4;
            int bit = lane & 15;
            EditorNode random = board.addNode(NodeKind.CONSTANT, 0, bank * 180 + bit * 9);
            random.width = 1;
            random.constantValue = 0L;
            random.clockSource = false;
            random.randomSource = true;
            random.randomChancePercent = lane >= 11 && lane <= 14 ? 100 : switch (lane % 3) {
                case 0 -> 25;
                case 1 -> 50;
                default -> 75;
            };
            randoms[lane] = random;
            board.connect(random.id, 0, mergers[bank].id, bit);
        }

        // Group 0: eleven RANDOMs are directly CLOCK-triggered.
        for (int lane = 0; lane < 11; lane++) board.connect(clock.id, 0, randoms[lane].id, 0);

        // Group 1: four RANDOMs use an independent root INPUT trigger.
        for (int lane = 11; lane <= 14; lane++) board.connect(externalTrigger.id, 0, randoms[lane].id, 0);

        // Groups 2..10: remaining RANDOMs are triggered by RANDOM outputs 0..8. Total trigger groups stays exactly 11.
        for (int lane = 15; lane < randoms.length; lane++) {
            int triggerLane = (lane - 15) % 9;
            board.connect(randoms[triggerLane].id, 0, randoms[lane].id, 0);
        }

        EditorNode display = board.addNode(NodeKind.EXTERNAL_DEVICE, 420, 180);
        display.configureExternalDevice(
                ExternalDeviceType.DISPLAY,
                "display-network-selftest",
                ExternalDeviceState.CONNECTED,
                "test",
                0,
                0,
                0
        );
        board.connect(x.id, 0, display.id, 0);
        board.connect(y.id, 0, display.id, 1);
        board.connect(color.id, 0, display.id, 2);
        board.connect(clock.id, 0, display.id, 3);
        board.connect(resetInput.id, 0, display.id, 4);
        int wireCountBeforeCompile = board.wires.size();

        CircuitProgramRuntime runtime = new CircuitProgramRuntime(
                new CircuitProgram(new ChipDefinition("RANDOM_NETWORK_11_EXTERNAL_TRIGGER", board), Map.of())
        );

        check(runtime.externalDeviceCount() == 1, "self-test must compile one physical DISPLAY");
        check(!runtime.directRandomDeviceDisplayBatchEligible(0),
                "legacy one-trigger bulk path must reject the eleven-group RANDOM network");

        RandomDisplayNetworkFastPath.CompileResult strict = RandomDisplayNetworkFastPath.compile(
                runtime, 0, 65_536, 65_536
        );
        check(!strict.active() && "display-reset-wired".equals(strict.reason()),
                "strict network compiler must expose the wired-RESET boundary");

        RandomDisplayNetworkFastPath.CompileResult compiled = RandomDisplayNetworkResetCompat.compile(
                runtime, 0, 65_536, 65_536
        );
        check(compiled.active(), "dynamic RESET compatibility compiler rejected external-trigger board: " + compiled.reason());
        check(compiled.reason().startsWith("active-dynamic-reset:"),
                "compat compiler must report dynamic RESET activation details");
        check(board.wires.size() == wireCountBeforeCompile,
                "compat compiler must restore the real RESET wire after structural proof");
        check(compiled.plan().randomLaneCount() == 48, "compiled network must contain all 48 RANDOM lanes");
        check(compiled.plan().triggerGroupCount() == 11, "compiled network must preserve all 11 trigger groups");
        check(compiled.plan().externalTriggerGroupCount() == 1,
                "compiled network must preserve the one independent trigger group");

        // Independent trigger groups are edge-detected once per worker slice, not scanned on every MHz clock edge.
        check(compiled.plan().externalTriggerFireCount() == 0L, "external trigger must start armed at LOW");
        compiled.plan().advance(0L, 0L, null);
        check(compiled.plan().externalTriggerFireCount() == 0L, "LOW external trigger must not fire");

        runtime.compiled().driveInputUnsigned(externalTrigger.id, 1L);
        compiled.plan().advance(0L, 0L, null);
        check(compiled.plan().externalTriggerFireCount() == 1L, "external LOW->HIGH must fire exactly once");
        Signal externalRandomOutput = runtime.compiled().outputSignal(
                CompiledCircuit.ROOT_SCOPE, randoms[11].id, 0, 0
        );
        check(externalRandomOutput != null && runtime.compiled().simulator().isHighFast(externalRandomOutput.id()),
                "100% external-trigger RANDOM lane must be committed HIGH");

        compiled.plan().advance(0L, 0L, null);
        check(compiled.plan().externalTriggerFireCount() == 1L, "held external HIGH must not refire");
        runtime.compiled().driveInputUnsigned(externalTrigger.id, 0L);
        compiled.plan().advance(0L, 0L, null);
        check(compiled.plan().externalTriggerFireCount() == 1L, "external falling edge must not fire");
        runtime.compiled().driveInputUnsigned(externalTrigger.id, 1L);
        compiled.plan().advance(0L, 0L, null);
        check(compiled.plan().externalTriggerFireCount() == 2L, "second external rising edge must fire again");
        runtime.compiled().driveInputUnsigned(externalTrigger.id, 0L);
        compiled.plan().advance(0L, 0L, null);

        int resetSignalId = RandomDisplayNetworkResetCompat.resetSignalId(runtime, 0);
        check(resetSignalId >= 0, "dynamic RESET signal must resolve");
        DisplayResetEdgeTracker resetTracker = new DisplayResetEdgeTracker(runtime, resetSignalId);
        check(resetTracker.pollCommand() == 0L, "RESET low must not clear");
        runtime.compiled().driveInputUnsigned(resetInput.id, 1L);
        check(DisplayCommandCodec.decode(resetTracker.pollCommand()).isClear(), "RESET rising edge must emit CLEAR");
        check(resetTracker.pollCommand() == 0L, "held RESET high must not repeat CLEAR");
        runtime.compiled().driveInputUnsigned(resetInput.id, 0L);
        check(resetTracker.pollCommand() == 0L, "RESET falling edge must not clear");

        final int[] commandCount = {0};
        final long[] firstCommand = {0L};
        long emitted = compiled.plan().advance(100_000L, 20_000L, (values, count) -> {
            if (count > 0 && commandCount[0] == 0) firstCommand[0] = values[0];
            commandCount[0] += count;
        });

        check(emitted > 0L, "compiled network must consume queued 50 MHz clock edges");
        check(commandCount[0] > 0, "compiled network must emit physical DISPLAY writes");
        check(DisplayCommandCodec.decode(firstCommand[0]).isPixel(), "compiled network must emit PIXEL commands");

        System.out.println("48-RANDOM / 11-trigger-group / external INPUT trigger / dynamic RESET DISPLAY bulk self-test: PASS"
                + " | emittedEdges=" + emitted + " commands=" + commandCount[0]
                + " externalFires=" + compiled.plan().externalTriggerFireCount()
                + " resetSignal=" + resetSignalId + " compile=" + compiled.reason());
    }

    private static EditorNode merger16(CircuitDocument board, double x, double y) {
        EditorNode node = board.addNode(NodeKind.MERGER, x, y);
        node.width = 16;
        node.laneWidth = 1;
        return node;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
