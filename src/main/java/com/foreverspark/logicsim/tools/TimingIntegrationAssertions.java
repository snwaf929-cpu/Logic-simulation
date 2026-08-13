package com.foreverspark.logicsim.tools;

final class TimingIntegrationAssertions {
    private TimingIntegrationAssertions() {}

    static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
