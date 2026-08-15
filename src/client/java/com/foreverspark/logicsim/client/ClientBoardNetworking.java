package com.foreverspark.logicsim.client;

import com.foreverspark.logicsim.block.CircuitBlockEntity;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.ExternalDeviceDescriptor;
import com.foreverspark.logicsim.network.CircuitBoardPayload;
import com.foreverspark.logicsim.network.ExternalDevicesPayload;
import com.foreverspark.logicsim.network.RequestCircuitBoardPayload;
import com.foreverspark.logicsim.network.RequestExternalDevicesPayload;
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

    private static BlockPos deviceSnapshotPos;
    private static List<ExternalDeviceDescriptor> deviceSnapshot = List.of();
    private static long deviceSnapshotRevision;

    private ClientBoardNetworking() {}

    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(CircuitBoardPayload.TYPE, (payload, context) ->
                Minecraft.getInstance().execute(() -> {
                    pendingPos = payload.pos().immutable();
                    pendingBoardJson = payload.boardJson() == null ? "" : payload.boardJson();
                    publishDeviceSnapshot(payload.pos(), parseDevices(payload.devicesJson()));
                    ClientEditorBridge.openEditor(payload.pos());
                }));

        ClientPlayNetworking.registerGlobalReceiver(ExternalDevicesPayload.TYPE, (payload, context) ->
                Minecraft.getInstance().execute(() ->
                        publishDeviceSnapshot(payload.pos(), parseDevices(payload.devicesJson()))));
    }

    /** Requests the board document and opens the editor when the response arrives. */
    public static void requestOpen(BlockPos pos) {
        if (pos != null) ClientPlayNetworking.send(new RequestCircuitBoardPayload(pos));
    }

    /** Requests only connected peripheral discovery. The response never opens or refocuses a screen. */
    public static void requestDevices(BlockPos pos) {
        if (pos != null) ClientPlayNetworking.send(new RequestExternalDevicesPayload(pos));
    }

    public static CircuitDocument consumePendingBoard(BlockPos pos) {
        if (pos == null || pendingPos == null || !pendingPos.equals(pos)) return null;
        String json = pendingBoardJson;
        pendingBoardJson = null;
        pendingPos = null;
        try {
            CircuitDocument board = json == null || json.isBlank() ? new CircuitDocument() : GSON.fromJson(json, CircuitDocument.class);
            if (board == null) board = new CircuitDocument();
            board.normalize();
            return board;
        } catch (RuntimeException invalid) {
            return new CircuitDocument();
        }
    }

    public static long deviceSnapshotRevision(BlockPos pos) {
        return pos != null && deviceSnapshotPos != null && deviceSnapshotPos.equals(pos) ? deviceSnapshotRevision : -1L;
    }

    public static List<ExternalDeviceDescriptor> latestDevices(BlockPos pos) {
        return pos != null && deviceSnapshotPos != null && deviceSnapshotPos.equals(pos)
                ? deviceSnapshot
                : List.of();
    }

    /** Compatibility for older editor hooks. Snapshots are no longer consumed/destructive. */
    public static boolean hasPendingDeviceSnapshot(BlockPos pos) {
        return deviceSnapshotRevision(pos) >= 0L;
    }

    /** Compatibility for older editor hooks. Returns the latest immutable snapshot without consuming it. */
    public static List<ExternalDeviceDescriptor> consumePendingDevices(BlockPos pos) {
        return latestDevices(pos);
    }

    public static void save(BlockPos pos, CircuitDocument board) {
        if (pos == null || board == null) return;
        board.normalize();
        String json = GSON.toJson(board);
        if (json.length() > CircuitBlockEntity.MAX_BOARD_JSON) return;
        ClientPlayNetworking.send(new SaveCircuitBoardPayload(pos, json));
    }

    private static void publishDeviceSnapshot(BlockPos pos, List<ExternalDeviceDescriptor> devices) {
        deviceSnapshotPos = pos == null ? null : pos.immutable();
        deviceSnapshot = devices == null ? List.of() : List.copyOf(devices);
        deviceSnapshotRevision++;
    }

    private static List<ExternalDeviceDescriptor> parseDevices(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            ExternalDeviceDescriptor[] devices = GSON.fromJson(json, ExternalDeviceDescriptor[].class);
            if (devices == null) return List.of();
            return Arrays.stream(devices).filter(java.util.Objects::nonNull).toList();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }
}
