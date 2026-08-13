package com.foreverspark.logicsim.editor.model;

public record WireConnection(int sourceNodeId, int sourcePort, int targetNodeId, int targetPort) {
}
