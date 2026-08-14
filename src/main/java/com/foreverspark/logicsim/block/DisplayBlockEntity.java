package com.foreverspark.logicsim.block;

import com.foreverspark.logicsim.display.DisplayFramebuffer;
import com.foreverspark.logicsim.interconnect.CableKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

public final class DisplayBlockEntity extends BlockEntity {
    public static final int MAX_WIDTH=64,MAX_HEIGHT=64,DEFAULT_PIXEL_WIDTH=32,DISPLAY_BUS_WIDTH=64;
    public static final int OP_NOP=0,OP_PIXEL=1,OP_CLEAR=2;
    private static final int[] PIXEL_WIDTHS={1,2,4,8,16,32,64};
    private static final int MAX_WALL_BLOCKS=4096;
    private final DisplayFramebuffer framebuffer=new DisplayFramebuffer(MAX_WIDTH,MAX_HEIGHT);
    private int pixelWidth=DEFAULT_PIXEL_WIDTH;
    private boolean syncPending;
    private long lastWallCommand=Long.MIN_VALUE;

    public DisplayBlockEntity(BlockPos pos,BlockState state){super(ModBlockEntities.DISPLAY,pos,state);}
    public DisplayFramebuffer framebuffer(){return framebuffer;}
    public int pixelWidth(){return pixelWidth;}
    public int pixelHeight(){return pixelWidth;}
    public static int pixelHeightFor(int width){return width;}
    public static int nextPixelWidth(int current){for(int i=0;i<PIXEL_WIDTHS.length;i++)if(PIXEL_WIDTHS[i]==current)return PIXEL_WIDTHS[(i+1)%PIXEL_WIDTHS.length];return DEFAULT_PIXEL_WIDTH;}
    public static long pixelCommand(int x,int y,int rgb565,int sequence){return((long)(sequence&255)<<56)|((long)OP_PIXEL<<48)|((long)(y&65535)<<32)|((long)(x&65535)<<16)|(rgb565&65535L);}
    public static long clearCommand(int sequence){return((long)(sequence&255)<<56)|((long)OP_CLEAR<<48);}

    public void setPixelWidth(int width){int normalized=normalizePixelWidth(width);if(normalized==pixelWidth)return;pixelWidth=normalized;framebuffer.clear(0);framebuffer.markAllDirty();lastWallCommand=Long.MIN_VALUE;setChanged();}
    public static int setWallPixelWidth(Level level,BlockPos start,BlockState startState,int width){DisplayWall wall=collectWall(level,start,startState);if(wall==null)return 0;int changed=0;for(BlockPos pos:wall.blocks)if(level.getBlockEntity(pos)instanceof DisplayBlockEntity display){display.setPixelWidth(width);changed++;}return changed;}
    public static void tick(Level level,BlockPos pos,BlockState state,DisplayBlockEntity display){if(!level.isClientSide())display.flushClientSync(level);}

    public void acceptCableValue(Direction face,long value){if(level==null||level.isClientSide())return;if(!DisplayPorts.accepts(getBlockState(),face,CableKind.BUS,DISPLAY_BUS_WIDTH))return;routeWallCommand(level,worldPosition,value);}

    private static void routeWallCommand(Level level,BlockPos touchedTile,long command){
        DisplayWall wall=collectWall(level,touchedTile,level.getBlockState(touchedTile));if(wall==null||wall.blocks.isEmpty())return;
        BlockPos controllerPos=wall.blocks.stream().min(Comparator.comparingLong(BlockPos::asLong)).orElse(touchedTile);
        if(!(level.getBlockEntity(controllerPos)instanceof DisplayBlockEntity controller)||controller.lastWallCommand==command)return;
        controller.lastWallCommand=command;
        int opcode=(int)((command>>>48)&255L);
        if(opcode==OP_NOP)return;
        if(opcode==OP_CLEAR){for(BlockPos pos:wall.blocks)if(level.getBlockEntity(pos)instanceof DisplayBlockEntity display)display.clearScreen();return;}
        if(opcode!=OP_PIXEL)return;
        int globalX=(int)((command>>>16)&65535L),globalY=(int)((command>>>32)&65535L),rgb565=(int)(command&65535L),density=controller.pixelWidth();
        int columns=wall.maxHorizontal-wall.minHorizontal+1,rows=wall.maxY-wall.minY+1;
        if(globalX>=columns*density||globalY>=rows*density)return;
        int targetHorizontal=wall.minHorizontal+globalX/density,targetY=wall.maxY-globalY/density;
        BlockPos target=touchedTile.offset(wall.right.getStepX()*targetHorizontal,targetY-touchedTile.getY(),wall.right.getStepZ()*targetHorizontal);
        if(!wall.blocks.contains(target))return;
        if(level.getBlockEntity(target)instanceof DisplayBlockEntity display)display.writePixel(globalX%density,globalY%density,rgb565);
    }

