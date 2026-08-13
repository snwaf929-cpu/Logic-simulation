package com.foreverspark.logicsim.tools;

import com.foreverspark.logicsim.core.TimingDomain;

public final class ClockMathChecks {
    private ClockMathChecks() {}

    public static void main(String[] args) {
        run();
        System.out.println("Virtual clock math self-test: PASS");
    }

    static void run() {
        TimingDomain timing = new TimingDomain(5000000L);
        long[] count = {0L};
        long emitted = timing.advanceNanos(1000000L, 20000L, high -> count[0]++);
        require(emitted == 10000L, "5 MHz edge count");
        require(count[0] == 10000L, "sink edge count");
        require(timing.pendingEdges() == 0L, "no backlog within budget");

        TimingDomain backlog = new TimingDomain(5000000L);
        long first = backlog.advanceNanos(50000000L, 25000L, high -> {});
        require(first == 25000L, "budget applied");
        require(backlog.pendingEdges() == 475000L, "backlog preserved");
        backlog.advanceNanos(0L, 500000L, high -> {});
        require(backlog.pendingEdges() == 0L, "backlog drained");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
