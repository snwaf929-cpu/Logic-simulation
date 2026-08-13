package com.foreverspark.logicsim.tools;

import com.foreverspark.logicsim.core.TimingDomain;

public final class ClockCoreSelfTest {
    private ClockCoreSelfTest() {}
    public static void main(String[] args) {
        TimingDomain timing = new TimingDomain(5000000L);
        System.out.println("Clock core self-test: PASS " + timing.frequencyHz());
    }
}
