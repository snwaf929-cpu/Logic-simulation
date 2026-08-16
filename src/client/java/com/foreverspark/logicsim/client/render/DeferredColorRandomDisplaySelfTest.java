package com.foreverspark.logicsim.client.render;

import com.foreverspark.logicsim.block.CircuitWorkerPolicy;
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

/** Regression for the real performance failure: 16 CLOCK-driven RGB565 RANDOM bits changed to 10%. */
public final class DeferredColorRandomDisplaySelfTest {
    private static final long BYTE_HIGH_BITS = 0x8080808080808080L;
    private static final long BYTE_REPEAT = 0x0101010101010101L;

    private DeferredColorRandomDisplaySelfTest() {}

    public static void main(String[] args) {
        verifyPackedUnsignedByteCompare();
        verifyWorkerPolicy();

        CircuitDocument board = new CircuitDocument();

        EditorNode clock = board.addNode(NodeKind.CONSTANT, -240, 0);
        clock.width = 1;
        clock.clockSource = true;
        clock.randomSource = false;
        clock.clockFrequencyHz = 50_000_000L;

        EditorNode reset = board.addNode(NodeKind.INPUT, -240, 70);
        reset.width = 1;
        reset.label = "RESET";

        EditorNode[] triggers = new EditorNode[10];
        for (int index = 0; index < triggers.length; index++) {
            triggers[index] = board.addNode(NodeKind.INPUT, -240, 130 + index * 24);
            triggers[index].width = 1;
            triggers[index].label = "TRIGGER_" + index;
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

            if (bank == 2) {
                // User regression: every RGB565 bit is an arbitrary 10% RANDOM probability.
                random.randomChancePercent = 10;
            } else if (lane >= 11 && lane <= 15) {
                // Five CLOCK-driven high X bits make only about 1/32 of random X coordinates reach a 2048-wide wall.
                random.randomChancePercent = 50;
            } else {
                // Coordinate lanes remain on the established cheap common-probability sampler.
                random.randomChancePercent = switch (lane % 3) {
                    case 0 -> 25;
                    case 1 -> 50;
                    default -> 75;
                };
            }

            randoms[lane] = random;
            board.connect(random.id, 0, mergers[bank].id, bit);
        }

        // 22 coordinate lanes + all 16 COLOR lanes = the 38 CLOCK RANDOM lanes from the real log.
        for (int lane = 0; lane < 22; lane++) board.connect(clock.id, 0, randoms[lane].id, 0);
        for (int lane = 32; lane < 48; lane++) board.connect(clock.id, 0, randoms[lane].id, 0);

        // Remaining Y lanes are ten independent trigger groups, preserving the real 11-group topology.
        for (int lane = 22; lane < 32; lane++) {
            board.connect(triggers[lane - 22].id, 0, randoms[lane].id, 0);
        }

        EditorNode display = board.addNode(NodeKind.EXTERNAL_DEVICE, 480, 180);
        display.configureExternalDevice(
                ExternalDeviceType.DISPLAY,
                "deferred-rgb-selftest",
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
                new CircuitProgram(new ChipDefinition("RGB_10_PERCENT_STRESS", board), Map.of())
        );

        DeferredColorRandomDisplayFastPath.CompileResult result = DeferredColorRandomDisplayFastPath.compile(
                runtime, 0, 2_048, 2_048
        );
        check(result.active(), "deferred RGB compiler rejected 10% COLOR stress board: " + result.reason());

        DeferredColorRandomDisplayFastPath.Plan plan = result.plan();
        check(plan.randomLaneCount() == 48, "all 48 RANDOM lanes must stay represented");
        check(plan.clockLaneCount() == 38, "real stress topology must keep 38 CLOCK RANDOM lanes");
        check(plan.hotNonColorLaneCount() == 22, "22 CLOCK coordinate lanes must remain on the cheap sampler");
        check(plan.deferredColorLaneCount() == 16, "all sixteen RGB565 bits must be deferred COLOR lanes");
        check(plan.arbitraryColorLaneCount() == 16, "all sixteen 10% RGB bits must use arbitrary packed thresholds");
        check(plan.arbitraryColorChunkCount() == 2, "sixteen arbitrary RGB bits must fit in two 64-bit byte chunks");
        check(plan.externalTriggerGroupCount() == 10, "ten independent trigger groups must be preserved");
        check(plan.coordinatePrefilterLaneCount() == 10,
                "2048x2048 prefilter must cover five high X and five high Y bits");
        check("field-shift".equals(plan.boundaryPackMode()),
                "three contiguous 16-bit buses must retain field-shift packing");

        ParallelDeferredColorDisplayFastPath.CompileResult parallel = ParallelDeferredColorDisplayFastPath.compile(plan);
        check(parallel.active(), "v10 parallel compiler rejected the proven v9 stress plan: " + parallel.reason());
        check(parallel.plan().minimumCyclesPerWorker() == 8_192,
                "v10 parallel plan must amortize synchronization over large cycle ranges");

        final int[] commands = {0};
        final boolean[] sawNonBinaryColor = {false};
        long emitted = plan.advance(100_000L, 20_000L, (values, count) -> {
            for (int index = 0; index < count; index++) {
                long raw = values[index];
                check(DisplayCommandCodec.decode(raw).isPixel(), "deferred RGB path must emit PIXEL commands only");
                int rgb565 = (int) raw & 0xFFFF;
                if (rgb565 != 0 && rgb565 != 0xFFFF) sawNonBinaryColor[0] = true;
            }
            commands[0] += count;
        });

        check(emitted == 10_000L, "100us at 50MHz must consume exactly 10,000 clock edges");
        check(plan.lastClockCycles() == 5_000L, "10,000 clock edges must contain exactly 5,000 rising samples");
        check(commands[0] > 0, "coordinate prefilter must still allow some RGB pixels through");
        check(commands[0] < plan.lastClockCycles() / 2,
                "high X bits must reject most writes so arbitrary RGB sampling is genuinely deferred");
        check(plan.lastDisplayWrites() == commands[0], "DISPLAY write telemetry mismatch");
        check(plan.lastColorSamples() == commands[0] || plan.lastColorSamples() == commands[0] + 1L,
                "COLOR must be sampled only for visible writes plus at most one final-state repair sample");
        check(plan.lastColorSamples() < plan.lastClockCycles() / 2,
                "10% RGB sampler must not run on every virtual clock cycle");
        check(sawNonBinaryColor[0], "10% RGB stress path must produce colors beyond pure black/white");

        System.out.println("Deferred arbitrary-RGB DISPLAY hotloop-v5 + parallel-v10 compile self-test: PASS"
                + " | emittedEdges=" + emitted
                + " clockCycles=" + plan.lastClockCycles()
                + " displayWrites=" + plan.lastDisplayWrites()
                + " colorSamples=" + plan.lastColorSamples()
                + " arbitraryColorLanes=" + plan.arbitraryColorLaneCount()
                + " colorChunks=" + plan.arbitraryColorChunkCount()
                + " boundaryPack=" + plan.boundaryPackMode()
                + " parallelCompile=true");
    }

