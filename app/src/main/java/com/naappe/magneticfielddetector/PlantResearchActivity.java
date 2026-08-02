package com.naappe.magneticfielddetector;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class PlantResearchActivity extends Activity implements SensorEventListener {
    private SensorManager manager;
    private Sensor magnetometer, accelerometer, lightSensor, pressureSensor;
    private float mx, my, mz, magnetic, motion, light = -1f, pressure = -1f;
    private float baseMx, baseMy, baseMz, baseMag, baseLight, basePressure;
    private boolean baselineReady, audioEnabled = true, resumed;

    private final List<PlantSample> samples = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ToneGenerator tone;
    private boolean renderPending;
    private long lastBeepMs;

    private TextView liveText, responseText, statusText;
    private EditText distanceInput, notesInput;
    private Button audioButton;

    private final Runnable renderTask = new Runnable() {
        @Override public void run() {
            renderPending = false;
            if (!resumed) return;
            try { render(); } catch (RuntimeException e) {
                if (statusText != null) statusText.setText("Display recovered: " + e.getClass().getSimpleName());
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.rgb(5,28,18));
        getWindow().setNavigationBarColor(Color.rgb(5,28,18));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        manager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
        magnetometer = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        lightSensor = manager.getDefaultSensor(Sensor.TYPE_LIGHT);
        pressureSensor = manager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        createTone();
        setContentView(buildUi());
        render();
    }

    private void createTone() {
        safeReleaseTone();
        try { tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 90); }
        catch (RuntimeException e) { tone = null; }
    }

    private void safeReleaseTone() {
        if (tone == null) return;
        try { tone.stopTone(); } catch (RuntimeException ignored) { }
        try { tone.release(); } catch (RuntimeException ignored) { }
        tone = null;
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        register(magnetometer);
        register(accelerometer);
        register(lightSensor);
        register(pressureSensor);
        if (tone == null) createTone();
        scheduleRender();
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(renderTask);
        renderPending = false;
        if (manager != null) manager.unregisterListener(this);
        if (tone != null) try { tone.stopTone(); } catch (RuntimeException ignored) { }
        super.onPause();
    }

    @Override protected void onDestroy() {
        safeReleaseTone();
        super.onDestroy();
    }

    private void register(Sensor sensor) {
        if (sensor != null) manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override public void onSensorChanged(SensorEvent event) {
        try {
            float[] v = event.values;
            int type = event.sensor.getType();
            if (type == Sensor.TYPE_MAGNETIC_FIELD && v.length >= 3) {
                float nextMx=v[0], nextMy=v[1], nextMz=v[2];
                float nextMag=(float)Math.sqrt(nextMx*nextMx+nextMy*nextMy+nextMz*nextMz);
                if (Float.isFinite(nextMag) && nextMag < 10000f) {
                    mx=nextMx; my=nextMy; mz=nextMz; magnetic=nextMag;
                }
            } else if (type == Sensor.TYPE_ACCELEROMETER && v.length >= 3) {
                float g=(float)Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]);
                motion=Math.abs(g-SensorManager.GRAVITY_EARTH);
            } else if (type == Sensor.TYPE_LIGHT && v.length > 0) light=v[0];
            else if (type == Sensor.TYPE_PRESSURE && v.length > 0) pressure=v[0];
            maybeBeep();
            scheduleRender();
        } catch (RuntimeException e) {
            if (statusText != null) statusText.setText("Sensor event recovered: " + e.getClass().getSimpleName());
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void scheduleRender() {
        if (!renderPending && resumed) {
            renderPending = true;
            handler.postDelayed(renderTask, 200L);
        }
    }

    private void maybeBeep() {
        if (!audioEnabled || !baselineReady || tone == null || motion > 1.2f) return;
        float delta = vectorDelta();
        if (delta < 0.25f) return;
        float confidence = confidence(delta);
        long interval = confidence >= 80f ? 250L : confidence >= 50f ? 450L : confidence >= 25f ? 700L : 1100L;
        long now = SystemClock.elapsedRealtime();
        if (now - lastBeepMs < interval) return;
        lastBeepMs = now;
        int toneId = confidence >= 80f ? ToneGenerator.TONE_PROP_BEEP2 : ToneGenerator.TONE_PROP_BEEP;
        try { tone.startTone(toneId, 100); }
        catch (RuntimeException e) { audioEnabled = false; if (audioButton != null) audioButton.setText("AUDIO FEEDBACK: ERROR"); }
    }

    private View buildUi() {
        ScrollView scroll=new ScrollView(this); scroll.setBackgroundColor(Color.rgb(5,28,18));
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        int p=dp(18); root.setPadding(p,p,p,p*2); scroll.addView(root);
        root.addView(title("Universal Plant Response Lab",28,true));
        root.addView(title("Stable sensor capture · controlled audio feedback",14,false));
        TextView warning=title("The beep represents magnetic change above baseline, not confirmed plant detection.",13,false);
        warning.setTextColor(Color.rgb(255,205,90)); warning.setBackgroundColor(Color.rgb(25,55,36)); warning.setPadding(dp(12),dp(10),dp(12),dp(10)); root.addView(warning);
        root.addView(section("Universal sample setup"));
        distanceInput=input("Distance from sensor (cm)"); distanceInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        notesInput=input("Trial notes (optional)"); root.addView(distanceInput); root.addView(notesInput);
        root.addView(section("Live response")); liveText=card(); root.addView(liveText); responseText=card(); root.addView(responseText);
        Button baseline=button("CAPTURE EMPTY-SPACE BASELINE"); baseline.setOnClickListener(v->captureBaseline()); root.addView(baseline);
        audioButton=button("AUDIO FEEDBACK: ON"); audioButton.setOnClickListener(v->toggleAudio()); root.addView(audioButton);
        Button test=button("TEST SOUND"); test.setOnClickListener(v->testSound()); root.addView(test);
        Button capture=button("CAPTURE ANONYMOUS SAMPLE"); capture.setOnClickListener(v->captureSample()); root.addView(capture);
        Button export=button("EXPORT RESEARCH CSV"); export.setOnClickListener(v->exportCsv()); root.addView(export);
        statusText=card(); statusText.setText("Fix the phone, test sound, then capture an empty-space baseline."); root.addView(statusText);
        root.addView(title("Screen refresh is limited to 5 times per second to prevent the app closing under fast sensor updates.",13,false));
        return scroll;
    }

    private void testSound() {
        if (tone == null) createTone();
        boolean ok=false;
        if (tone != null) try { ok=tone.startTone(ToneGenerator.TONE_PROP_BEEP2,500); } catch (RuntimeException ignored) { }
        statusText.setText(ok ? "Test sound started. Increase media volume if it is quiet." : "Sound could not start. Reopen the app and check media volume.");
    }

    private void toggleAudio() {
        audioEnabled=!audioEnabled;
        if (!audioEnabled && tone != null) try { tone.stopTone(); } catch (RuntimeException ignored) { }
        audioButton.setText("AUDIO FEEDBACK: "+(audioEnabled?"ON":"OFF"));
        if (audioEnabled) testSound();
        scheduleRender();
    }

    private void captureBaseline() {
        if (motion > 0.8f) { statusText.setText("Baseline rejected: phone is moving."); return; }
        if (!Float.isFinite(magnetic) || magnetic <= 0f || magnetic > 10000f) { statusText.setText("Baseline rejected: invalid magnetic reading."); return; }
        baseMx=mx; baseMy=my; baseMz=mz; baseMag=magnetic; baseLight=light; basePressure=pressure; baselineReady=true;
        statusText.setText(String.format(Locale.US,"Baseline ready\nX/Y/Z: %.2f / %.2f / %.2f µT\nTotal: %.2f µT",baseMx,baseMy,baseMz,baseMag));
        scheduleRender();
    }

    private void captureSample() {
        if (!baselineReady) { statusText.setText("Capture the empty-space baseline first."); return; }
        if (motion > 0.8f) { statusText.setText("Sample rejected: phone moved."); return; }
        PlantSample s=new PlantSample(); s.timestamp=System.currentTimeMillis(); s.id=samples.size()+1;
        s.distance=distanceInput.getText().toString().trim(); s.notes=notesInput.getText().toString().trim();
        s.mx=mx; s.my=my; s.mz=mz; s.magnetic=magnetic; s.vectorDelta=vectorDelta(); s.totalDelta=magnetic-baseMag;
        s.motion=motion; s.light=light; s.lightDelta=valid(light,baseLight)?light-baseLight:Float.NaN;
        s.pressure=pressure; s.pressureDelta=valid(pressure,basePressure)?pressure-basePressure:Float.NaN; s.confidence=confidence(s.vectorDelta);
        samples.add(s);
        statusText.setText(String.format(Locale.US,"Sample %03d saved\nVector change: %.2f µT\nTotal change: %+.2f µT\nResponse index: %.0f%%",s.id,s.vectorDelta,s.totalDelta,s.confidence));
        scheduleRender();
    }

    private float vectorDelta() {
        if (!baselineReady) return 0f;
        float dx=mx-baseMx,dy=my-baseMy,dz=mz-baseMz;
        return (float)Math.sqrt(dx*dx+dy*dy+dz*dz);
    }

    private float confidence(float delta) { return clamp(delta*10f-Math.min(70f,motion*80f),0f,100f); }

    private void render() {
        if (liveText == null) return;
        liveText.setText(String.format(Locale.US,"Magnetic total: %.2f µT\nX / Y / Z: %.2f / %.2f / %.2f\nMotion: %.3f m/s²\nLight: %s\nPressure: %s",magnetic,mx,my,mz,motion,light>=0?String.format(Locale.US,"%.2f lux",light):"Unavailable",pressure>=0?String.format(Locale.US,"%.2f hPa",pressure):"Unavailable"));
        float delta=vectorDelta(),score=confidence(delta);
        responseText.setText(baselineReady?String.format(Locale.US,"Relative vector change: %.2f µT\nResponse index: %.0f%%\nAudio: %s\nSamples: %d",delta,score,audioEnabled?"ON":"OFF",samples.size()):"No baseline yet.");
    }

    private void exportCsv() {
        if(samples.isEmpty()){statusText.setText("No samples to export.");return;}
        try {
            File dir=getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS); if(dir==null)throw new IllegalStateException("Storage unavailable"); if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("Cannot create folder");
            File file=new File(dir,"universal_plant_response_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date())+".csv");
            StringBuilder csv=new StringBuilder("timestamp,sample_id,distance_cm,mx_uT,my_uT,mz_uT,total_uT,vector_delta_uT,total_delta_uT,motion,light_lux,light_delta_lux,pressure_hPa,pressure_delta_hPa,response_index,notes\n");
            for(PlantSample s:samples)csv.append(s.timestamp).append(',').append(s.id).append(',').append(q(s.distance)).append(',').append(f(s.mx)).append(',').append(f(s.my)).append(',').append(f(s.mz)).append(',').append(f(s.magnetic)).append(',').append(f(s.vectorDelta)).append(',').append(f(s.totalDelta)).append(',').append(f(s.motion)).append(',').append(f(s.light)).append(',').append(f(s.lightDelta)).append(',').append(f(s.pressure)).append(',').append(f(s.pressureDelta)).append(',').append(f(s.confidence)).append(',').append(q(s.notes)).append('\n');
            try(FileOutputStream out=new FileOutputStream(file)){out.write(csv.toString().getBytes(StandardCharsets.UTF_8));}
            statusText.setText("Dataset exported:\n"+file.getAbsolutePath()); Toast.makeText(this,"Dataset exported",Toast.LENGTH_LONG).show();
        } catch(Exception e){statusText.setText("Export failed: "+e.getMessage());}
    }

    private TextView title(String s,int size,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(bold?Color.WHITE:Color.rgb(155,185,165));if(bold)v.setTypeface(null,android.graphics.Typeface.BOLD);v.setLineSpacing(0,1.15f);return v;}
    private TextView section(String s){TextView v=title(s,15,true);v.setPadding(0,dp(18),0,dp(8));return v;}
    private EditText input(String hint){EditText v=new EditText(this);v.setHint(hint);v.setHintTextColor(Color.rgb(115,145,125));v.setTextColor(Color.WHITE);v.setBackgroundColor(Color.rgb(18,48,31));v.setPadding(dp(12),dp(10),dp(12),dp(10));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(5),0,dp(5));v.setLayoutParams(lp);return v;}
    private TextView card(){TextView v=title("",14,false);v.setTextColor(Color.rgb(220,235,225));v.setBackgroundColor(Color.rgb(14,42,27));v.setPadding(dp(14),dp(12),dp(14),dp(12));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(6),0,dp(8));v.setLayoutParams(lp);return v;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextColor(Color.WHITE);b.setBackgroundColor(Color.rgb(29,115,67));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(54));lp.setMargins(0,dp(5),0,dp(5));b.setLayoutParams(lp);return b;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private static boolean valid(float a,float b){return a>=0&&b>=0;}
    private static float clamp(float v,float min,float max){return Math.max(min,Math.min(max,v));}
    private static String f(float v){return Float.isNaN(v)?"":String.format(Locale.US,"%.5f",v);}
    private static String q(String s){String v=s==null?"":s.replace("\"","\"\"");return "\""+v+"\"";}

    private static final class PlantSample {
        long timestamp; int id; String distance,notes;
        float mx,my,mz,magnetic,vectorDelta,totalDelta,motion,light,lightDelta,pressure,pressureDelta,confidence;
    }
}
