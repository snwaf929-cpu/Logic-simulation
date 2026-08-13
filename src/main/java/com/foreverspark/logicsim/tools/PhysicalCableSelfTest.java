package com.foreverspark.logicsim.tools;

import com.foreverspark.logicsim.interconnect.CableKind;

public final class PhysicalCableSelfTest {
    private PhysicalCableSelfTest() {}

    public static void main(String[] args) {
        check(CableKind.SIGNAL.supportsWidth(1), "1-bit signal wire supported");
        check(!CableKind.SIGNAL.supportsWidth(2), "signal wire rejects 2-bit");
        for (int width : new int[]{2, 4, 8, 16, 32}) {
            check(CableKind.BUS.supportsWidth(width), width + "-bit bus cable supported");
        }
        check(!CableKind.BUS.supportsWidth(1), "bus rejects 1-bit; use signal wire");
        check(!CableKind.BUS.supportsWidth(64), "physical bus currently stops at 32-bit");
        System.out.println("Physical cable width self-test: PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