    private static void verifyWorkerPolicy() {
        check(CircuitWorkerPolicy.systemMaximum(32) == 8, "32 logical CPUs must expose max 8 simulation workers");
        check(CircuitWorkerPolicy.systemMaximum(24) == 6, "24 logical CPUs must expose max 6 simulation workers");
        check(CircuitWorkerPolicy.systemMaximum(16) == 4, "16 logical CPUs must expose max 4 simulation workers");
        check(CircuitWorkerPolicy.systemMaximum(8) == 2, "8 logical CPUs must expose max 2 simulation workers");
        check(CircuitWorkerPolicy.systemMaximum(4) == 1, "4 logical CPUs must expose max 1 simulation worker");
        check(CircuitWorkerPolicy.resolve(CircuitWorkerPolicy.AUTO, 8) == 8, "AUTO must resolve to the machine simulation cap");
        check(CircuitWorkerPolicy.resolve(3, 8) == 3, "explicit worker request must be preserved inside the machine cap");
        check(CircuitWorkerPolicy.resolve(20, 8) == 8, "explicit worker request must clamp to the machine cap");
    }

    private static void verifyPackedUnsignedByteCompare() {
        for (int threshold = 0; threshold < 256; threshold++) {
            long thresholdBytes = (long) threshold * BYTE_REPEAT;
            for (int value = 0; value < 256; value++) {
                long randomBytes = (long) value * BYTE_REPEAT;
                long actual = DeferredColorRandomDisplayFastPath.unsignedByteLessThan(randomBytes, thresholdBytes);
                long expected = value < threshold ? BYTE_HIGH_BITS : 0L;
                check(actual == expected,
                        "packed unsigned byte compare mismatch value=" + value + " threshold=" + threshold);
            }
        }
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
