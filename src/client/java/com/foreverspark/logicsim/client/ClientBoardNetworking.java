package com.foreverspark.logicsim.client;

import com.foreverspark.logicsim.block.CircuitBlockEntity;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.ExternalDeviceDescriptor;
import com.foreverspark.logicsim.network.CircuitBoardPayload;
import com.foreverspark.logicsim.network.RequestCircuitBoardPayload;
import com.foreverspark.logicsim.network.SaveCircuitBoardPayload;
import com.foreverspark.logicsim.platform.ClientEditorBridge;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.Arrays;
import java.util.List;

/** Client/server sync for the editable board and its currently connected physical peripherals. */
public final class ClientBoardNetworking {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static BlockPos pendingPos;
    private static String pendingBoardJson;
    private static List<ExternalDeviceDescriptor> pendingDevices = List.of();

    private ClientBoardNetworking() {}

    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(CircuitBoardPayload.TYPE, (payload, context) ->
                Minecraft.getInstance().execute(() -> {
                    pendingPos = payload.pos().immutable();
                    pendingBoardJson = payload.boardJson() == null ? "" : payload.boardJson();
                    pendingDevices = parseDevices(payload.devicesJson());
                    ClientEditorBridge.openEditor(payload.pos());
                }));
    }

    public static void requestOpen(BlockPos pos) { if (pos != null) ClientPlayNetworking.send(new RequestCircuitBoardPayload(pos)); }

    public static CircuitDocument consumePendingBoard(BlockPos pos) {
        if (pos == null || pendingPos == null || !pendingPos.equals(pos)) return null;
        String json = pendingBoardJson;
        pendingBoardJson = null;
        try {
            CircuitDocument board = json == null || json.isBlank() ? new CircuitDocument() : GSON.fromJson(json, CircuitDocument.class);
            if (board == null) board = new CircuitDocument();
            board.normalize();
            return board;
        } catch (RuntimeException invalid) {
            return new CircuitDocument();
        }
    }

    public static boolean hasPendingDeviceSnapshot(BlockPos pos) {
        return pos != null && pendingPos != null && pendingPos.equals(pos) && pendingBoardJson == null;
    }

    public static List<ExternalDeviceDescriptor> consumePendingDevices(BlockPos pos) {
        if (!hasPendingDeviceSnapshot(pos)) return List.of();
        List<ExternalDeviceDescriptor> result = pendingDevices;
        pendingDevices = List.of();
        pendingPos = null;
        return result;
    }

    public static void save(BlockPos pos, CircuitDocument board) {
        if (pos == null || board == null) return;
        board.normalize();
        String json = GSON.toJson(board);
        if (json.length() > CircuitBlockEntity.MAX_BOARD_JSON) return;
        ClientPlayNetworking.send(new SaveCircuitBoardPayload(pos, json));
    }

    private static List<ExternalDeviceDescriptor> parseDevices(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            ExternalDeviceDescriptor[] devices = GSON.fromJson(json, ExternalDeviceDescriptor[].class);
            return devices == null ? List.of() : List.copyOf(Arrays.asList(devices));
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }
}
