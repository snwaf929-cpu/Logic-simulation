package com.foreverspark.logicsim.interconnect;
import com.foreverspark.logicsim.block.CircuitBlockEntity;
import com.foreverspark.logicsim.editor.model.PortSpec;
import java.util.ArrayList;
import java.util.List;
public final class DirectPortResolver{
 private DirectPortResolver(){}
 public static PortSpec unique(CircuitBlockEntity circuit,CableKind kind,int width){if(circuit==null||kind==null)return null;CircuitPortCatalog c=circuit.portCatalog();List<PortSpec>m=new ArrayList<>();collect(m,c.inputs,kind,width);collect(m,c.outputs,kind,width);return m.size()==1?m.getFirst():null;}
 private static void collect(List<PortSpec>m,List<PortSpec>ports,CableKind kind,int width){for(PortSpec p:ports){if(new PhysicalPortBinding(p).accepts(kind,width))m.add(p);if(m.size()>1)return;}}
}
