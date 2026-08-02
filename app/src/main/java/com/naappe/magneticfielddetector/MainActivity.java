package com.naappe.magneticfielddetector;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener {
    private static final int PERMISSION_REQUEST = 100;
    private SensorManager sensorManager;
    private WifiManager wifiManager;
    private ConnectivityManager connectivityManager;
    private TelephonyManager telephonyManager;
    private HiddenWorldView view;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final PhoneStateListener phoneListener = new PhoneStateListener() {
        @Override public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            if (view != null && signalStrength != null) view.cellLevel = signalStrength.getLevel();
        }
    };

    private final Runnable updater = new Runnable() {
        @Override public void run() {
            updateNetwork();
            if (view != null) view.invalidate();
            handler.postDelayed(this, 1200L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.rgb(5, 13, 24));
        getWindow().setNavigationBarColor(Color.rgb(5, 13, 24));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        view = new HiddenWorldView(this);
        setContentView(view);
        requestPermissionsIfNeeded();
    }

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT < 23) return;
        boolean location = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED;
        boolean phone = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED;
        boolean nearby = Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED;
        if (location || phone || nearby) {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE, Manifest.permission.NEARBY_WIFI_DEVICES}, PERMISSION_REQUEST);
            else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE}, PERMISSION_REQUEST);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        for (int type : new int[]{Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_LIGHT}) {
            Sensor s = sensorManager.getDefaultSensor(type);
            if (s != null) sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME);
        }
        try { telephonyManager.listen(phoneListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS); } catch (SecurityException ignored) { }
        handler.post(updater);
    }

    @Override protected void onPause() {
        super.onPause(); sensorManager.unregisterListener(this); handler.removeCallbacks(updater);
        try { telephonyManager.listen(phoneListener, PhoneStateListener.LISTEN_NONE); } catch (SecurityException ignored) { }
    }

    @Override public void onSensorChanged(SensorEvent event) {
        int type = event.sensor.getType();
        if (type == Sensor.TYPE_MAGNETIC_FIELD) {
            float x=event.values[0],y=event.values[1],z=event.values[2];
            view.magnetic=(float)Math.sqrt(x*x+y*y+z*z); view.mx=x;view.my=y;view.mz=z;view.accuracy=event.accuracy;view.onMagneticSample();
        } else if (type == Sensor.TYPE_ACCELEROMETER) {
            float x=event.values[0],y=event.values[1],z=event.values[2]; view.onAccelerationSample((float)Math.sqrt(x*x+y*y+z*z));
        } else if (type == Sensor.TYPE_GYROSCOPE) {
            float x=event.values[0],y=event.values[1],z=event.values[2]; view.gyro=(float)Math.sqrt(x*x+y*y+z*z);
        } else if (type == Sensor.TYPE_LIGHT) view.light=event.values[0];
        view.invalidate();
    }

    @Override public void onAccuracyChanged(Sensor sensor,int accuracy){view.accuracy=accuracy;}

    private void updateNetwork(){
        view.wifiConnected=false; view.ssid="Not connected";
        try{
            Network n=connectivityManager.getActiveNetwork(); NetworkCapabilities c=n==null?null:connectivityManager.getNetworkCapabilities(n);
            view.wifiConnected=c!=null&&c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            WifiInfo info=wifiManager.getConnectionInfo();
            if(view.wifiConnected&&info!=null){view.rssi=info.getRssi();view.frequency=info.getFrequency();view.linkSpeed=info.getLinkSpeed();String raw=info.getSSID();view.ssid=raw==null||WifiManager.UNKNOWN_SSID.equals(raw)?"Connected Wi-Fi":raw.replace("\"","");}
        }catch(SecurityException e){view.note="Grant Location and Nearby devices for Wi-Fi details";}
    }

    private static final class HiddenWorldView extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[][] map=new float[8][6]; private final boolean[][] visited=new boolean[8][6];
        private final Deque<Float> vibration=new ArrayDeque<>();
        int page=0,accuracy,rssi=-127,frequency,linkSpeed,cellLevel=-1,row=0,col=0;
        float magnetic,mx,my,mz,gyro,light=-1,rms,peak,hz,baseMag=-1,baseRssi=-127,baseLight=-1,baseRms=-1,lastDelta;
        boolean wifiConnected,scanning; String ssid="Not connected",note=""; long windowStart;int crossings;

        HiddenWorldView(Context c){super(c);setBackgroundColor(Color.rgb(5,13,24));}
        void onMagneticSample(){if(scanning){float old=map[row][col];map[row][col]=visited[row][col]?old*.75f+magnetic*.25f:magnetic;visited[row][col]=true;}}
        void onAccelerationSample(float magnitude){float d=Math.abs(magnitude-9.81f);vibration.addLast(d);while(vibration.size()>120)vibration.removeFirst();float s=0,pk=0;for(float v:vibration){s+=v*v;if(v>pk)pk=v;}rms=vibration.isEmpty()?0:(float)Math.sqrt(s/vibration.size());peak=pk;long now=System.nanoTime();if(windowStart==0)windowStart=now;if(Math.signum(d-.08f)!=Math.signum(lastDelta-.08f))crossings++;if(now-windowStart>2_000_000_000L){hz=crossings/4f;crossings=0;windowStart=now;}lastDelta=d;}

        @Override public boolean onTouchEvent(MotionEvent e){float w=getWidth(),h=getHeight();if(e.getAction()==MotionEvent.ACTION_UP&&e.getY()>h*.90f){page=Math.max(0,Math.min(3,(int)(e.getX()/(w/4f))));invalidate();return true;}if(page==0){RectF b=new RectF(w*.06f,h*.79f,w*.94f,h*.86f);if(e.getAction()==MotionEvent.ACTION_UP&&b.contains(e.getX(),e.getY())){scanning=!scanning;invalidate();return true;}RectF g=new RectF(w*.06f,h*.23f,w*.94f,h*.72f);if((e.getAction()==MotionEvent.ACTION_DOWN||e.getAction()==MotionEvent.ACTION_MOVE)&&g.contains(e.getX(),e.getY())){col=Math.max(0,Math.min(5,(int)((e.getX()-g.left)/(g.width()/6f))));row=Math.max(0,Math.min(7,(int)((e.getY()-g.top)/(g.height()/8f))));invalidate();return true;}}if(page==3&&e.getAction()==MotionEvent.ACTION_UP&&e.getY()>h*.74f&&e.getY()<h*.85f){baseMag=magnetic;baseRssi=rssi;baseLight=light;baseRms=rms;invalidate();return true;}return true;}

        @Override protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight(),pad=w*.055f;p.setColor(Color.WHITE);p.setFakeBoldText(true);p.setTextSize(w*.057f);c.drawText("Hidden World Lab",pad,h*.07f,p);p.setFakeBoldText(false);p.setColor(Color.rgb(135,157,180));p.setTextSize(w*.028f);c.drawText("Make invisible patterns visible",pad,h*.101f,p);if(page==0)magCamera(c);else if(page==1)vibration(c);else if(page==2)signals(c);else changes(c);bottom(c);}

        private void magCamera(Canvas c){float w=getWidth(),h=getHeight();title(c,"MAGNETIC CAMERA","Move phone over an area and touch the matching grid cell",h*.155f);RectF g=new RectF(w*.06f,h*.23f,w*.94f,h*.72f);float cw=g.width()/6f,ch=g.height()/8f;for(int r=0;r<8;r++)for(int q=0;q<6;q++){float l=g.left+q*cw,t=g.top+r*ch;p.setColor(visited[r][q]?heat(map[r][q]):Color.rgb(17,31,47));c.drawRoundRect(l+3,t+3,l+cw-3,t+ch-3,10,10,p);if(scanning&&r==row&&q==col){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(5);p.setColor(Color.WHITE);c.drawRoundRect(l+2,t+2,l+cw-2,t+ch-2,10,10,p);p.setStyle(Paint.Style.FILL);}}p.setTextAlign(Paint.Align.CENTER);p.setColor(Color.WHITE);p.setTextSize(w*.033f);c.drawText(String.format(Locale.US,"Live %.1f µT",magnetic),w/2f,h*.755f,p);p.setTextAlign(Paint.Align.LEFT);button(c,w*.06f,h*.79f,w*.94f,h*.86f,scanning?"STOP SCAN":"START MAGNETIC SCAN",scanning);}
        private void vibration(Canvas c){float w=getWidth(),h=getHeight(),pad=w*.06f;title(c,"VIBRATION VISION","Reveal tiny movements your eyes cannot see",h*.155f);card(c,pad,h*.23f,w*.42f,h*.12f,"RMS VIBRATION",fmt(rms),"m/s² variation");card(c,w*.52f,h*.23f,w*.42f,h*.12f,"PEAK",fmt(peak),"m/s² variation");card(c,pad,h*.38f,w*.42f,h*.12f,"EST. FREQUENCY",fmt(hz),"Hz");card(c,w*.52f,h*.38f,w*.42f,h*.12f,"ROTATION",fmt(gyro),"rad/s");RectF graph=new RectF(pad,h*.56f,w-pad,h*.81f);p.setColor(Color.rgb(14,29,45));c.drawRoundRect(graph,22,22,p);Float[] a=vibration.toArray(new Float[0]);if(a.length>1){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(4);p.setColor(Color.rgb(80,220,190));for(int i=1;i<a.length;i++){float x1=graph.left+graph.width()*(i-1)/(a.length-1f),x2=graph.left+graph.width()*i/(a.length-1f);float y1=graph.bottom-Math.min(a[i-1]/3f,1f)*graph.height(),y2=graph.bottom-Math.min(a[i]/3f,1f)*graph.height();c.drawLine(x1,y1,x2,y2,p);}p.setStyle(Paint.Style.FILL);}p.setColor(Color.rgb(135,157,180));p.setTextSize(w*.026f);c.drawText("Place phone on a table, appliance or machine to expose vibration.",pad,h*.855f,p);}
        private void signals(Canvas c){float w=getWidth(),h=getHeight(),pad=w*.06f;title(c,"INVISIBLE SIGNAL MAP","See radio connection strength that humans cannot sense",h*.155f);gauge(c,w/2f,h*.34f,w*.21f,wifiConnected?Math.max(0,Math.min(1,(rssi+100)/70f)):0,wifiConnected?String.valueOf(rssi):"--","dBm",wifiConnected?signalLabel():"Wi-Fi not connected");p.setTextAlign(Paint.Align.CENTER);p.setColor(Color.rgb(190,205,220));p.setTextSize(w*.032f);c.drawText(ssid,w/2f,h*.50f,p);p.setTextAlign(Paint.Align.LEFT);card(c,pad,h*.55f,w*.42f,h*.11f,"FREQUENCY",wifiConnected?String.valueOf(frequency):"--","MHz");card(c,w*.52f,h*.55f,w*.42f,h*.11f,"LINK SPEED",wifiConnected?String.valueOf(linkSpeed):"--","Mbps");card(c,pad,h*.69f,w*.42f,h*.11f,"WI-FI QUALITY",wifiConnected?quality()+"%":"--","calculated RSSI");card(c,w*.52f,h*.69f,w*.42f,h*.11f,"CELLULAR",cellLevel>=0?cellLevel+" / 4":"--","Android level");if(!note.isEmpty()){p.setColor(Color.rgb(255,190,80));p.setTextSize(w*.025f);c.drawText(note,pad,h*.85f,p);}}
        private void changes(Canvas c){float w=getWidth(),h=getHeight(),pad=w*.06f;title(c,"INVISIBLE CHANGE DETECTOR","Capture a place, return later and compare hidden signals",h*.155f);boolean ready=baseMag>=0;diff(c,pad,h*.23f,w*.42f,h*.13f,"MAGNETIC",magnetic-baseMag,"µT",ready);diff(c,w*.52f,h*.23f,w*.42f,h*.13f,"WI-FI",rssi-baseRssi,"dBm",ready&&baseRssi>-127);diff(c,pad,h*.40f,w*.42f,h*.13f,"LIGHT",light-baseLight,"lux",ready&&baseLight>=0);diff(c,w*.52f,h*.40f,w*.42f,h*.13f,"VIBRATION",rms-baseRms,"m/s²",ready);float score=ready?Math.min(100,Math.abs(magnetic-baseMag)*1.2f+Math.abs(rssi-baseRssi)*1.8f+Math.abs(light-baseLight)*.05f+Math.abs(rms-baseRms)*25f):0;p.setTextAlign(Paint.Align.CENTER);p.setColor(score>60?Color.rgb(255,95,90):score>25?Color.rgb(255,190,80):Color.rgb(75,215,180));p.setFakeBoldText(true);p.setTextSize(w*.115f);c.drawText(ready?String.format(Locale.US,"%.0f",score):"--",w/2f,h*.68f,p);p.setTextSize(w*.032f);p.setFakeBoldText(false);c.drawText(ready?"environment change score":"No baseline captured",w/2f,h*.715f,p);p.setTextAlign(Paint.Align.LEFT);button(c,pad,h*.76f,w-pad,h*.84f,ready?"CAPTURE NEW BASELINE":"CAPTURE BASELINE",false);}

        private void bottom(Canvas c){float w=getWidth(),h=getHeight();String[] n={"MAG CAMERA","VIBRATION","SIGNALS","CHANGE"};for(int i=0;i<4;i++){float l=i*w/4f;p.setColor(i==page?Color.rgb(26,104,132):Color.rgb(10,24,39));c.drawRect(l,h*.90f,l+w/4f,h,p);p.setTextAlign(Paint.Align.CENTER);p.setFakeBoldText(i==page);p.setTextSize(w*.025f);p.setColor(i==page?Color.WHITE:Color.rgb(135,157,180));c.drawText(n[i],l+w/8f,h*.956f,p);}p.setTextAlign(Paint.Align.LEFT);p.setFakeBoldText(false);}
        private void title(Canvas c,String a,String b,float y){float w=getWidth();p.setColor(Color.WHITE);p.setFakeBoldText(true);p.setTextSize(w*.042f);c.drawText(a,w*.055f,y,p);p.setFakeBoldText(false);p.setColor(Color.rgb(135,157,180));p.setTextSize(w*.026f);c.drawText(b,w*.055f,y+w*.043f,p);}
        private void card(Canvas c,float l,float t,float width,float height,String label,String value,String unit){p.setColor(Color.rgb(14,29,45));c.drawRoundRect(l,t,l+width,t+height,22,22,p);p.setColor(Color.rgb(135,157,180));p.setTextSize(getWidth()*.027f);c.drawText(label,l+18,t+30,p);p.setColor(Color.WHITE);p.setFakeBoldText(true);p.setTextSize(getWidth()*.043f);c.drawText(value,l+18,t+height-35,p);p.setFakeBoldText(false);p.setColor(Color.rgb(135,157,180));p.setTextSize(getWidth()*.024f);c.drawText(unit,l+18,t+height-13,p);}
        private void diff(Canvas c,float l,float t,float width,float height,String label,float d,String unit,boolean ready){p.setColor(Color.rgb(14,29,45));c.drawRoundRect(l,t,l+width,t+height,22,22,p);p.setColor(Color.rgb(135,157,180));p.setTextSize(getWidth()*.027f);c.drawText(label,l+18,t+30,p);p.setColor(!ready?Color.rgb(135,157,180):Math.abs(d)>.5f?Color.rgb(255,190,80):Color.rgb(75,215,180));p.setFakeBoldText(true);p.setTextSize(getWidth()*.047f);c.drawText(ready?String.format(Locale.US,"%+.1f",d):"--",l+18,t+height-30,p);p.setFakeBoldText(false);p.setTextSize(getWidth()*.024f);c.drawText(unit,l+18,t+height-10,p);}
        private void button(Canvas c,float l,float t,float r,float b,String text,boolean active){p.setColor(active?Color.rgb(170,55,55):Color.rgb(24,101,132));c.drawRoundRect(l,t,r,b,20,20,p);p.setTextAlign(Paint.Align.CENTER);p.setColor(Color.WHITE);p.setFakeBoldText(true);p.setTextSize(getWidth()*.031f);c.drawText(text,(l+r)/2f,t+(b-t)*.64f,p);p.setTextAlign(Paint.Align.LEFT);p.setFakeBoldText(false);}
        private void gauge(Canvas c,float cx,float cy,float r,float n,String value,String unit,String label){float w=getWidth();p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(w*.028f);p.setColor(Color.rgb(24,43,62));c.drawCircle(cx,cy,r,p);p.setColor(n>.72f?Color.rgb(255,95,90):n>.42f?Color.rgb(255,190,80):Color.rgb(75,215,180));p.setStrokeCap(Paint.Cap.ROUND);c.drawArc(cx-r,cy-r,cx+r,cy+r,-90,360*n,false,p);p.setStrokeCap(Paint.Cap.BUTT);p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);p.setColor(Color.WHITE);p.setFakeBoldText(true);p.setTextSize(w*.105f);c.drawText(value,cx,cy+w*.018f,p);p.setFakeBoldText(false);p.setColor(Color.rgb(150,171,194));p.setTextSize(w*.032f);c.drawText(unit,cx,cy+w*.073f,p);p.setTextSize(w*.027f);c.drawText(label,cx,cy+r+w*.065f,p);p.setTextAlign(Paint.Align.LEFT);}
        private int heat(float v){if(v<45)return Color.rgb(38,95,170);if(v<65)return Color.rgb(42,180,155);if(v<95)return Color.rgb(230,190,65);if(v<140)return Color.rgb(240,120,55);return Color.rgb(220,55,65);}
        private String signalLabel(){if(rssi>=-55)return"Very strong signal";if(rssi>=-67)return"Strong signal";if(rssi>=-75)return"Usable signal";return"Weak signal";}
        private int quality(){return Math.max(0,Math.min(100,2*(rssi+100)));}
        private String fmt(float v){return String.format(Locale.US,"%.2f",v);}
    }
}
