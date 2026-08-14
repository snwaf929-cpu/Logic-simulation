package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CircuitCanvasWidget.class, priority = 1550)
public abstract class CircuitCanvasLowZoomTitleMixin {
    @Shadow private CircuitDocument document;
    @Shadow private double zoom;
    @Shadow private int screenX(double x){throw new AssertionError();}
    @Shadow private int screenY(double y){throw new AssertionError();}
    @Shadow private double nodeWidth(EditorNode n){throw new AssertionError();}
    @Shadow private double nodeHeight(EditorNode n){throw new AssertionError();}
    @Shadow private boolean isNodeSelected(int id){throw new AssertionError();}
    @Shadow private int nodeAccent(EditorNode n){throw new AssertionError();}
    @Shadow private Font font(){throw new AssertionError();}

    @Inject(method="drawNode",at=@At("HEAD"),cancellable=true)
    private void logic$dynamicText(GuiGraphicsExtractor g,EditorNode n,CallbackInfo ci){
        if(zoom>=.70||n.kind==NodeKind.INPUT||n.kind==NodeKind.OUTPUT)return;
        int x=screenX(n.x),y=screenY(n.y),w=Math.max(6,(int)Math.round(nodeWidth(n)*zoom)),h=Math.max(6,(int)Math.round(nodeHeight(n)*zoom));
        int accent=n.kind==NodeKind.BUS?0xFF4B5662:n.kind==NodeKind.CONSTANT&&n.randomSource?0xFFB06CE8:nodeAccent(n);
        g.fill(x,y,x+w,y+h,n.kind==NodeKind.BUS?0xFF080B0F:0xF0191F26);
        g.outline(x,y,w,h,isNodeSelected(n.id)?0xFFFFFFFF:accent);
        String title=n.kind==NodeKind.BUS?Integer.toString(n.width):n.kind==NodeKind.CUSTOM_CHIP?n.displayName():switch(n.kind){case NAND->"NAND";case CONSTANT->n.randomSource?"RND "+n.randomChancePercent+"%":n.clockSource?"CLK "+EditorNode.formatFrequency(n.clockFrequencyHz):"CONST";case PROBE->"PROBE";case SPLITTER->"SPLIT "+n.width;case MERGER->"MERGE "+n.width;default->n.displayName();};
        logic$text(g,title,x+w/2,y+Math.max(2,h/2-4),Math.max(4,w-4),0xFFF2F5F8);
        ci.cancel();
    }

    @Inject(method="extractWidgetRenderState",at=@At("TAIL"))
    private void logic$sourceTitleAtNormalZoom(GuiGraphicsExtractor g,int mouseX,int mouseY,float delta,CallbackInfo ci){
        if(zoom<.70)return;
        for(EditorNode n:document.nodes){
            if(n.kind!=NodeKind.CONSTANT||(!n.clockSource&&!n.randomSource))continue;
            int x=screenX(n.x),y=screenY(n.y),w=Math.max(18,(int)Math.round(nodeWidth(n)*zoom));
            int top=y+Math.max(6,(int)Math.round(7*zoom));
            int bottom=y+Math.max(18,(int)Math.round(20*zoom));
            g.fill(x+3,top,x+w-3,bottom,0xFF191F26);
            String title=n.randomSource?"RND "+n.randomChancePercent+"%":"CLK "+EditorNode.formatFrequency(n.clockFrequencyHz);
            logic$text(g,title,x+w/2,top+Math.max(1,(bottom-top-8)/2),Math.max(6,w-8),0xFFF2F5F8);
        }
    }

    @Unique private void logic$text(GuiGraphicsExtractor g,String t,int cx,int y,int max,int color){
        int rw=Math.max(1,font().width(t));
        float s=(float)Math.max(.22,Math.min(zoom,Math.min(1.0,max/(double)rw)));
        g.pose().pushMatrix();g.pose().scale(s);
        g.text(font(),t,Math.round(cx/s-rw/2f),Math.round(y/s),color,false);
        g.pose().popMatrix();
    }
}
