package com.foreverspark.logicsim.platform;

import net.minecraft.core.BlockPos;
import java.util.Objects;

public final class ClientEditorBridge {
    private static Runnable editorOpener = () -> {};
    private static BlockPos activeCircuitPos;

    private ClientEditorBridge() {}

    public static void installEditorOpener(Runnable opener) {
        editorOpener = Objects.requireNonNull(opener, "opener");
    }

    public static void openEditor() {
        activeCircuitPos = null;
        editorOpener.run();
    }

    public static void openEditor(BlockPos pos) {
        activeCircuitPos = pos == null ? null : pos.immutable();
        editorOpener.run();
    }

    public static BlockPos activeCircuitPos() {
        return activeCircuitPos;
    }
}
