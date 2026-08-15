package com.foreverspark.logicsim.client.screen.v2;

import com.foreverspark.logicsim.editor.model.BusSliceOutput;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.runtime.CircuitCompileException;
import com.foreverspark.logicsim.editor.runtime.CircuitCompiler;
import com.foreverspark.logicsim.editor.runtime.CompiledCircuit;

import java.util.ArrayList;

/** Dependency-light electrical regression checks for Logic Editor V2 Phase 2. */
public final class EditorV2Phase2Checks {
    private EditorV2Phase2Checks() {}

    public static void run() {
        arbitraryWidthChecks();
        busSliceChecks();
        netLabelChecks();
        numericCodecChecks();
        snapshotSliceChecks();
    }

    private static void arbitraryWidthChecks() {
        CircuitDocument document = new CircuitDocument();
        EditorNode input = document.addNode(NodeKind.INPUT, 0, 0);
        input.width = 12;
        EditorNode bus = document.addNode(NodeKind.BUS, 90, 0);
        bus.width = 12;
        EditorNode output = document.addNode(NodeKind.OUTPUT, 180, 0);
        output.width = 12;
        document.connect(input.id, 0, bus.id, 0);
        document.connect(bus.id, 0, output.id, 0);
        CompiledCircuit compiled = CircuitCompiler.compile(document, name -> null);
        compiled.driveInputUnsigned(input.id, 0xA55L);
        check(compiled.inputUnsigned(output.id, 0) == 0xA55L, "12-bit arbitrary BUS width propagates");

        CircuitDocument grouped = new CircuitDocument();
        EditorNode source = grouped.addNode(NodeKind.INPUT, 0, 0);
        source.width = 12;
        EditorNode split = grouped.addNode(NodeKind.SPLITTER, 90, 0);
        split.width = 12;
        split.laneWidth = 3;
        EditorNode merge = grouped.addNode(NodeKind.MERGER, 180, 0);
        merge.width = 12;
        merge.laneWidth = 3;
        EditorNode sink = grouped.addNode(NodeKind.OUTPUT, 270, 0);
        sink.width = 12;
        grouped.connect(source.id, 0, split.id, 0);
        for (int lane = 0; lane < 4; lane++) grouped.connect(split.id, lane, merge.id, lane);
        grouped.connect(merge.id, 0, sink.id, 0);
        CompiledCircuit groupedCompiled = CircuitCompiler.compile(grouped, name -> null);
        groupedCompiled.driveInputUnsigned(source.id, 0xD6BL);
        check(groupedCompiled.inputUnsigned(sink.id, 0) == 0xD6BL, "12-bit bus supports four 3-bit grouped lanes");
    }

    private static void busSliceChecks() {
        CircuitDocument document = new CircuitDocument();
        EditorNode input = document.addNode(NodeKind.INPUT, 0, 0);
        input.width = 16;
        EditorNode slice = document.addNode(NodeKind.BUS_SLICE, 100, 0);
        slice.width = 16;
        slice.slices = new ArrayList<>();
        slice.slices.add(new BusSliceOutput("OPCODE", 12, 4));
        slice.slices.add(new BusSliceOutput("OPERAND", 0, 12));
        EditorNode opcode = document.addNode(NodeKind.OUTPUT, 220, 0);
        opcode.width = 4;
        EditorNode operand = document.addNode(NodeKind.OUTPUT, 220, 60);
        operand.width = 12;
        document.connect(input.id, 0, slice.id, 0);
        document.connect(slice.id, 0, opcode.id, 0);
        document.connect(slice.id, 1, operand.id, 0);

        CompiledCircuit compiled = CircuitCompiler.compile(document, name -> null);
        compiled.driveInputUnsigned(input.id, 0xA55AL);
        check(compiled.inputUnsigned(opcode.id, 0) == 0xAL, "BUS_SLICE extracts INSTRUCTION[15:12]");
        check(compiled.inputUnsigned(operand.id, 0) == 0x55AL, "BUS_SLICE extracts INSTRUCTION[11:0]");
    }

