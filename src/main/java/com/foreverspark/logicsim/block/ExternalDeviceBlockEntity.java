package com.foreverspark.logicsim.block;

import com.foreverspark.logicsim.editor.model.ExternalDeviceType;
import com.foreverspark.logicsim.editor.model.PortDirection;
import com.foreverspark.logicsim.editor.model.PortSpec;
import com.foreverspark.logicsim.interconnect.CableRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * World identity/state for a physical peripheral. Host behavior is intentionally separate from logic simulation.
 * Inputs can be observed from cables or from an explicitly bound BOARD DEVICE node; unavailable host-driven outputs
 * remain electrically UNKNOWN in the schematic until that host feature exists.
 */
public final class ExternalDeviceBlockEntity extends BlockEntity {
    private String stableId = UUID.randomUUID().toString();
    private final Map<String, Long> observedInputs = new LinkedHashMap<>();

    public ExternalDeviceBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.EXTERNAL_DEVICE, pos, state);
    }

    public String stableId() { return stableId; }

    public ExternalDeviceType deviceType() {
        return getBlockState().getBlock() instanceof ExternalDeviceBlock block ? block.deviceType() : ExternalDeviceType.STORAGE;
    }

    public long observedInput(String name) { return observedInputs.getOrDefault(name, 0L); }

    /** Server-thread delivery from one explicit BOARD DEVICE binding. */
    public void acceptSchematicInput(String name, long value) {
        if (name == null || name.isBlank()) return;
        PortSpec matched = null;
        for (PortSpec port : deviceType().inputs()) {
            if (port.name().equalsIgnoreCase(name)) {
                matched = port;
                break;
            }
        }
        if (matched == null) return;
        long normalized = matched.width() >= 64 ? value : value & ((1L << matched.width()) - 1L);
        Long old = observedInputs.put(matched.name(), normalized);
        if (old == null || old.longValue() != normalized) setChanged();
    }

    public static void tick(Level level, BlockPos pos, BlockState state, ExternalDeviceBlockEntity device) {
        if (level.isClientSide() || !(state.getBlock() instanceof ExternalDeviceBlock block)) return;
        for (Direction face : Direction.values()) {
            PortSpec port = block.portOnFace(face);
            if (port == null || port.direction() != PortDirection.INPUT) continue;
            BlockPos cablePos = pos.relative(face);
            BlockState cableState = level.getBlockState(cablePos);
            if (!(cableState.getBlock() instanceof CableBlock cable) || !block.accepts(face, cable)) continue;
            long value = CableRuntime.value(level, cablePos);
            Long old = device.observedInputs.put(port.name(), value);
            if (old == null || old.longValue() != value) device.setChanged();
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        output.putString("deviceId", stableId);
        for (Map.Entry<String, Long> entry : observedInputs.entrySet()) {
            output.putLong("input_" + entry.getKey(), entry.getValue());
        }
        super.saveAdditional(output);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        String loaded = input.getStringOr("deviceId", "").trim();
        if (!loaded.isEmpty()) stableId = loaded;
        observedInputs.clear();
        ExternalDeviceType type = deviceType();
        for (PortSpec port : type.inputs()) {
            observedInputs.put(port.name(), input.getLongOr("input_" + port.name(), 0L));
        }
    }
}
