package com.naappe.magneticfielddetector;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener {
    private static final int PERMISSION_REQUEST = 100;

    private SensorManager sensorManager;
    private WifiManager wifiManager;
    private ConnectivityManager connectivityManager;
    private TelephonyManager telephonyManager;
    private LabView labView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final PhoneStateListener phoneListener = new PhoneStateListener() {
        @Override public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            if (labView != null && signalStrength != null) labView.cellLevel = signalStrength.getLevel();
        }
    };

    private final Runnable updater = new Runnable() {
        @Override public void run() {
            updateNetwork();
            updateDevice();
            labView.invalidate();
            handler.postDelayed(this, 1500L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.rgb(7, 16, 29));
        getWindow().setNavigationBarColor(Color.rgb(7, 16, 29));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        labView = new LabView(this, sensorManager);
        setContentView(labView);
        requestPermissionsIfNeeded();
    }

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT < 23) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED ||
                (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED)) {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE, Manifest.permission.NEARBY_WIFI_DEVICES}, PERMISSION_REQUEST);
            else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE}, PERMISSION_REQUEST);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        for (int type : new int[]{Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_LIGHT, Sensor.TYPE_PROXIMITY, Sensor.TYPE_PRESSURE}) {
            Sensor sensor = sensorManager.getDefaultSensor(type);
            if (sensor != null) sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
        }
        try { telephonyManager.listen(phoneListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS); } catch (SecurityException ignored) { }
        handler.post(updater);
    }

    @Override protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
        handler.removeCallbacks(updater);
        try { telephonyManager.listen(phoneListener, PhoneStateListener.LISTEN_NONE); } catch (SecurityException ignored) { }
    }

    @Override public void onSensorChanged(SensorEvent event) {
        switch (event.sensor.getType()) {
            case Sensor.TYPE_MAGNETIC_FIELD:
                labView.mx = event.values[0]; labView.my = event.values[1]; labView.mz = event.values[2];
                labView.magnetic = (float)Math.sqrt(labView.mx*labView.mx + labView.my*labView.my + labView.mz*labView.mz);
                labView.accuracy = event.accuracy; break;
            case Sensor.TYPE_ACCELEROMETER:
                labView.ax = event.values[0]; labView.ay = event.values[1]; labView.az = event.values[2]; break;
            case Sensor.TYPE_GYROSCOPE:
                labView.gx = event.values[0]; labView.gy = event.values[1]; labView.gz = event.values[2]; break;
            case Sensor.TYPE_LIGHT: labView.light = event.values[0]; break;
            case Sensor.TYPE_PROXIMITY: labView.proximity = event.values[0]; break;
            case Sensor.TYPE_PRESSURE: labView.pressure = event.values[0]; break;
        }
        labView.invalidate();
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { labView.accuracy = accuracy; }

    private void updateNetwork() {
        labView.wifiConnected = false;
        labView.ssid = "Not connected";
        try {
            Network n = connectivityManager.getActiveNetwork();
            NetworkCapabilities c = n == null ? null : connectivityManager.getNetworkCapabilities(n);
            labView.wifiConnected = c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            WifiInfo info = wifiManager.getConnectionInfo();
            if (labView.wifiConnected && info != null) {
                labView.rssi = info.getRssi();
                labView.linkSpeed = info.getLinkSpeed();
                labView.frequency = info.getFrequency();
                String raw = info.getSSID();
                labView.ssid = raw == null || WifiManager.UNKNOWN_SSID.equals(raw) ? "Connected Wi-Fi" : raw.replace("\"", "");
            }
        } catch (SecurityException ignored) { labView.permissionNote = "Grant Nearby devices and Location for Wi-Fi details"; }
    }

    private void updateDevice() {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            labView.batteryPercent = scale > 0 ? Math.round(level * 100f / scale) : -1;
            labView.batteryTemp = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
            labView.batteryVoltage = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000f;
        }
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        long total = fs.getTotalBytes();
        long free = fs.getAvailableBytes();
        labView.storageTotalGb = total / 1073741824f;
        labView.storageFreeGb = free / 1073741824f;
    }

    private static final class LabView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean hasMag, hasAccel, hasGyro, hasLight, hasProx, hasPressure;
        int page = 0, accuracy, rssi = -127, linkSpeed, frequency, cellLevel = -1, batteryPercent = -1;
        float mx,my,mz,magnetic, ax,ay,az, gx,gy,gz, light=-1, proximity=-1, pressure=-1, batteryTemp, batteryVoltage, storageTotalGb, storageFreeGb;
        boolean wifiConnected;
        String ssid="Not connected", permissionNote="";

        LabView(Context context, SensorManager sm) {
            super(context);
            setBackgroundColor(Color.rgb(7,16,29));
            hasMag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null;
            hasAccel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null;
            hasGyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null;
            hasLight = sm.getDefaultSensor(Sensor.TYPE_LIGHT) != null;
            hasProx = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null;
            hasPressure = sm.getDefaultSensor(Sensor.TYPE_PRESSURE) != null;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction()==MotionEvent.ACTION_UP && e.getY()>getHeight()*0.90f) {
                page = Math.max(0, Math.min(3, (int)(e.getX()/(getWidth()/4f)))); invalidate();
            }
            return true;
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w=getWidth(), h=getHeight(), pad=w*.055f;
            p.setColor(Color.WHITE); p.setFakeBoldText(true); p.setTextSize(w*.061f); c.drawText("Sensor Laboratory",pad,h*.075f,p);
            p.setFakeBoldText(false); p.setColor(Color.rgb(137,157,180)); p.setTextSize(w*.030f); c.drawText("Professional Android diagnostics",pad,h*.105f,p);
            if(page==0) drawMagnetic(c); else if(page==1) drawRadio(c); else if(page==2) drawMotion(c); else drawDevice(c);
            drawBottom(c);
        }

        private void drawMagnetic(Canvas c) {
            float w=getWidth(), h=getHeight(), pad=w*.055f;
            title(c,"MAGNETIC LAB","Direct magnetometer measurement",h*.16f);
            gauge(c,w/2f,h*.34f,w*.23f,Math.min(magnetic/200f,1f),String.format(Locale.US,"%.1f",magnetic),"µT",magnetic<70?"Normal ambient field":magnetic<150?"Elevated field":"Strong field");
            card(c,pad,h*.55f,w*.27f,h*.105f,"X",fmt(mx),"µT"); card(c,w*.365f,h*.55f,w*.27f,h*.105f,"Y",fmt(my),"µT"); card(c,w*.675f,h*.55f,w*.27f,h*.105f,"Z",fmt(mz),"µT");
            card(c,pad,h*.69f,w*.43f,h*.11f,"SENSOR",hasMag?"AVAILABLE":"MISSING",hasMag?"hardware detected":"not supported");
            card(c,w*.515f,h*.69f,w*.43f,h*.11f,"ACCURACY",accuracyLabel(),"sensor status");
        }

        private void drawRadio(Canvas c) {
            float w=getWidth(), h=getHeight(), pad=w*.055f;
            title(c,"RADIO LAB","Android-exposed network measurements",h*.16f);
            gauge(c,w/2f,h*.34f,w*.23f,wifiConnected?Math.max(0,Math.min(1,(rssi+100)/70f)):0,wifiConnected?String.valueOf(rssi):"--","dBm",wifiConnected?signalLabel():"Wi-Fi not connected");
            p.setTextAlign(Paint.Align.CENTER); p.setColor(Color.rgb(190,205,222)); p.setTextSize(w*.034f); c.drawText(ssid,w/2f,h*.505f,p); p.setTextAlign(Paint.Align.LEFT);
            card(c,pad,h*.56f,w*.43f,h*.105f,"LINK SPEED",wifiConnected?String.valueOf(linkSpeed):"--","Mbps"); card(c,w*.515f,h*.56f,w*.43f,h*.105f,"FREQUENCY",wifiConnected?String.valueOf(frequency):"--","MHz");
            card(c,pad,h*.70f,w*.43f,h*.105f,"WI-FI QUALITY",wifiConnected?quality()+"%":"--","calculated from RSSI"); card(c,w*.515f,h*.70f,w*.43f,h*.105f,"CELLULAR",cellLevel>=0?cellLevel+" / 4":"--","Android signal level");
            if(!permissionNote.isEmpty()){p.setColor(Color.rgb(255,190,80));p.setTextSize(w*.025f);c.drawText(permissionNote,pad,h*.855f,p);}
        }

        private void drawMotion(Canvas c) {
            float w=getWidth(), h=getHeight(), pad=w*.055f;
            title(c,"MOTION & ENVIRONMENT","Live phone sensor values",h*.16f);
            card(c,pad,h*.23f,w*.43f,h*.12f,"ACCELEROMETER",hasAccel?fmt3(ax,ay,az):"Unavailable","m/s²  X · Y · Z"); card(c,w*.515f,h*.23f,w*.43f,h*.12f,"GYROSCOPE",hasGyro?fmt3(gx,gy,gz):"Unavailable","rad/s  X · Y · Z");
            card(c,pad,h*.39f,w*.43f,h*.12f,"AMBIENT LIGHT",hasLight?fmt(light):"Unavailable","lux"); card(c,w*.515f,h*.39f,w*.43f,h*.12f,"PROXIMITY",hasProx?fmt(proximity):"Unavailable","cm / sensor units");
            card(c,pad,h*.55f,w*.43f,h*.12f,"PRESSURE",hasPressure?fmt(pressure):"Unavailable","hPa"); card(c,w*.515f,h*.55f,w*.43f,h*.12f,"SUPPORTED",supported()+" / 6","physical sensors");
            p.setColor(Color.rgb(137,157,180)); p.setTextSize(w*.028f); c.drawText("Values depend on hardware installed by the phone manufacturer.",pad,h*.75f,p);
        }

        private void drawDevice(Canvas c) {
            float w=getWidth(), h=getHeight(), pad=w*.055f;
            title(c,"DEVICE LAB","Battery, storage and platform information",h*.16f);
            card(c,pad,h*.23f,w*.43f,h*.12f,"BATTERY",batteryPercent>=0?batteryPercent+"%":"--",batteryTemp+" °C"); card(c,w*.515f,h*.23f,w*.43f,h*.12f,"VOLTAGE",String.format(Locale.US,"%.2f",batteryVoltage),"V");
            card(c,pad,h*.39f,w*.43f,h*.12f,"FREE STORAGE",String.format(Locale.US,"%.1f",storageFreeGb),"GB"); card(c,w*.515f,h*.39f,w*.43f,h*.12f,"TOTAL STORAGE",String.format(Locale.US,"%.1f",storageTotalGb),"GB");
            card(c,pad,h*.55f,w*.43f,h*.12f,"ANDROID",Build.VERSION.RELEASE,"API "+Build.VERSION.SDK_INT); card(c,w*.515f,h*.55f,w*.43f,h*.12f,"DEVICE",Build.MANUFACTURER,""+Build.MODEL);
            card(c,pad,h*.71f,w*.89f,h*.10f,"BUILD","Sensor Laboratory 2.0","Direct values + Android-reported data");
        }

        private void drawBottom(Canvas c) {
            float w=getWidth(),h=getHeight(); String[] labels={"MAGNETIC","RADIO","MOTION","DEVICE"};
            for(int i=0;i<4;i++){float l=i*w/4f; p.setColor(i==page?Color.rgb(30,100,132):Color.rgb(12,27,44)); c.drawRect(l,h*.90f,l+w/4f,h,p); p.setTextAlign(Paint.Align.CENTER); p.setTextSize(w*.027f); p.setFakeBoldText(i==page); p.setColor(i==page?Color.WHITE:Color.rgb(137,157,180)); c.drawText(labels[i],l+w/8f,h*.955f,p);} p.setTextAlign(Paint.Align.LEFT);p.setFakeBoldText(false);
        }

        private void title(Canvas c,String a,String b,float y){float w=getWidth();p.setColor(Color.WHITE);p.setFakeBoldText(true);p.setTextSize(w*.044f);c.drawText(a,w*.055f,y,p);p.setFakeBoldText(false);p.setColor(Color.rgb(137,157,180));p.setTextSize(w*.027f);c.drawText(b,w*.055f,y+w*.045f,p);}
        private void gauge(Canvas c,float cx,float cy,float r,float n,String v,String unit,String label){float w=getWidth();p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(w*.03f);p.setColor(Color.rgb(25,43,62));c.drawCircle(cx,cy,r,p);p.setColor(n>.7f?Color.rgb(255,105,95):n>.4f?Color.rgb(255,190,80):Color.rgb(71,214,180));p.setStrokeCap(Paint.Cap.ROUND);c.drawArc(cx-r,cy-r,cx+r,cy+r,-90,360*n,false,p);p.setStrokeCap(Paint.Cap.BUTT);p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);p.setColor(Color.WHITE);p.setFakeBoldText(true);p.setTextSize(w*.115f);c.drawText(v,cx,cy+w*.02f,p);p.setFakeBoldText(false);p.setTextSize(w*.035f);p.setColor(Color.rgb(150,170,193));c.drawText(unit,cx,cy+w*.075f,p);p.setTextSize(w*.030f);p.setColor(Color.rgb(190,205,222));c.drawText(label,cx,cy+r+w*.055f,p);p.setTextAlign(Paint.Align.LEFT);}
        private void card(Canvas c,float l,float t,float cw,float ch,String label,String value,String unit){p.setColor(Color.rgb(13,29,47));c.drawRoundRect(l,t,l+cw,t+ch,22,22,p);p.setColor(Color.rgb(130,151,176));p.setTextSize(getWidth()*.027f);c.drawText(label,l+18,t+30,p);p.setColor(Color.WHITE);p.setFakeBoldText(true);p.setTextSize(getWidth()*.038f);c.drawText(value,l+18,t+ch*.66f,p);p.setFakeBoldText(false);p.setColor(Color.rgb(130,151,176));p.setTextSize(getWidth()*.024f);c.drawText(unit,l+18,t+ch-15,p);}
        private String fmt(float v){return String.format(Locale.US,"%.1f",v);} private String fmt3(float a,float b,float c){return String.format(Locale.US,"%.1f · %.1f · %.1f",a,b,c);} private int quality(){return Math.max(0,Math.min(100,2*(rssi+100)));} private String signalLabel(){return rssi>=-50?"Excellent":rssi>=-60?"Strong":rssi>=-70?"Good":rssi>=-80?"Weak":"Very weak";} private int supported(){int n=0;if(hasMag)n++;if(hasAccel)n++;if(hasGyro)n++;if(hasLight)n++;if(hasProx)n++;if(hasPressure)n++;return n;} private String accuracyLabel(){return accuracy==SensorManager.SENSOR_STATUS_ACCURACY_HIGH?"HIGH":accuracy==SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM?"MEDIUM":accuracy==SensorManager.SENSOR_STATUS_ACCURACY_LOW?"LOW":"CALIBRATE";}
    }
}