    private static DisplayWall collectWall(Level level,BlockPos start,BlockState startState){
        if(level==null||start==null||!(startState.getBlock()instanceof DisplayBlock))return null;
        Direction facing=DisplayPorts.front(startState),left=DisplayPorts.left(startState),right=left.getOpposite();
        ArrayDeque<BlockPos> queue=new ArrayDeque<>();Set<BlockPos> seen=new HashSet<>(),blocks=new HashSet<>();queue.add(start.immutable());
        int minH=0,maxH=0,minY=start.getY(),maxY=start.getY();
        while(!queue.isEmpty()&&seen.size()<MAX_WALL_BLOCKS){BlockPos pos=queue.removeFirst();if(!seen.add(pos))continue;BlockState state=level.getBlockState(pos);if(!(state.getBlock()instanceof DisplayBlock)||DisplayPorts.front(state)!=facing)continue;blocks.add(pos.immutable());int dx=pos.getX()-start.getX(),dz=pos.getZ()-start.getZ(),h=dx*right.getStepX()+dz*right.getStepZ();minH=Math.min(minH,h);maxH=Math.max(maxH,h);minY=Math.min(minY,pos.getY());maxY=Math.max(maxY,pos.getY());queue.add(pos.relative(left));queue.add(pos.relative(right));queue.add(pos.relative(Direction.UP));queue.add(pos.relative(Direction.DOWN));}
        return new DisplayWall(Set.copyOf(blocks),right,minH,maxH,minY,maxY);
    }

    public void writePixel(int x,int y,int rgb565){if(x<0||x>=pixelWidth||y<0||y>=pixelWidth)return;long before=framebuffer.revision();framebuffer.writePixel(x,y,rgb565);if(framebuffer.revision()!=before)setChanged();}
    public void clearScreen(){long before=framebuffer.revision();framebuffer.clear(0);if(framebuffer.revision()!=before)setChanged();}
    @Override protected void saveAdditional(ValueOutput output){output.putInt("pixelWidth",pixelWidth);for(int i=0;i<pixelWidth*pixelWidth;i++){int value=framebuffer.pixelRgb565(i%pixelWidth,i/pixelWidth);if(value!=0)output.putInt("p"+i,value);}super.saveAdditional(output);}
    @Override protected void loadAdditional(ValueInput input){super.loadAdditional(input);pixelWidth=normalizePixelWidth(input.getIntOr("pixelWidth",DEFAULT_PIXEL_WIDTH));framebuffer.clear(0);for(int i=0;i<pixelWidth*pixelWidth;i++){int value=input.getIntOr("p"+i,0);if(value!=0)framebuffer.writePixel(i%pixelWidth,i/pixelWidth,value);}framebuffer.markAllDirty();lastWallCommand=Long.MIN_VALUE;}
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registryLookup){return saveWithoutMetadata(registryLookup);}
    @Override public Packet<ClientGamePacketListener> getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
    @Override public void setChanged(){super.setChanged();syncPending=true;}
    private void flushClientSync(Level level){if(!syncPending)return;syncPending=false;BlockState state=getBlockState();level.sendBlockUpdated(worldPosition,state,state,Block.UPDATE_ALL);}
    private static int normalizePixelWidth(int width){for(int candidate:PIXEL_WIDTHS)if(candidate==width)return candidate;return DEFAULT_PIXEL_WIDTH;}
    private record DisplayWall(Set<BlockPos> blocks,Direction right,int minHorizontal,int maxHorizontal,int minY,int maxY){}
}
