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

/**
 * Low-zoom fallback renderer whose labels stay at one screen-space font size. Zoom may change the component body,
 * never the text pixels. If a body becomes too narrow, the label is shortened instead of being scaled.
 */
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
    private void logic$screenSpaceLowZoomText(GuiGraphicsExtractor g,EditorNode n,CallbackInfo ci){
        if(n.kind==NodeKind.CUSTOM_CHIP)return;
        if(n.kind==NodeKind.CONSTANT&&n.randomSource)return;
        if(zoom>=.70||n.kind==NodeKind.INPUT||n.kind==NodeKind.OUTPUT)return;
        int x=screenX(n.x),y=screenY(n.y),w=Math.max(6,(int)Math.round(nodeWidth(n)*zoom)),h=Math.max(6,(int)Math.round(nodeHeight(n)*zoom));
        int accent=n.kind==NodeKind.BUS?0xFF4B5662:nodeAccent(n);
        g.fill(x,y,x+w,y+h,n.kind==NodeKind.BUS?0xFF080B0F:0xF0191F26);
        g.outline(x,y,w,h,isNodeSelected(n.id)?0xFFFFFFFF:accent);
        String title=n.kind==NodeKind.BUS?Integer.toString(n.width):switch(n.kind){case NAND->"NAND";case CONSTANT->n.clockSource?"CLK "+EditorNode.formatFrequency(n.clockFrequencyHz):"CONST";case PROBE->"PROBE";case SPLITTER->"SPLIT "+n.width;case MERGER->"MERGE "+n.width;default->n.displayName();};
        logic$text(g,title,x+w/2,y+Math.max(1,h/2-4),Math.max(4,w-4),0xFFF2F5F8);
        ci.cancel();
    }

    @Inject(method="extractWidgetRenderState",at=@At("TAIL"))
    private void logic$clockTitleAtNormalZoom(GuiGraphicsExtractor g,int mouseX,int mouseY,float delta,CallbackInfo ci){
        if(zoom<.70)return;
        for(EditorNode n:document.nodes){
            if(n.kind!=NodeKind.CONSTANT||!n.clockSource)continue;
            int x=screenX(n.x),y=screenY(n.y),w=Math.max(18,(int)Math.round(nodeWidth(n)*zoom));
            int top=y+Math.max(6,(int)Math.round(7*zoom));
            int bottom=y+Math.max(18,(int)Math.round(20*zoom));
            g.fill(x+3,top,x+w-3,bottom,0xFF191F26);
            String title="CLK "+EditorNode.formatFrequency(n.clockFrequencyHz);
            logic$text(g,title,x+w/2,top+Math.max(1,(bottom-top-8)/2),Math.max(6,w-8),0xFFF2F5F8);
        }
    }

    @Unique private void logic$text(GuiGraphicsExtractor g,String text,int cx,int y,int maxWidth,int color){
        String shown=logic$fit(text,Math.max(1,maxWidth));
        int rw=Math.max(1,font().width(shown));
        g.text(font(),shown,cx-rw/2,y,color,false);
    }

    @Unique private String logic$fit(String text,int maxWidth){
        if(text==null)return "";
        if(font().width(text)<=maxWidth)return text;
        String suffix="…";
        int end=text.length();
        while(end>1&&font().width(text.substring(0,end-1)+suffix)>maxWidth)end--;
        return end<=1&&font().width(suffix)>maxWidth?"":text.substring(0,Math.max(0,end-1))+suffix;
    }
}
