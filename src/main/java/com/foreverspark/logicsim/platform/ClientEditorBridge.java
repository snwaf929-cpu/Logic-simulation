package com.foreverspark.logicsim.platform;

import net.minecraft.core.BlockPos;
import java.util.Objects;
import java.util.function.Consumer;

public final class ClientEditorBridge {
    private static Consumer<BlockPos> editorOpener = ignored -> {};
    private ClientEditorBridge() {}
    public static void installEditorOpener(Consumer<BlockPos> opener) { editorOpener = Objects.requireNonNull(opener, "opener"); }
    public static void openEditor(BlockPos pos) { editorOpener.accept(pos == null ? null : pos.immutable()); }
}
