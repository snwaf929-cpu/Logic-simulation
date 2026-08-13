package com.foreverspark.logicsim.interconnect;

import com.foreverspark.logicsim.editor.model.PortDirection;
import com.foreverspark.logicsim.editor.model.PortSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Topology model for future world Wire / Bus Cable blocks.
 *
 * This intentionally performs no implicit width conversion and allows only one driver per
 * input port. Shared tri-state buses and arbitration are a later layer rather than hidden magic.
 */
public final class InterconnectGraph {
    private final Map<String, InterconnectDevice> devices = new LinkedHashMap<>();
    private final List<CableConnection> connections = new ArrayList<>();

    public void registerDevice(InterconnectDevice device) {
        if (devices.containsKey(device.id())) {
            throw new IllegalArgumentException("Device already registered: " + device.id());
        }
        devices.put(device.id(), device);
    }

    public void removeDevice(String deviceId) {
        devices.remove(deviceId);
        connections.removeIf(connection -> connection.source().deviceId().equals(deviceId)
                || connection.target().deviceId().equals(deviceId));
    }

    public InterconnectDevice device(String deviceId) {
        InterconnectDevice device = devices.get(deviceId);
        if (device == null) {
            throw new IllegalArgumentException("Unknown device: " + deviceId);
        }
        return device;
    }

    public CableConnection connect(DevicePortAddress source, DevicePortAddress target, CableKind kind) {
        if (source.direction() != PortDirection.OUTPUT) {
            throw new IllegalArgumentException("Cable source must be an OUTPUT port");
        }
        if (target.direction() != PortDirection.INPUT) {
            throw new IllegalArgumentException("Cable target must be an INPUT port");
        }

        PortSpec sourcePort = device(source.deviceId()).port(source);
        PortSpec targetPort = device(target.deviceId()).port(target);
        if (sourcePort.width() != targetPort.width()) {
            throw new IllegalArgumentException("Width mismatch: " + sourcePort.width() + "-bit -> " + targetPort.width() + "-bit");
        }

        kind.validateWidth(sourcePort.width());
        if (connections.stream().anyMatch(connection -> connection.target().equals(target))) {
            throw new IllegalArgumentException("Input port is already driven: " + target.deviceId() + "." + targetPort.name());
        }

        CableConnection connection = new CableConnection(source, target, kind, sourcePort.width());
        connections.add(connection);
        return connection;
    }

    public boolean disconnect(CableConnection connection) {
        return connections.remove(connection);
    }

    public List<CableConnection> connections() {
        return Collections.unmodifiableList(connections);
    }

    public List<CableConnection> connectionsFor(String deviceId) {
        return connections.stream()
                .filter(connection -> connection.source().deviceId().equals(deviceId)
                        || connection.target().deviceId().equals(deviceId))
                .toList();
    }

    public int deviceCount() {
        return devices.size();
    }
}
