package com.foreverspark.logicsim.client;

import com.foreverspark.logicsim.client.screen.CircuitEditorScreen;
import com.foreverspark.logicsim.platform.ClientEditorBridge;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public final class CircuitModeHotkey {
    private static boolean wasDown;

    private CircuitModeHotkey() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean down = isPressed(client);
            if (down && !wasDown && ClientEditorBridge.activeCircuitPos() != null) {
                ClientProgramUploader.toggleRedstoneClockGate(ClientEditorBridge.activeCircuitPos());
            }
            wasDown = down;
        });
    }

    private static boolean isPressed(Minecraft client) {
        if (!(client.screen instanceof CircuitEditorScreen)) return false;
        long window = client.getWindow().handle();
        boolean ctrl = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
        return ctrl && GLFW.glfwGetKey(window, GLFW.GLFW_KEY_R) == GLFW.GLFW_PRESS;
    }
}
