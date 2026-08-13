package com.foreverspark.logicsim.tools;

import com.foreverspark.logicsim.core.CircuitSimulator;
import com.foreverspark.logicsim.core.LogicCircuit;
import com.foreverspark.logicsim.core.LogicValue;
import com.foreverspark.logicsim.core.Signal;

public final class BenchmarkMain {
    private static final int DEFAULT_GATE_COUNT = 10_000;
    private static final int DEFAULT_TOGGLES = 2_000;

    private BenchmarkMain() {}

    public static void main(String[] args) {
        int gateCount = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_GATE_COUNT;
        int toggles = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_TOGGLES;

        LogicCircuit circuit = new LogicCircuit();
        Signal source = circuit.signal("BENCH/IN", LogicValue.LOW);
        Signal previous = source;

        for (int i = 0; i < gateCount; i++) {
            Signal output = circuit.signal("BENCH/S" + i, LogicValue.UNKNOWN);
            circuit.nand("BENCH/NAND" + i, previous, previous, output);
            previous = output;
        }

        CircuitSimulator simulator = new CircuitSimulator(circuit);
        simulator.scheduleAll();
        simulator.runUntilStable(Math.max(1L, gateCount * 4L));

        long beforeEvaluations = simulator.totalGateEvaluations();
        long start = System.nanoTime();

        for (int i = 0; i < toggles; i++) {
            simulator.drive(source, (i & 1) == 0 ? LogicValue.HIGH : LogicValue.LOW);
            simulator.runUntilStable(Math.max(1L, gateCount * 2L));
        }

        long elapsedNanos = System.nanoTime() - start;
        long evaluations = simulator.totalGateEvaluations() - beforeEvaluations;
        double seconds = elapsedNanos / 1_000_000_000.0;
        double evalsPerSecond = evaluations / seconds;
        double togglesPerSecond = toggles / seconds;

        System.out.printf("Gates: %,d%n", gateCount);
        System.out.printf("Input toggles: %,d%n", toggles);
        System.out.printf("Gate evaluations: %,d%n", evaluations);
        System.out.printf("Elapsed: %.3f s%n", seconds);
        System.out.printf("Gate evaluations/s: %,.0f%n", evalsPerSecond);
        System.out.printf("End-to-end toggles/s: %,.0f%n", togglesPerSecond);
        System.out.println("Note: this is an early accurate/event-driven benchmark, not the future Turbo compiler.");
    }
}
