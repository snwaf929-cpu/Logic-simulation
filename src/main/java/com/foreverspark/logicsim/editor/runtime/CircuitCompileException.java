package com.foreverspark.logicsim.editor.runtime;

public final class CircuitCompileException extends RuntimeException {
    public CircuitCompileException(String message) {
        super(message);
    }

    public CircuitCompileException(String message, Throwable cause) {
        super(message, cause);
    }
}
