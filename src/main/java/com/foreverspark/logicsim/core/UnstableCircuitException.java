package com.foreverspark.logicsim.core;

public final class UnstableCircuitException extends RuntimeException {
    public UnstableCircuitException(long maxEvaluations) {
        super("Circuit did not settle within " + maxEvaluations + " gate evaluations");
    }
}
