package com.foreverspark.logicsim.client;

import com.foreverspark.logicsim.client.screen.CircuitPortBindingScreen;
import com.foreverspark.logicsim.interconnect.CircuitPortCatalog;
import com.foreverspark.logicsim.network.CircuitPortsPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

public final class ClientPortNetworking {
    private ClientPortNetworking() {}

    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(CircuitPortsPayload.TYPE, (payload, context) -> {
            CircuitPortCatalog catalog;
            try {
                catalog = CircuitPortCatalog.fromJson(payload.catalogJson());
            } catch (RuntimeException invalid) {
                return;
            }
            Minecraft.getInstance().execute(() ->
                    Minecraft.getInstance().gui.setScreen(new CircuitPortBindingScreen(payload.socketPos(), payload.circuitPos(), catalog))
            );
        });
    }
}
