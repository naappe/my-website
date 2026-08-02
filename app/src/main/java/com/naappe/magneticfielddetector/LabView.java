package com.naappe.magneticfielddetector;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

import java.util.Deque;
import java.util.List;
import java.util.Locale;

public final class LabView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SensorEngine engine;
    private int page;

    public LabView(Context context, SensorEngine engine) {
        super(context);
        this.engine = engine;
        setBackgroundColor(Color.rgb(6, 15, 27));
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_UP) return true;
        float w = getWidth(), h = getHeight();
        if (e.getY() > h * .91f) {
            page = Math.max(0, Math.min(3, (int)(e.getX() / (w / 4f))));
            invalidate();
            return true;
        }
        if (page == 1 && e.getY() > h*.76f && e.getY() < h*.86f) engine.captureBaseline();
        if (page == 2 && e.getY() > h*.75f && e.getY() < h*.86f) {
            if (e.getX() < w/2f) engine.captureMapCell(); else engine.clearMap();
        }
        if (page == 3 && e.getY() > h*.75f && e.getY() < h*.86f) engine.toggleRecording();
        invalidate();
        return true;
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight(), pad = w*.055f;
        p.setColor(Color.WHITE); p.setFakeBoldText(true); p.setTextSize(w*.058f);
        c.drawText("Hidden World Laboratory", pad, h*.065f, p);
        p.setFakeBoldText(false); p.setColor(Color.rgb(138,160,185)); p.setTextSize(w*.028f);
        c.drawText("Maximum-boundary Android sensing · v4", pad, h*.098f, p);
        if (page==0) drawDiscover(c); else if (page==1) drawFusion(c); else if (page==2) drawMap(c); else drawTimeline(c);
        drawBottom(c);
    }

    private void drawDiscover(Canvas c) {
        float w=getWidth(), h=getHeight(), pad=w*.055f;
        title(c,"A · DISCOVER","Everything the phone exposes through Android",h*.155f);
        List<String> lines = engine.capabilityLines();
        float y=h*.225f;
        for(String line: lines){
            panel(c,pad,y,w*.89f,h*.067f);
            p.setColor(Color.WHITE);p.setTextSize(w*.030f);c.drawText(line,pad+w*.035f,y+h*.043f,p);
            y+=h*.079f;
        }
        p.setColor(Color.rgb(120,145,170));p.setTextSize(w*.025f);
        c.drawText("Counts update as Wi-Fi, BLE and GNSS observations change.",pad,h*.875f,p);
    }

    private void drawFusion(Canvas c) {
        float w=getWidth(),h=getHeight(),pad=w*.055f;
        title(c,"B · SENSOR FUSION","Derived patterns humans cannot directly perceive",h*.155f);
        gauge(c,w/2f,h*.33f,w*.20f,engine.environmentScore,"ENVIRONMENT CHANGE");
        card(c,pad,h*.52f,w*.43f,h*.10f,"MAGNETIC ANOMALY",pct(engine.magneticAnomaly),fmt(engine.magnetic)+" µT");
        card(c,w*.515f,h*.52f,w*.43f,h*.10f,"MOTION COMPLEXITY",pct(engine.motionComplexity),fmt(engine.vibration)+" m/s²");
        card(c,pad,h*.65f,w*.43f,h*.10f,"WIRELESS DENSITY",pct(engine.wirelessDensity),engine.nearbyWifiCount+" Wi-Fi · "+engine.nearbyBleCount+" BLE");
        card(c,w*.515f,h*.65f,w*.43f,h*.10f,"STABILITY",pct(engine.stabilityScore),engine.lastEvent);
        button(c,pad,h*.78f,w*.89f,h*.075f,"CAPTURE NEW BASELINE");
    }

    private void drawMap(Canvas c) {
        float w=getWidth(),h=getHeight(),pad=w*.055f;
        title(c,"C · SPATIAL INTELLIGENCE","Scan 36 positions to paint invisible fields",h*.155f);
        float left=pad, top=h*.235f, size=w*.89f, cell=size/6f;
        for(int i=0;i<36;i++){
            int row=i/6,col=i%6; float l=left+col*cell,t=top+row*cell;
            float mag=engine.magneticMap[i];
            p.setColor(mapColor(mag)); c.drawRect(l+3,t+3,l+cell-3,t+cell-3,p);
            if(i==engine.mapCursor && engine.mapCursor<36){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(5);p.setColor(Color.WHITE);c.drawRect(l+5,t+5,l+cell-5,t+cell-5,p);p.setStyle(Paint.Style.FILL);}
            if(mag>0){p.setTextAlign(Paint.Align.CENTER);p.setTextSize(w*.021f);p.setColor(Color.WHITE);c.drawText(String.format(Locale.US,"%.0f",mag),l+cell/2,t+cell*.60f,p);p.setTextAlign(Paint.Align.LEFT);}
        }
        p.setColor(Color.rgb(135,160,185));p.setTextSize(w*.025f);
        c.drawText("Move phone to each point, then capture. Values are magnetic µT.",pad,h*.705f,p);
        button(c,pad,h*.77f,w*.42f,h*.075f,"CAPTURE CELL");
        button(c,w*.525f,h*.77f,w*.42f,h*.075f,"CLEAR MAP");
    }

    private void drawTimeline(Canvas c) {
        float w=getWidth(),h=getHeight(),pad=w*.055f;
        title(c,"D · TIME INTELLIGENCE","Record changes, order events and export CSV",h*.155f);
        panel(c,pad,h*.225f,w*.89f,h*.27f);
        drawSeries(c,engine.fusionHistory,pad+w*.025f,h*.27f,w-pad-w*.025f,h*.45f,100f);
        p.setColor(Color.rgb(150,175,200));p.setTextSize(w*.026f);c.drawText("Environment change timeline",pad+w*.025f,h*.255f,p);
        card(c,pad,h*.53f,w*.43f,h*.10f,"ANOMALY EVENTS",String.valueOf(engine.anomalyEvents),engine.lastEvent);
        card(c,w*.515f,h*.53f,w*.43f,h*.10f,"RECORDED SAMPLES",String.valueOf(engine.recordedSamples),engine.recording?"recording now":"stopped");
        p.setColor(Color.rgb(125,150,175));p.setTextSize(w*.022f);c.drawText(engine.lastSavedPath,pad,h*.70f,p);
        button(c,pad,h*.77f,w*.89f,h*.075f,engine.recording?"STOP & SAVE CSV":"START MULTI-SENSOR RECORDING");
    }

    private void drawBottom(Canvas c){
        float w=getWidth(),h=getHeight();String[] labels={"DISCOVER","FUSION","MAP","TIME"};
        for(int i=0;i<4;i++){float l=i*w/4f;p.setColor(i==page?Color.rgb(25,105,145):Color.rgb(11,27,45));c.drawRect(l,h*.91f,l+w/4f,h,p);p.setTextAlign(Paint.Align.CENTER);p.setColor(i==page?Color.WHITE:Color.rgb(135,158,182));p.setFakeBoldText(i==page);p.setTextSize(w*.026f);c.drawText(labels[i],l+w/8f,h*.965f,p);}p.setTextAlign(Paint.Align.LEFT);p.setFakeBoldText(false);
    }

    private void title(Canvas c,String a,String b,float y){float w=getWidth();p.setColor(Color.WHITE);p.setFakeBoldText(true);p.setTextSize(w*.040f);c.drawText(a,w*.055f,y,p);p.setFakeBoldText(false);p.setColor(Color.rgb(135,158,182));p.setTextSize(w*.026f);c.drawText(b,w*.055f,y+w*.043f,p);}
    private void panel(Canvas c,float l,float t,float width,float height){p.setColor(Color.rgb(13,31,50));c.drawRoundRect(l,t,l+width,t+height,24,24,p);}
    private void card(Canvas c,float l,float t,float width,float height,String label,String value,String sub){panel(c,l,t,width,height);p.setColor(Color.rgb(130,155,180));p.setTextSize(getWidth()*.023f);c.drawText(label,l+18,t+28,p);p.setColor(Color.WHITE);p.setFakeBoldText(true);p.setTextSize(getWidth()*.038f);c.drawText(value,l+18,t+height*.64f,p);p.setFakeBoldText(false);p.setColor(Color.rgb(115,140,165));p.setTextSize(getWidth()*.020f);c.drawText(trim(sub,27),l+18,t+height*.86f,p);}
    private void button(Canvas c,float l,float t,float width,float height,String text){p.setColor(Color.rgb(23,101,139));c.drawRoundRect(l,t,l+width,t+height,22,22,p);p.setTextAlign(Paint.Align.CENTER);p.setFakeBoldText(true);p.setColor(Color.WHITE);p.setTextSize(getWidth()*.027f);c.drawText(text,l+width/2,t+height*.62f,p);p.setTextAlign(Paint.Align.LEFT);p.setFakeBoldText(false);}
    private void gauge(Canvas c,float cx,float cy,float r,float value,String label){float w=getWidth();p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(w*.027f);p.setColor(Color.rgb(25,45,65));c.drawCircle(cx,cy,r,p);p.setColor(scoreColor(value));p.setStrokeCap(Paint.Cap.ROUND);c.drawArc(cx-r,cy-r,cx+r,cy+r,-90,Math.min(360,value*3.6f),false,p);p.setStrokeCap(Paint.Cap.BUTT);p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);p.setColor(Color.WHITE);p.setFakeBoldText(true);p.setTextSize(w*.10f);c.drawText(String.format(Locale.US,"%.0f",value),cx,cy+w*.02f,p);p.setFakeBoldText(false);p.setColor(Color.rgb(145,170,195));p.setTextSize(w*.023f);c.drawText(label,cx,cy+w*.075f,p);p.setTextAlign(Paint.Align.LEFT);}
    private void drawSeries(Canvas c,Deque<Float> values,float l,float t,float r,float b,float max){if(values.size()<2)return;Path path=new Path();int i=0,n=values.size();for(float v:values){float x=l+(r-l)*i/(n-1f),y=b-Math.min(1,v/max)*(b-t);if(i==0)path.moveTo(x,y);else path.lineTo(x,y);i++;}p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(4);p.setColor(Color.rgb(72,211,181));c.drawPath(path,p);p.setStyle(Paint.Style.FILL);}
    private int mapColor(float v){if(v<=0)return Color.rgb(18,37,57);if(v<50)return Color.rgb(25,110,145);if(v<75)return Color.rgb(50,165,130);if(v<120)return Color.rgb(225,170,55);return Color.rgb(220,70,70);}
    private int scoreColor(float v){if(v<30)return Color.rgb(70,210,180);if(v<60)return Color.rgb(235,180,65);return Color.rgb(235,85,80);}
    private static String pct(float v){return String.format(Locale.US,"%.0f%%",v);}private static String fmt(float v){return String.format(Locale.US,"%.2f",v);}private static String trim(String s,int n){if(s==null)return "";return s.length()>n?s.substring(0,n-1)+"…":s;}
}
