package com.foreverspark.logicsim.block;
import com.foreverspark.logicsim.interconnect.CableKind;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
public final class DisplayPorts {
 public enum Port { X,Y,COLOR,WRITE,CLEAR,NONE } private DisplayPorts(){}
 public static Direction front(BlockState s){return s.hasProperty(DisplayBlock.FACING)?s.getValue(DisplayBlock.FACING):Direction.NORTH;}
 public static Direction left(BlockState s){return left(front(s));} public static Direction right(BlockState s){return left(s).getOpposite();} public static Direction back(BlockState s){return front(s).getOpposite();}
 public static Port portAt(BlockState s,Direction f){if(f==front(s))return Port.NONE;if(f==back(s))return Port.COLOR;if(f==left(s))return Port.X;if(f==right(s))return Port.Y;if(f==Direction.UP)return Port.WRITE;if(f==Direction.DOWN)return Port.CLEAR;return Port.NONE;}
 public static int widthAt(BlockState s,Direction f){return switch(portAt(s,f)){case X,Y,COLOR->16;case WRITE,CLEAR->1;case NONE->0;};}
 public static boolean accepts(BlockState s,Direction f,CableKind k,int w){return switch(portAt(s,f)){case X,Y,COLOR->k==CableKind.BUS&&(w==2||w==4||w==8||w==16||w==32||w==64);case WRITE,CLEAR->k==CableKind.SIGNAL&&w==1;case NONE->false;};}
 private static Direction left(Direction f){return switch(f){case NORTH->Direction.WEST;case SOUTH->Direction.EAST;case WEST->Direction.SOUTH;case EAST->Direction.NORTH;default->Direction.WEST;};}
}
