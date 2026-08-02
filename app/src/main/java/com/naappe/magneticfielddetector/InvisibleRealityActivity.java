package com.naappe.magneticfielddetector;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

public class InvisibleRealityActivity extends Activity implements SensorEventListener {
  private SensorManager sm; private RealityView view;
  @Override protected void onCreate(Bundle b){super.onCreate(b);requestWindowFeature(Window.FEATURE_NO_TITLE);getWindow().setStatusBarColor(Color.rgb(5,13,25));getWindow().setNavigationBarColor(Color.rgb(5,13,25));getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);sm=(SensorManager)getSystemService(Context.SENSOR_SERVICE);view=new RealityView(this);setContentView(view);}
  @Override protected void onResume(){super.onResume();for(int t:new int[]{2,1,4,5,6,8}){Sensor s=sm.getDefaultSensor(t);if(s!=null)sm.registerListener(this,s,SensorManager.SENSOR_DELAY_GAME);}}
  @Override protected void onPause(){super.onPause();sm.unregisterListener(this);}
  @Override public void onSensorChanged(SensorEvent e){view.accept(e);}
  @Override public void onAccuracyChanged(Sensor s,int a){}

  static final class RealityView extends View {
    final Paint p=new Paint(1),line=new Paint(1); final Deque<Float> history=new ArrayDeque<>();
    int page,samples,events; float magnetic,vibration,rotation,light=-1,pressure=-1,proximity=-1,baseMag,baseVib,baseLight,basePressure,anomaly,stability=100,complexity; boolean baseline,recording; long started; String insight="Capture a baseline to begin";
    RealityView(Context c){super(c);setBackgroundColor(Color.rgb(5,13,25));line.setStyle(Paint.Style.STROKE);line.setStrokeWidth(4);line.setColor(Color.rgb(76,220,187));}
    void accept(SensorEvent e){float[]v=e.values;switch(e.sensor.getType()){case 2:magnetic=(float)Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]);break;case 1:float m=(float)Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]);vibration=Math.abs(m-9.80665f);break;case 4:rotation=(float)Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]);break;case 5:light=v[0];break;case 6:pressure=v[0];break;case 8:proximity=v[0];break;}process();invalidate();}
    void process(){float md=baseline?Math.abs(magnetic-baseMag):0,vd=baseline?Math.abs(vibration-baseVib):0,ld=baseline&&light>=0&&baseLight>=0?Math.abs(light-baseLight)/Math.max(10,baseLight):0,pd=baseline&&pressure>0&&basePressure>0?Math.abs(pressure-basePressure):0;anomaly=clamp(md*.9f+vd*16+ld*18+pd*7,0,100);complexity=clamp(vibration*22+rotation*18,0,100);stability=clamp(100-anomaly*.72f-complexity*.28f,0,100);if(!baseline)insight="Capture a baseline to begin";else if(md>45&&vibration<.5)insight="Likely stationary magnetic source";else if(md>20&&vibration>.8)insight="Magnetic + vibration activity";else if(vibration>2)insight="Strong mechanical movement";else if(ld>1.2)insight="Major illumination change";else if(anomaly>35)insight="Unknown environmental anomaly";else insight="Environment matches baseline";if(anomaly>55)events++;history.addLast(anomaly);while(history.size()>120)history.removeFirst();if(recording)samples++;}
    @Override public boolean onTouchEvent(MotionEvent e){if(e.getAction()!=1)return true;float w=getWidth(),h=getHeight();if(e.getY()>h*.9){page=Math.max(0,Math.min(3,(int)(e.getX()/(w/4))));invalidate();return true;}if(page==1&&e.getY()>h*.78){baseMag=magnetic;baseVib=vibration;baseLight=light;basePressure=pressure;baseline=true;events=0;invalidate();}if(page==2&&e.getY()>h*.78){recording=!recording;if(recording){started=SystemClock.elapsedRealtime();samples=events=0;history.clear();}invalidate();}return true;}
    @Override protected void onDraw(Canvas c){super.onDraw(c);header(c);if(page==0)processPage(c);else if(page==1)fusionPage(c);else if(page==2)timePage(c);else discoveryPage(c);bottom(c);}
    void header(Canvas c){float w=getWidth(),h=getHeight();p.setColor(Color.WHITE);p.setFakeBoldText(true);p.setTextSize(w*.058f);c.drawText("Invisible Reality Lab",w*.055f,h*.065f,p);p.setFakeBoldText(false);p.setColor(Color.rgb(130,151,177));p.setTextSize(w*.028f);c.drawText("Processing · fusion · timeline · discovery",w*.055f,h*.098f,p);}
    void processPage(Canvas c){title(c,"PROCESS","Transforms raw signals into features");gauge(c,anomaly,"LIVE INDEX");metric(c,.055f,.55f,.43f,"MAGNETIC",fmt(magnetic),"µT");metric(c,.515f,.55f,.43f,"VIBRATION",fmt(vibration),"m/s² residual");metric(c,.055f,.70f,.43f,"ROTATION",fmt(rotation),"rad/s");metric(c,.515f,.70f,.43f,"LIGHT",light>=0?fmt(light):"--","lux");}
    void fusionPage(Canvas c){title(c,"FUSION","Cross-sensor environmental intelligence");gauge(c,anomaly,"ANOMALY SCORE");metric(c,.055f,.55f,.43f,"STABILITY",fmt(stability),"percent");metric(c,.515f,.55f,.43f,"COMPLEXITY",fmt(complexity),"motion index");metric(c,.055f,.70f,.89f,"INTERPRETATION",insight,baseline?"Compared with baseline":"Baseline required");button(c,baseline?"RECAPTURE BASELINE":"CAPTURE BASELINE");}
    void timePage(Canvas c){title(c,"TIME ENGINE","Records environmental change over time");graph(c);long sec=recording?(SystemClock.elapsedRealtime()-started)/1000:0;metric(c,.055f,.55f,.28f,"STATUS",recording?"RECORDING":"STOPPED","session");metric(c,.36f,.55f,.28f,"TIME",recording?sec+" s":"--","elapsed");metric(c,.665f,.55f,.28f,"SAMPLES",""+samples,"processed");metric(c,.055f,.70f,.43f,"EVENTS",""+events,"anomalies");metric(c,.515f,.70f,.43f,"CURRENT",insight,"latest inference");button(c,recording?"STOP RECORDING":"START RECORDING");}
    void discoveryPage(Canvas c){title(c,"DISCOVERY","Makes hidden patterns visible");metric(c,.055f,.23f,.89f,"CURRENT HYPOTHESIS",insight,"Inference, not certainty");metric(c,.055f,.40f,.43f,"MAGNETIC DELTA",baseline?fmt(Math.abs(magnetic-baseMag)):"--","µT");metric(c,.515f,.40f,.43f,"VIBRATION DELTA",baseline?fmt(Math.abs(vibration-baseVib)):"--","m/s²");metric(c,.055f,.57f,.43f,"PRESSURE",pressure>0?fmt(pressure):"--","hPa");metric(c,.515f,.57f,.43f,"PROXIMITY",proximity>=0?fmt(proximity):"--","sensor units");metric(c,.055f,.74f,.89f,"ENGINE","Invisible Reality 5.0","Acquire → features → fusion → discovery");}
    void title(Canvas c,String a,String b){float w=getWidth(),h=getHeight();p.setColor(Color.WHITE);p.setFakeBoldText(true);p.setTextSize(w*.043f);c.drawText(a,w*.055f,h*.155f,p);p.setFakeBoldText(false);p.setColor(Color.rgb(130,151,177));p.setTextSize(w*.026f);c.drawText(b,w*.055f,h*.198f,p);}
    void gauge(Canvas c,float value,String label){float w=getWidth(),h=getHeight(),cx=w/2,cy=h*.35f,r=w*.22f,n=clamp(value/100,0,1);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(w*.032f);p.setColor(Color.rgb(22,42,64));c.drawCircle(cx,cy,r,p);p.setColor(value>70?Color.rgb(255,95,90):value>35?Color.rgb(255,190,75):Color.rgb(76,220,187));p.setStrokeCap(Paint.Cap.ROUND);c.drawArc(cx-r,cy-r,cx+r,cy+r,-90,360*n,false,p);p.setStyle(Paint.Style.FILL);p.setStrokeCap(Paint.Cap.BUTT);p.setTextAlign(Paint.Align.CENTER);p.setColor(Color.WHITE);p.setFakeBoldText(true);p.setTextSize(w*.12f);c.drawText(String.format(Locale.US,"%.0f",value),cx,cy+w*.02f,p);p.setFakeBoldText(false);p.setTextSize(w*.029f);p.setColor(Color.rgb(145,164,187));c.drawText(label,cx,cy+w*.075f,p);p.setTextAlign(Paint.Align.LEFT);}
    void metric(Canvas c,float x,float y,float ww,String l,String v,String u){float w=getWidth(),h=getHeight(),left=w*x,top=h*y,width=w*ww,height=h*.115f;p.setColor(Color.rgb(12,29,49));c.drawRoundRect(left,top,left+width,top+height,24,24,p);p.setColor(Color.rgb(125,148,174));p.setTextSize(w*.024f);c.drawText(l,left+18,top+28,p);p.setColor(Color.WHITE);p.setFakeBoldText(true);p.setTextSize(w*.038f);String z=v.length()>27?v.substring(0,27):v;c.drawText(z,left+18,top+height*.67f,p);p.setFakeBoldText(false);p.setColor(Color.rgb(125,148,174));p.setTextSize(w*.021f);c.drawText(u,left+18,top+height-13,p);}
    void button(Canvas c,String s){float w=getWidth(),h=getHeight(),l=w*.055f,t=h*.835f,ww=w*.89f,hh=h*.05f;p.setColor(Color.rgb(24,99,131));c.drawRoundRect(l,t,l+ww,t+hh,20,20,p);p.setTextAlign(Paint.Align.CENTER);p.setColor(Color.WHITE);p.setFakeBoldText(true);p.setTextSize(w*.027f);c.drawText(s,l+ww/2,t+hh*.64f,p);p.setTextAlign(Paint.Align.LEFT);p.setFakeBoldText(false);}
    void graph(Canvas c){float w=getWidth(),h=getHeight(),l=w*.055f,t=h*.24f,r=w*.945f,b=h*.49f;p.setColor(Color.rgb(12,29,49));c.drawRoundRect(l,t,r,b,24,24,p);if(history.size()<2)return;Path path=new Path();int i=0,n=history.size();for(float v:history){float x=l+18+(r-l-36)*i/(n-1f),y=b-18-clamp(v/100,0,1)*(b-t-36);if(i++==0)path.moveTo(x,y);else path.lineTo(x,y);}c.drawPath(path,line);}
    void bottom(Canvas c){float w=getWidth(),h=getHeight();String[]a={"PROCESS","FUSION","TIMELINE","DISCOVERY"};for(int i=0;i<4;i++){float l=i*w/4;p.setColor(i==page?Color.rgb(28,103,136):Color.rgb(10,24,41));c.drawRect(l,h*.9f,l+w/4,h,p);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(w*.024f);p.setColor(i==page?Color.WHITE:Color.rgb(130,151,177));c.drawText(a[i],l+w/8,h*.955f,p);}p.setTextAlign(Paint.Align.LEFT);}
    static float clamp(float v,float a,float b){return Math.max(a,Math.min(b,v));}static String fmt(float v){return String.format(Locale.US,"%.2f",v);}
  }
}