    private static void netLabelChecks() {
        CircuitDocument defaults = new CircuitDocument();
        EditorNode defaultA = defaults.addNode(NodeKind.NET_LABEL, 0, 0);
        EditorNode defaultB = defaults.addNode(NodeKind.NET_LABEL, 100, 0);
        check(!defaultA.label.equalsIgnoreCase(defaultB.label), "fresh NET_LABELs do not accidentally share one default electrical net");
        check(defaultA.label.equals("NET" + defaultA.id) && defaultB.label.equals("NET" + defaultB.id),
                "fresh NET_LABELs expose deterministic unique default names");
        defaultA.label = "";
        defaultB.label = "NET";
        defaults.normalize();
        check(defaultA.label.equals("NET" + defaultA.id), "blank NET_LABEL normalizes to a unique name");
        check(defaultB.label.equals("NET"), "explicit legacy NET label name remains electrically compatible");

        CircuitDocument document = new CircuitDocument();
        EditorNode input = document.addNode(NodeKind.INPUT, 0, 0);
        input.width = 12;
        EditorNode sourceLabel = document.addNode(NodeKind.NET_LABEL, 90, 0);
        sourceLabel.width = 12;
        sourceLabel.label = "DATA_BUS";
        EditorNode remoteLabel = document.addNode(NodeKind.NET_LABEL, 260, 100);
        remoteLabel.width = 12;
        remoteLabel.label = "data_bus";
        EditorNode output = document.addNode(NodeKind.OUTPUT, 360, 100);
        output.width = 12;
        document.connect(input.id, 0, sourceLabel.id, 0);
        document.connect(remoteLabel.id, 0, output.id, 0);

        CompiledCircuit compiled = CircuitCompiler.compile(document, name -> null);
        compiled.driveInputUnsigned(input.id, 0xB4DL);
        check(compiled.inputUnsigned(output.id, 0) == 0xB4DL, "same-name NET_LABELs share one electrical net case-insensitively");

        CircuitDocument floating = new CircuitDocument();
        EditorNode a = floating.addNode(NodeKind.NET_LABEL, 0, 0);
        a.width = 5;
        a.label = "RESET_BUS";
        EditorNode b = floating.addNode(NodeKind.NET_LABEL, 100, 0);
        b.width = 5;
        b.label = "RESET_BUS";
        EditorNode low = floating.addNode(NodeKind.OUTPUT, 200, 0);
        low.width = 5;
        floating.connect(b.id, 0, low.id, 0);
        CompiledCircuit floatingCompiled = CircuitCompiler.compile(floating, name -> null);
        check(floatingCompiled.inputUnsigned(low.id, 0) == 0L, "undriven NET_LABEL defaults LOW");

        CircuitDocument multiple = new CircuitDocument();
        EditorNode one = multiple.addNode(NodeKind.CONSTANT, 0, 0);
        one.width = 3;
        one.constantValue = 7;
        EditorNode zero = multiple.addNode(NodeKind.CONSTANT, 0, 80);
        zero.width = 3;
        zero.constantValue = 0;
        EditorNode n1 = multiple.addNode(NodeKind.NET_LABEL, 100, 0);
        n1.width = 3;
        n1.label = "CTRL";
        EditorNode n2 = multiple.addNode(NodeKind.NET_LABEL, 100, 80);
        n2.width = 3;
        n2.label = "CTRL";
        multiple.connect(one.id, 0, n1.id, 0);
        multiple.connect(zero.id, 0, n2.id, 0);
        boolean rejected = false;
        try {
            CircuitCompiler.compile(multiple, name -> null);
        } catch (CircuitCompileException expected) {
            rejected = expected.getMessage() != null && expected.getMessage().contains("multiple drivers");
        }
        check(rejected, "NET_LABEL rejects multiple distinct drivers");
    }

    private static void numericCodecChecks() {
        long value = NumericValueCodec.parse("0xA55", NumericValueCodec.Radix.HEX, 12);
        check(value == 0xA55L, "HEX parses 12-bit value");
        check(NumericValueCodec.parse("2645", NumericValueCodec.Radix.DEC, 12) == value, "DEC matches HEX value");
        check(NumericValueCodec.parse("1010 0101 0101", NumericValueCodec.Radix.BIN, 12) == value, "spaced BIN matches HEX value");
        check(NumericValueCodec.hex(value, 12).equals("0xA55"), "HEX formatting preserves width");
        check(NumericValueCodec.bin(value, 12).equals("1010 0101 0101"), "BIN formatting groups nibbles");
        check(NumericValueCodec.parse("18446744073709551615", NumericValueCodec.Radix.DEC, 64) == -1L, "unsigned 64-bit DEC max is accepted");

        boolean overflow = false;
        try {
            NumericValueCodec.parse("0x1000", NumericValueCodec.Radix.HEX, 12);
        } catch (IllegalArgumentException expected) {
            overflow = true;
        }
        check(overflow, "numeric editor rejects values wider than configured bus");
    }

    private static void snapshotSliceChecks() {
        CircuitDocument document = new CircuitDocument();
        EditorNode slice = document.addNode(NodeKind.BUS_SLICE, 0, 0);
        slice.width = 24;
        slice.slices = new ArrayList<>();
        slice.slices.add(new BusSliceOutput("FIELD", 5, 6));
        CircuitDocument copy = EditorDocumentSnapshot.copy(document);
        check(EditorDocumentSnapshot.same(document, copy), "undo snapshot preserves BUS_SLICE ranges");
        copy.node(slice.id).slices.getFirst().startBit = 7;
        check(document.node(slice.id).slices.getFirst().startBit == 5, "BUS_SLICE snapshot is deep copied");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
