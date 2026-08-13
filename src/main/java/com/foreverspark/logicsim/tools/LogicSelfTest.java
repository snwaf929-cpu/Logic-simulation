package com.foreverspark.logicsim.tools;

import com.foreverspark.logicsim.core.Bus;
import com.foreverspark.logicsim.core.CircuitSimulator;
import com.foreverspark.logicsim.core.LogicCircuit;
import com.foreverspark.logicsim.core.LogicValue;
import com.foreverspark.logicsim.core.Signal;
import com.foreverspark.logicsim.core.TraceRecorder;

public final class LogicSelfTest {
    private LogicSelfTest() {}

    public static void main(String[] args) {
        testNandTruthTable();
        testNotFromNand();
        testAndFromNandOnly();
        testBusSplitMerge();
        testTraceRingBuffer();
        System.out.println("Logic core self-test: PASS");
    }

    private static void testNandTruthTable() {
        check(LogicValue.nand(LogicValue.LOW, LogicValue.LOW) == LogicValue.HIGH, "NAND 0,0");
        check(LogicValue.nand(LogicValue.LOW, LogicValue.HIGH) == LogicValue.HIGH, "NAND 0,1");
        check(LogicValue.nand(LogicValue.HIGH, LogicValue.LOW) == LogicValue.HIGH, "NAND 1,0");
        check(LogicValue.nand(LogicValue.HIGH, LogicValue.HIGH) == LogicValue.LOW, "NAND 1,1");
        check(LogicValue.nand(LogicValue.UNKNOWN, LogicValue.HIGH) == LogicValue.UNKNOWN, "NAND X,1");
        check(LogicValue.nand(LogicValue.UNKNOWN, LogicValue.LOW) == LogicValue.HIGH, "NAND X,0");
    }

    private static void testNotFromNand() {
        LogicCircuit circuit = new LogicCircuit();
        Signal input = circuit.signal("NOT/A");
        Signal output = circuit.signal("NOT/OUT");
        circuit.nand("NOT/NAND0", input, input, output);

        CircuitSimulator simulator = new CircuitSimulator(circuit);
        simulator.scheduleAll();
        simulator.runUntilStable(100);

        simulator.drive(input, LogicValue.LOW);
        simulator.runUntilStable(100);
        check(output.value() == LogicValue.HIGH, "NOT 0");

        simulator.drive(input, LogicValue.HIGH);
        simulator.runUntilStable(100);
        check(output.value() == LogicValue.LOW, "NOT 1");
    }

    private static void testAndFromNandOnly() {
        LogicCircuit circuit = new LogicCircuit();
        Signal a = circuit.signal("AND/A");
        Signal b = circuit.signal("AND/B");
        Signal nandOut = circuit.signal("AND/NAND_OUT");
        Signal out = circuit.signal("AND/OUT");
        circuit.nand("AND/NAND0", a, b, nandOut);
        circuit.nand("AND/NAND1", nandOut, nandOut, out);

        CircuitSimulator simulator = new CircuitSimulator(circuit);
        simulator.scheduleAll();
        simulator.runUntilStable(100);

        for (int av = 0; av <= 1; av++) {
            for (int bv = 0; bv <= 1; bv++) {
                simulator.drive(a, LogicValue.fromBoolean(av == 1));
                simulator.drive(b, LogicValue.fromBoolean(bv == 1));
                simulator.runUntilStable(100);
                boolean expected = av == 1 && bv == 1;
                check(out.value().asBoolean() == expected, "AND " + av + "," + bv);
            }
        }
    }

    private static void testBusSplitMerge() {
        LogicCircuit circuit = new LogicCircuit();
        Bus data = Bus.create(circuit, "DATA", 16);
        Bus low = data.slice("LOW", 0, 8);
        Bus high = data.slice("HIGH", 8, 8);
        Bus merged = Bus.merge("MERGED", low, high);
        CircuitSimulator simulator = new CircuitSimulator(circuit);

        data.driveUnsigned(0xA55AL, simulator);
        check(data.readUnsigned() == 0xA55AL, "bus read");
        check(low.readUnsigned() == 0x5AL, "low byte split");
        check(high.readUnsigned() == 0xA5L, "high byte split");
        check(merged.readUnsigned() == 0xA55AL, "bus merge");
    }

    private static void testTraceRingBuffer() {
        LogicCircuit circuit = new LogicCircuit();
        Signal input = circuit.signal("TRACE/A", LogicValue.LOW);
        TraceRecorder trace = new TraceRecorder(3);
        CircuitSimulator simulator = new CircuitSimulator(circuit, trace);

        simulator.drive(input, LogicValue.HIGH);
        simulator.drive(input, LogicValue.LOW);
        simulator.drive(input, LogicValue.HIGH);
        simulator.drive(input, LogicValue.LOW);

        check(trace.size() == 3, "trace capacity");
        check(trace.snapshot().get(0).sequence() == 2L, "trace overwrites oldest event");
        check(trace.snapshot().get(2).sequence() == 4L, "trace keeps newest event");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
