package com.foreverspark.logicsim.client.render;

import com.foreverspark.logicsim.display.DisplayCommandCodec;
import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.ExternalDeviceState;
import com.foreverspark.logicsim.editor.model.ExternalDeviceType;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.interconnect.CircuitProgram;
import com.foreverspark.logicsim.interconnect.CircuitProgramRuntime;

import java.util.Map;

/** Reproduces the real 48-RANDOM / 11-trigger-group / INPUT-driven RESET physical DISPLAY workload. */
public final class RandomDisplayNetworkSelfTest {
    private RandomDisplayNetworkSelfTest() {}

    public static void main(String[] args) {
        CircuitDocument board = new CircuitDocument();

        EditorNode clock = board.addNode(NodeKind.CONSTANT, -180, 0);
        clock.width = 1;
        clock.clockSource = true;
        clock.randomSource = false;
        clock.clockFrequencyHz = 50_000_000L;

        // Matches the real regression log: DISPLAY RESET is driven by a root INPUT, not a static constant.
        EditorNode resetInput = board.addNode(NodeKind.INPUT, -180, 80);
        resetInput.width = 1;
        resetInput.label = "RESET";

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
            random.randomChancePercent = switch (lane % 3) {
                case 0 -> 25;
                case 1 -> 50;
                default -> 75;
            };
            randoms[lane] = random;
            board.connect(random.id, 0, mergers[bank].id, bit);
        }

        // Group 0: eleven RANDOMs are directly CLOCK-triggered.
        for (int lane = 0; lane < 11; lane++) board.connect(clock.id, 0, randoms[lane].id, 0);

        // Groups 1..10: the remaining 37 RANDOMs are triggered from rising outputs of RANDOM 0..9.
        for (int lane = 11; lane < randoms.length; lane++) {
            int triggerLane = (lane - 11) % 10;
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
                new CircuitProgram(new ChipDefinition("RANDOM_NETWORK_11_DYNAMIC_RESET", board), Map.of())
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
        check(compiled.active(), "dynamic RESET compatibility compiler rejected test board: " + compiled.reason());
        check(compiled.reason().startsWith("active-dynamic-reset:"),
                "compat compiler must report dynamic RESET activation details");
        check(board.wires.size() == wireCountBeforeCompile,
                "compat compiler must restore the real RESET wire after structural proof");
        check(compiled.plan().randomLaneCount() == 48, "compiled network must contain all 48 RANDOM lanes");
        check(compiled.plan().triggerGroupCount() == 11, "compiled network must preserve all 11 trigger groups");

        int resetSignalId = RandomDisplayNetworkResetCompat.resetSignalId(runtime, 0);
        check(resetSignalId >= 0, "dynamic RESET signal must resolve");
        DisplayResetEdgeTracker resetTracker = new DisplayResetEdgeTracker(runtime, resetSignalId);

        check(resetTracker.pollCommand() == 0L, "RESET low must not clear");
        runtime.compiled().driveInputUnsigned(resetInput.id, 1L);
        check(DisplayCommandCodec.decode(resetTracker.pollCommand()).isClear(), "RESET rising edge must emit CLEAR");
        check(resetTracker.pollCommand() == 0L, "held RESET high must not repeat CLEAR");
        runtime.compiled().driveInputUnsigned(resetInput.id, 0L);
        check(resetTracker.pollCommand() == 0L, "RESET falling edge must not clear");
        runtime.compiled().driveInputUnsigned(resetInput.id, 1L);
        check(DisplayCommandCodec.decode(resetTracker.pollCommand()).isClear(), "second RESET rising edge must emit CLEAR");
        runtime.compiled().driveInputUnsigned(resetInput.id, 0L);
        check(resetTracker.pollCommand() == 0L, "RESET low must re-arm without clearing");

        final int[] commandCount = {0};
        final long[] firstCommand = {0L};
        long emitted = compiled.plan().advance(100_000L, 20_000L, (values, count) -> {
            if (count > 0 && commandCount[0] == 0) firstCommand[0] = values[0];
            commandCount[0] += count;
        });

        check(emitted > 0L, "compiled network must consume queued 50 MHz clock edges");
        check(commandCount[0] > 0, "compiled network must emit physical DISPLAY writes");
        check(DisplayCommandCodec.decode(firstCommand[0]).isPixel(), "compiled network must emit PIXEL commands");

        System.out.println("48-RANDOM / 11-trigger-group / dynamic INPUT RESET physical DISPLAY bulk self-test: PASS"
                + " | emittedEdges=" + emitted + " commands=" + commandCount[0]
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
