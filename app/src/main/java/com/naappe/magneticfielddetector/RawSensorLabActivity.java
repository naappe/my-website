package com.naappe.magneticfielddetector;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.Locale;

public class RawSensorLabActivity extends Activity implements SensorEventListener {
    private SensorManager manager;
    private Sensor calibrated, uncalibrated, rotationVector, accelerometer;
    private TextView liveOutput, algorithmOutput, statusOutput;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean uiScheduled;

    private float cx, cy, cz, ux, uy, uz, bx, by, bz, ax, ay, az;
    private final float[] rotationMatrix = new float[9];
    private boolean hasRotation;
    private long lastCalNs, lastUncalNs;
    private float calHz, uncalHz;

    private boolean baselineReady, collectingNoise;
    private long noiseStartMs;
    private int noiseCount;
    private double noiseSum, noiseSumSq;
    private float baseCx, baseCy, baseCz, baseUx, baseUy, baseUz;
    private float baseWx, baseWy, baseWz, baseNoiseMean, baseNoiseStd;

    private final ArrayDeque<Float> magnitudes = new ArrayDeque<>();
    private final ArrayDeque<Long> times = new ArrayDeque<>();

    private final Runnable renderTask = new Runnable() {
        @Override public void run() {
            uiScheduled = false;
            try { render(); }
            catch (Exception e) {
                if (statusOutput != null) statusOutput.setText("Display error recovered: " + e.getClass().getSimpleName());
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.rgb(8,16,28));
        getWindow().setNavigationBarColor(Color.rgb(8,16,28));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        manager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
        calibrated = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        uncalibrated = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED);
        rotationVector = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        setContentView(buildUi());
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        register(calibrated, SensorManager.SENSOR_DELAY_GAME);
        register(uncalibrated, SensorManager.SENSOR_DELAY_GAME);
        register(rotationVector, SensorManager.SENSOR_DELAY_GAME);
        register(accelerometer, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override protected void onPause() {
        uiHandler.removeCallbacks(renderTask);
        uiScheduled = false;
        if (manager != null) manager.unregisterListener(this);
        super.onPause();
    }

    private void register(Sensor sensor, int delay) {
        if (sensor != null) manager.registerListener(this, sensor, delay);
    }

    @Override public void onSensorChanged(SensorEvent event) {
        try {
            int type = event.sensor.getType();
            if (type == Sensor.TYPE_MAGNETIC_FIELD && event.values.length >= 3) {
                cx=event.values[0]; cy=event.values[1]; cz=event.values[2];
                if (lastCalNs != 0) calHz=smooth(calHz,1_000_000_000f/Math.max(1L,event.timestamp-lastCalNs));
                lastCalNs=event.timestamp;
                addSample(magnitude(cx,cy,cz));
                updateNoise();
            } else if (type == Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED && event.values.length >= 3) {
                ux=event.values[0]; uy=event.values[1]; uz=event.values[2];
                if (event.values.length >= 6) { bx=event.values[3]; by=event.values[4]; bz=event.values[5]; }
                if (lastUncalNs != 0) uncalHz=smooth(uncalHz,1_000_000_000f/Math.max(1L,event.timestamp-lastUncalNs));
                lastUncalNs=event.timestamp;
            } else if (type == Sensor.TYPE_ROTATION_VECTOR) {
                try {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix,event.values);
                    hasRotation=true;
                } catch (RuntimeException ignored) { hasRotation=false; }
            } else if (type == Sensor.TYPE_ACCELEROMETER && event.values.length >= 3) {
                ax=event.values[0]; ay=event.values[1]; az=event.values[2];
            }
            scheduleRender();
        } catch (RuntimeException e) {
            if (statusOutput != null) statusOutput.setText("Sensor event recovered: " + e.getClass().getSimpleName());
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor,int accuracy) { }

    private void scheduleRender() {
        if (!uiScheduled) {
            uiScheduled=true;
            uiHandler.postDelayed(renderTask,200L);
        }
    }

    private ScrollView buildUi() {
        ScrollView scroll=new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(8,16,28));
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p=dp(18); root.setPadding(p,p,p,p*2);
        TextView title=text("Magnetic Algorithm Laboratory",28,Color.WHITE);
        title.setTypeface(null,android.graphics.Typeface.BOLD); root.addView(title);
        root.addView(text("Independent raw, corrected, noise and rotation-compensated analysis.",14,Color.rgb(160,180,205)));
        liveOutput=card(); root.addView(liveOutput);
        Button baseline=button("CAPTURE 3-SECOND NOISE BASELINE"); baseline.setOnClickListener(v->startBaseline()); root.addView(baseline);
        Button reset=button("RESET BASELINE & HISTORY"); reset.setOnClickListener(v->reset()); root.addView(reset);
        algorithmOutput=card(); root.addView(algorithmOutput);
        statusOutput=card(); statusOutput.setText("Keep the phone fixed, then capture the baseline."); root.addView(statusOutput);
        root.addView(text("The app now limits screen refresh to 5 times per second so high-rate sensor events cannot overload and close the app.",13,Color.rgb(255,205,90)));
        scroll.addView(root); return scroll;
    }

    private void startBaseline() {
        collectingNoise=true; baselineReady=false; noiseStartMs=SystemClock.elapsedRealtime();
        noiseCount=0; noiseSum=0; noiseSumSq=0;
        statusOutput.setText("Collecting baseline for 3 seconds. Do not move the phone.");
    }

    private void updateNoise() {
        if (!collectingNoise) return;
        float v=magnitude(cx,cy,cz); noiseCount++; noiseSum+=v; noiseSumSq+=v*v;
        if (SystemClock.elapsedRealtime()-noiseStartMs>=3000 && noiseCount>=10) {
            collectingNoise=false;
            baseNoiseMean=(float)(noiseSum/noiseCount);
            baseNoiseStd=(float)Math.sqrt(Math.max(0,noiseSumSq/noiseCount-baseNoiseMean*baseNoiseMean));
            baseCx=cx; baseCy=cy; baseCz=cz; baseUx=ux; baseUy=uy; baseUz=uz;
            float[] w=world(cx,cy,cz); baseWx=w[0]; baseWy=w[1]; baseWz=w[2]; baselineReady=true;
            statusOutput.setText(String.format(Locale.US,"Baseline ready: %d samples\nMean %.4f µT · noise σ %.4f µT",noiseCount,baseNoiseMean,baseNoiseStd));
        }
    }

    private void reset() {
        baselineReady=false; collectingNoise=false; magnitudes.clear(); times.clear();
        statusOutput.setText("Baseline and history cleared."); render();
    }

    private void addSample(float v) {
        magnitudes.addLast(v); times.addLast(SystemClock.elapsedRealtimeNanos());
        while (magnitudes.size()>160) magnitudes.removeFirst();
        while (times.size()>160) times.removeFirst();
    }

    private void render() {
        float calMag=magnitude(cx,cy,cz), rawMag=magnitude(ux,uy,uz);
        float qx=ux-bx,qy=uy-by,qz=uz-bz, correctedMag=magnitude(qx,qy,qz);
        float gravity=magnitude(ax,ay,az);
        liveOutput.setText(String.format(Locale.US,
                "STREAM STATUS\nCalibrated: %s · %.1f Hz\nUncalibrated: %s · %.1f Hz\nRotation vector: %s\n\nCalibrated X/Y/Z: %.3f / %.3f / %.3f\nRaw X/Y/Z: %.3f / %.3f / %.3f\nBias X/Y/Z: %.3f / %.3f / %.3f\n\nCalibrated magnitude: %.3f µT\nRaw magnitude: %.3f µT\nBias-corrected magnitude: %.3f µT\nGravity: %.3f m/s²",
                yes(calibrated),calHz,yes(uncalibrated),uncalHz,yes(rotationVector),cx,cy,cz,ux,uy,uz,bx,by,bz,calMag,rawMag,correctedMag,gravity));
        if (!baselineReady) {
            algorithmOutput.setText(collectingNoise?"ALGORITHMS\nBaseline collection in progress…":"ALGORITHMS\nCapture a baseline to activate analysis.");
            return;
        }
        float calDelta=magnitude(cx-baseCx,cy-baseCy,cz-baseCz);
        float rawDelta=magnitude(ux-baseUx,uy-baseUy,uz-baseUz);
        float correctedDelta=magnitude(qx-baseCx,qy-baseCy,qz-baseCz);
        float[] w=world(cx,cy,cz); float worldDelta=magnitude(w[0]-baseWx,w[1]-baseWy,w[2]-baseWz);
        float magDelta=Math.abs(calMag-baseNoiseMean);
        float sigma=Math.max(baseNoiseStd,Math.max(resolution(calibrated),0.001f));
        float z=magDelta/sigma; float freq=frequency();
        String evidence=z>=5?"STRONG":z>=3?"ABOVE NOISE":z>=2?"BORDERLINE":"WITHIN NOISE";
        String movement=Math.abs(gravity-SensorManager.GRAVITY_EARTH)>.30f?"HIGH":"LOW";
        algorithmOutput.setText(String.format(Locale.US,
                "ALGORITHM RESULTS\n\nCalibrated delta: %.4f µT\nRaw delta: %.4f µT\nBias-corrected delta: %.4f µT\nWorld-coordinate delta: %.4f µT\nMagnitude change: %.4f µT\nNoise score: %.2f σ · %s\nEstimated low frequency: %.2f Hz\nMovement risk: %s",
                calDelta,rawDelta,correctedDelta,worldDelta,magDelta,z,evidence,freq,movement));
    }

    private float frequency() {
        if (magnitudes.size()<20 || magnitudes.size()!=times.size()) return 0;
        float mean=0; for(float v:magnitudes) mean+=v; mean/=magnitudes.size();
        int crossings=0; Float prev=null; for(float v:magnitudes){float c=v-mean;if(prev!=null&&prev<=0&&c>0)crossings++;prev=c;}
        Long first=times.peekFirst(),last=times.peekLast(); if(first==null||last==null)return 0;
        float sec=(last-first)/1_000_000_000f; return sec>0?crossings/sec:0;
    }

    private float[] world(float x,float y,float z) {
        if(!hasRotation)return new float[]{x,y,z};
        return new float[]{rotationMatrix[0]*x+rotationMatrix[1]*y+rotationMatrix[2]*z,
                rotationMatrix[3]*x+rotationMatrix[4]*y+rotationMatrix[5]*z,
                rotationMatrix[6]*x+rotationMatrix[7]*y+rotationMatrix[8]*z};
    }

    private TextView card(){TextView v=text("",14,Color.rgb(220,232,245));v.setBackgroundColor(Color.rgb(15,31,50));v.setPadding(dp(14),dp(14),dp(14),dp(14));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(14),0,0);v.setLayoutParams(lp);return v;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextColor(Color.WHITE);b.setBackgroundColor(Color.rgb(32,105,150));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(54));lp.setMargins(0,dp(8),0,0);b.setLayoutParams(lp);return b;}
    private TextView text(String s,int size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setLineSpacing(0,1.18f);return v;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private static float magnitude(float x,float y,float z){return(float)Math.sqrt(x*x+y*y+z*z);}
    private static float smooth(float a,float b){return a==0?b:a*.9f+b*.1f;}
    private static String yes(Sensor s){return s==null?"NO":"YES";}
    private static float resolution(Sensor s){return s==null?0:s.getResolution();}
}
