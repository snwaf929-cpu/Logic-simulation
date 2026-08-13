package com.foreverspark.logicsim.tools;

import com.foreverspark.logicsim.core.CircuitSimulator;
import com.foreverspark.logicsim.core.LogicCircuit;
import com.foreverspark.logicsim.core.LogicValue;
import com.foreverspark.logicsim.core.Signal;
import com.foreverspark.logicsim.core.TimingSignalDriver;

public final class ClockSignalChecks {
    private ClockSignalChecks() {}

    public static void main(String[] args) {
        LogicCircuit circuit = new LogicCircuit();
        Signal clock = circuit.signal("CLOCK", LogicValue.LOW);
        Signal inverted = circuit.signal("INVERTED");
        circuit.nand("NOT", clock, clock, inverted);
        CircuitSimulator simulator = new CircuitSimulator(circuit);
        simulator.scheduleAll();
        simulator.runUntilStable(100L);

        TimingSignalDriver driver = new TimingSignalDriver(10L, simulator, clock, 100L);
        require(inverted.value() == LogicValue.HIGH, "low clock settles through NAND");
        driver.stepEdges(1L);
        require(clock.value() == LogicValue.HIGH, "first edge drives clock high");
        require(inverted.value() == LogicValue.LOW, "NAND settles after rising edge");
        driver.stepEdges(1L);
        require(clock.value() == LogicValue.LOW, "second edge drives clock low");
        require(inverted.value() == LogicValue.HIGH, "NAND settles after falling edge");
        System.out.println("Virtual clock signal self-test: PASS");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
