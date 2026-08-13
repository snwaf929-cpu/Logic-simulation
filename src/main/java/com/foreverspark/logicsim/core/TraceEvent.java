package com.foreverspark.logicsim.core;

/** One signal transition in the event trace. */
public record TraceEvent(
        long sequence,
        int signalId,
        String signalPath,
        LogicValue from,
        LogicValue to
) {}
