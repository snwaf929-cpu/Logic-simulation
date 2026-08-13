package com.foreverspark.logicsim.block;

import com.foreverspark.logicsim.editor.model.PortDirection;
import com.foreverspark.logicsim.editor.model.PortSpec;
import com.foreverspark.logicsim.interconnect.PhysicalPortBinding;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public final class CircuitPortBlockEntity extends BlockEntity {
    private BlockPos circuitPos;
    private String portName = "";
    private PortDirection direction = PortDirection.INPUT;
    private int width = 1;

    public CircuitPortBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CIRCUIT_PORT, pos, state);
    }

    public void bind(BlockPos circuitPos, PortSpec port) {
        PhysicalPortBinding checked = new PhysicalPortBinding(port);
        this.circuitPos = circuitPos.immutable();
        this.portName = checked.portName();
        this.direction = checked.direction();
        this.width = checked.width();
        setChanged();
    }

    public boolean isBound() { return circuitPos != null && !portName.isBlank(); }
    public BlockPos circuitPos() { return circuitPos; }
    public String portName() { return portName; }
    public PortDirection direction() { return direction; }
    public int width() { return width; }

    public PhysicalPortBinding binding() {
        return isBound() ? new PhysicalPortBinding(portName, direction, width) : null;
    }

    public boolean accepts(CableBlock cable) {
        PhysicalPortBinding binding = binding();
        return binding != null && cable != null && binding.accepts(cable.cableKind(), cable.bitWidth());
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        if (isBound()) {
            output.putLong("circuitPos", circuitPos.asLong());
            output.putString("portName", portName);
            output.putString("portDirection", direction.name());
            output.putInt("portWidth", width);
        }
        super.saveAdditional(output);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        long packed = input.getLongOr("circuitPos", Long.MIN_VALUE);
        if (packed == Long.MIN_VALUE) {
            clearBinding();
            return;
        }
        BlockPos loadedPos = BlockPos.of(packed);
        String loadedName = input.getStringOr("portName", "");
        String loadedDirection = input.getStringOr("portDirection", PortDirection.INPUT.name());
        int loadedWidth = input.getIntOr("portWidth", 1);
        try {
            PortDirection parsedDirection = PortDirection.valueOf(loadedDirection);
            PhysicalPortBinding checked = new PhysicalPortBinding(loadedName, parsedDirection, loadedWidth);
            circuitPos = loadedPos;
            portName = checked.portName();
            direction = checked.direction();
            width = checked.width();
        } catch (IllegalArgumentException invalid) {
            clearBinding();
        }
    }

    private void clearBinding() {
        circuitPos = null;
        portName = "";
        direction = PortDirection.INPUT;
        width = 1;
    }
}
