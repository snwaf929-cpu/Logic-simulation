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

/** Mirrors the user's current runtime: 48 RANDOM, 38 CLOCK lanes, 10 independent trigger groups, dynamic RESET. */
public final class DenseIndependentRandomDisplaySelfTest {
    private DenseIndependentRandomDisplaySelfTest() {}

    public static void main(String[] args) {
        CircuitDocument board = new CircuitDocument();

        EditorNode clock = board.addNode(NodeKind.CONSTANT, -240, 0);
        clock.width = 1;
        clock.clockSource = true;
        clock.randomSource = false;
        clock.clockFrequencyHz = 50_000_000L;

        EditorNode reset = board.addNode(NodeKind.INPUT, -240, 80);
        reset.width = 1;
        reset.label = "RESET";

        EditorNode[] triggers = new EditorNode[10];
        for (int i = 0; i < triggers.length; i++) {
            triggers[i] = board.addNode(NodeKind.INPUT, -240, 140 + i * 24);
            triggers[i].width = 1;
            triggers[i].label = "TRIGGER_" + i;
        }

        EditorNode x = merger16(board, 220, 0);
        EditorNode y = merger16(board, 220, 180);
        EditorNode color = merger16(board, 220, 360);
        EditorNode[] mergers = {x, y, color};

        EditorNode[] randoms = new EditorNode[48];
        for (int lane = 0; lane < randoms.length; lane++) {
            int bank = lane >>> 4;
            int bit = lane & 15;
            EditorNode random = board.addNode(NodeKind.CONSTANT, 0, bank * 180 + bit * 9);
            random.width = 1;
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

        // Exact shape reported by the user's latest log: 38 RANDOM lanes on the MHz CLOCK.
        for (int lane = 0; lane < 38; lane++) board.connect(clock.id, 0, randoms[lane].id, 0);
        // Remaining ten lanes each have their own independent trigger => eleven trigger groups total.
        for (int lane = 38; lane < 48; lane++) board.connect(triggers[lane - 38].id, 0, randoms[lane].id, 0);

        EditorNode display = board.addNode(NodeKind.EXTERNAL_DEVICE, 480, 180);
        display.configureExternalDevice(
                ExternalDeviceType.DISPLAY,
                "dense-display-selftest",
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
        board.connect(reset.id, 0, display.id, 4);

        CircuitProgramRuntime runtime = new CircuitProgramRuntime(
                new CircuitProgram(new ChipDefinition("DENSE_38_CLOCK_10_EXTERNAL", board), Map.of())
        );

        DenseIndependentRandomDisplayFastPath.CompileResult dense = DenseIndependentRandomDisplayFastPath.compile(
                runtime, 0, 2_048, 2_048
        );
        check(dense.active(), "dense compiler rejected real topology: " + dense.reason());
        check(dense.plan().randomLaneCount() == 48, "all 48 RANDOM lanes must stay represented");
        check(dense.plan().clockLaneCount() == 38, "real topology must compile 38 CLOCK RANDOM lanes");
        check(dense.plan().externalTriggerGroupCount() == 10, "real topology must compile 10 independent groups");
        check(dense.plan().rngWordsPerCycle() == 1, "dense hot loop must consume one RNG word per cycle");
        check(dense.plan().randomBitsPerCycle() == 63,
                "38 lanes with 25/50/75 pattern must fit in exactly 63 random bits");
        check(dense.plan().coordinatePrefilterLaneCount() == 10,
                "2048x2048 must reject five high X bits plus five high Y bits");
        check("field-shift".equals(dense.plan().boundaryPackMode()),
                "three contiguous 16-bit buses must use field-shift packing");

        final int[] commands = {0};
        final long[] first = {0L};
        long emitted = dense.plan().advance(100_000L, 20_000L, (values, count) -> {
            if (count > 0 && commands[0] == 0) first[0] = values[0];
            commands[0] += count;
        });
        check(emitted == 10_000L, "100us at 50MHz must consume exactly 10,000 clock edges");
        check(commands[0] > 0, "dense engine must publish in-range DISPLAY pixels");
        check(DisplayCommandCodec.decode(first[0]).isPixel(), "dense engine output must be PIXEL command");

        // Independent trigger polling is delegated to the already-proven v3 plan. It must remain edge based.
        runtime.compiled().driveInputUnsigned(triggers[0].id, 1L);
        dense.plan().advance(0L, 0L, null);
        runtime.compiled().driveInputUnsigned(triggers[0].id, 0L);
        dense.plan().advance(0L, 0L, null);

        System.out.println("Dense 38-CLOCK-lane / 10-independent-trigger DISPLAY hotloop-v4 self-test: PASS"
                + " | emittedEdges=" + emitted
                + " commands=" + commands[0]
                + " randomBitsPerCycle=" + dense.plan().randomBitsPerCycle()
                + " rngWordsPerCycle=" + dense.plan().rngWordsPerCycle()
                + " prefilter2048=" + dense.plan().coordinatePrefilterLaneCount()
                + " boundaryPack=" + dense.plan().boundaryPackMode());
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
