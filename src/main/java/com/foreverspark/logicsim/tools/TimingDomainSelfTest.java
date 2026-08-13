package com.foreverspark.logicsim.tools;

import com.foreverspark.logicsim.core.CircuitSimulator;
import com.foreverspark.logicsim.core.LogicCircuit;
import com.foreverspark.logicsim.core.LogicValue;
import com.foreverspark.logicsim.core.Signal;
import com.foreverspark.logicsim.core.TimingDomain;
import com.foreverspark.logicsim.core.TimingSignalDriver;

public final class TimingDomainSelfTest {
    private TimingDomainSelfTest() {}

    public static void main(String[] args) {
        testFiveMegahertzAccounting();
        testBudgetPreservesBacklog();
        testPauseAndManualStep();
        testTimingSignalSettlesNandLogic();
        System.out.println("Virtual timing + work-budget + signal-driver self-test: PASS");
    }

    private static void testFiveMegahertzAccounting() {
        TimingDomain timing = new TimingDomain(5_000_000L);
        long[] edges = {0L};
        long emitted = timing.advanceNanos(1_000_000L, 20_000L, high -> edges[0]++);
        check(emitted == 10_000L, "5 MHz produces 10,000 edges per millisecond");
        check(edges[0] == 10_000L && timing.pendingEdges() == 0L, "5 MHz edge accounting is exact");
        check(!timing.high(), "an even number of edges returns the square wave low");
    }

    private static void testBudgetPreservesBacklog() {
        TimingDomain timing = new TimingDomain(5_000_000L);
        long emitted = timing.advanceNanos(50_000_000L, 25_000L, high -> {});
        check(emitted == 25_000L, "edge budget limits host work");
        check(timing.pendingEdges() == 475_000L, "unprocessed virtual edges remain as backlog");
        timing.advanceNanos(0L, 500_000L, high -> {});
        check(timing.pendingEdges() == 0L, "backlog can be drained without advancing time");
    }

    private static void testPauseAndManualStep() {
        TimingDomain timing = new TimingDomain(1_000L);
        timing.setRunning(false);
        timing.advanceNanos(1_000_000_000L, 10_000L, high -> {});
        check(timing.totalEdges() == 0L, "paused domain does not accumulate time edges");
        timing.stepEdges(3L, high -> {});
        check(timing.totalEdges() == 3L && timing.high(), "manual edge stepping works while paused");
    }

    private static void testTimingSignalSettlesNandLogic() {
        LogicCircuit circuit = new LogicCircuit();
        Signal timingSignal = circuit.signal("TIMING", LogicValue.LOW);
        Signal inverted = circuit.signal("INVERTED");
        circuit.nand("NOT", timingSignal, timingSignal, inverted);
        CircuitSimulator simulator = new CircuitSimulator(circuit);
        simulator.scheduleAll();
        simulator.runUntilStable(100L);

        TimingSignalDriver driver = new TimingSignalDriver(10L, simulator, timingSignal, 100L);
        check(inverted.value() == LogicValue.HIGH, "low timing level settles through NAND");
        driver.stepEdges(1L);
        check(timingSignal.value() == LogicValue.HIGH && inverted.value() == LogicValue.LOW, "rising timing edge settles downstream NAND");
        driver.stepEdges(1L);
        check(timingSignal.value() == LogicValue.LOW && inverted.value() == LogicValue.HIGH, "falling timing edge settles downstream NAND");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
