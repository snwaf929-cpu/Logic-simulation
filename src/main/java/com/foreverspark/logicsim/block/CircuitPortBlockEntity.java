package com.foreverspark.logicsim.block;

import com.foreverspark.logicsim.editor.model.PortDirection;
import com.foreverspark.logicsim.editor.model.PortSpec;
import com.foreverspark.logicsim.interconnect.PhysicalPortBinding;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

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
}
