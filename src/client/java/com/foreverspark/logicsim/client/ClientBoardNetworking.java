package com.foreverspark.logicsim.client;

import com.foreverspark.logicsim.block.CircuitBlockEntity;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.network.CircuitBoardPayload;
import com.foreverspark.logicsim.network.RequestCircuitBoardPayload;
import com.foreverspark.logicsim.network.SaveCircuitBoardPayload;
import com.foreverspark.logicsim.platform.ClientEditorBridge;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Client/server sync for the editable board that belongs to each physical Circuit Block. */
public final class ClientBoardNetworking {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static BlockPos pendingPos;
    private static String pendingBoardJson;

    private ClientBoardNetworking() {}

    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(CircuitBoardPayload.TYPE, (payload, context) ->
                Minecraft.getInstance().execute(() -> {
                    pendingPos = payload.pos().immutable();
                    pendingBoardJson = payload.boardJson() == null ? "" : payload.boardJson();
                    ClientEditorBridge.openEditor(payload.pos());
                })
        );
    }

    public static void requestOpen(BlockPos pos) {
        if (pos != null) ClientPlayNetworking.send(new RequestCircuitBoardPayload(pos));
    }

    /** Returns a board only for the Circuit Block that triggered this editor open. */
    public static CircuitDocument consumePendingBoard(BlockPos pos) {
        if (pos == null || pendingPos == null || !pendingPos.equals(pos)) return null;
        String json = pendingBoardJson;
        pendingPos = null;
        pendingBoardJson = null;

        CircuitDocument board;
        try {
            board = json == null || json.isBlank() ? new CircuitDocument() : GSON.fromJson(json, CircuitDocument.class);
            if (board == null) board = new CircuitDocument();
            board.normalize();
            return board;
        } catch (RuntimeException invalid) {
            return new CircuitDocument();
        }
    }

    public static void save(BlockPos pos, CircuitDocument board) {
        if (pos == null || board == null) return;
        board.normalize();
        String json = GSON.toJson(board);
        if (json.length() > CircuitBlockEntity.MAX_BOARD_JSON) return;
        ClientPlayNetworking.send(new SaveCircuitBoardPayload(pos, json));
    }
}
