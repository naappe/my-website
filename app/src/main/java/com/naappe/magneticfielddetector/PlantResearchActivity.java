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
import android.view.Gravity;
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
    private SensorManager sensorManager;
    private Sensor magnetometer;
    private Sensor accelerometer;
    private Sensor lightSensor;
    private Sensor pressureSensor;

    private float mx, my, mz, magnetic;
    private float motion;
    private float light = -1f;
    private float pressure = -1f;

    private float baselineMx, baselineMy, baselineMz, baselineMag;
    private float baselineLight, baselinePressure;
    private boolean baselineReady;

    private final List<PlantSample> samples = new ArrayList<>();
    private final Handler audioHandler = new Handler(Looper.getMainLooper());
    private ToneGenerator toneGenerator;
    private boolean audioEnabled = true;
    private boolean audioLoopRunning;

    private TextView liveText;
    private TextView statusText;
    private TextView responseText;
    private EditText distanceInput;
    private EditText notesInput;
    private Button audioButton;

    private final Runnable audioLoop = new Runnable() {
        @Override public void run() {
            if (!audioLoopRunning) return;
            if (audioEnabled && baselineReady && toneGenerator != null) {
                float delta = magneticDeltaMagnitude();
                float confidence = confidenceScore(delta);
                if (confidence >= 10f && motion < 0.45f) {
                    int tone;
                    int duration;
                    long delay;
                    if (confidence >= 80f) {
                        tone = ToneGenerator.TONE_PROP_BEEP2; duration = 160; delay = 220;
                    } else if (confidence >= 50f) {
                        tone = ToneGenerator.TONE_PROP_BEEP; duration = 120; delay = 420;
                    } else if (confidence >= 25f) {
                        tone = ToneGenerator.TONE_PROP_ACK; duration = 90; delay = 700;
                    } else {
                        tone = ToneGenerator.TONE_PROP_PROMPT; duration = 70; delay = 1100;
                    }
                    toneGenerator.startTone(tone, duration);
                    audioHandler.postDelayed(this, delay);
                    return;
                }
            }
            audioHandler.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.rgb(5, 28, 18));
        getWindow().setNavigationBarColor(Color.rgb(5, 28, 18));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 85);
        setContentView(buildUi());
    }

    @Override protected void onResume() {
        super.onResume();
        register(magnetometer); register(accelerometer); register(lightSensor); register(pressureSensor);
        audioLoopRunning = true;
        audioHandler.post(audioLoop);
    }

    @Override protected void onPause() {
        sensorManager.unregisterListener(this);
        audioLoopRunning = false;
        audioHandler.removeCallbacks(audioLoop);
        if (toneGenerator != null) toneGenerator.stopTone();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (toneGenerator != null) { toneGenerator.release(); toneGenerator = null; }
        super.onDestroy();
    }

    private void register(Sensor sensor) {
        if (sensor != null) sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override public void onSensorChanged(SensorEvent event) {
        float[] v = event.values;
        switch (event.sensor.getType()) {
            case Sensor.TYPE_MAGNETIC_FIELD:
                mx = v[0]; my = v[1]; mz = v[2];
                magnetic = (float)Math.sqrt(mx*mx + my*my + mz*mz);
                break;
            case Sensor.TYPE_ACCELEROMETER:
                float m = (float)Math.sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2]);
                motion = Math.abs(m - SensorManager.GRAVITY_EARTH);
                break;
            case Sensor.TYPE_LIGHT: light = v[0]; break;
            case Sensor.TYPE_PRESSURE: pressure = v[0]; break;
        }
        updateLiveText();
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private View buildUi() {
        int pad = dp(18);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(5,28,18));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad,pad,pad,pad*2);
        scroll.addView(root);

        root.addView(title("Universal Plant Response Lab",28,true));
        root.addView(title("Anonymous samples · magnetic audio · repeatable trials",14,false));
        root.addView(space(12));

        TextView warning = title("The beep represents measured change above baseline. It does not prove plant identity, health or nutrient content.",13,false);
        warning.setTextColor(Color.rgb(255,205,90)); warning.setBackgroundColor(Color.rgb(25,55,36));
        warning.setPadding(dp(12),dp(10),dp(12),dp(10)); root.addView(warning);

        root.addView(section("Universal sample setup"));
        distanceInput = input("Distance from sensor (cm)");
        distanceInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        notesInput = input("Trial notes (optional)");
        root.addView(distanceInput); root.addView(notesInput);

        root.addView(section("Live response"));
        liveText = cardText(); root.addView(liveText);
        responseText = cardText(); root.addView(responseText);

        Button baselineButton = button("CAPTURE EMPTY-SPACE BASELINE");
        baselineButton.setOnClickListener(v -> captureBaseline()); root.addView(baselineButton);

        audioButton = button("AUDIO FEEDBACK: ON");
        audioButton.setOnClickListener(v -> toggleAudio()); root.addView(audioButton);

        Button sampleButton = button("CAPTURE ANONYMOUS SAMPLE");
        sampleButton.setOnClickListener(v -> captureSample()); root.addView(sampleButton);

        Button exportButton = button("EXPORT RESEARCH CSV");
        exportButton.setOnClickListener(v -> exportCsv()); root.addView(exportButton);

        statusText = cardText();
        statusText.setText("Keep the phone fixed. Capture empty space first, then bring the plant to the same marked distance.");
        root.addView(statusText);

        root.addView(section("Universal protocol"));
        TextView protocol = title(
                "1. Fix the phone on a non-metal support.\n"+
                "2. Enter a constant distance.\n"+
                "3. Capture empty-space baseline.\n"+
                "4. Bring any plant sample into the marked position.\n"+
                "5. Listen: faster beeps mean a larger stable change.\n"+
                "6. Capture at least three trials per sample.\n"+
                "7. Export and compare repeatability before making conclusions.",14,false);
        protocol.setTextColor(Color.rgb(205,225,212)); root.addView(protocol);

        updateLiveText();
        return scroll;
    }

    private void toggleAudio() {
        audioEnabled = !audioEnabled;
        if (!audioEnabled && toneGenerator != null) toneGenerator.stopTone();
        audioButton.setText("AUDIO FEEDBACK: " + (audioEnabled ? "ON" : "OFF"));
    }

    private void captureBaseline() {
        if (motion > 0.35f) {
            statusText.setText("Baseline rejected: phone is moving. Fix the phone and retry."); return;
        }
        baselineMx=mx; baselineMy=my; baselineMz=mz; baselineMag=magnetic;
        baselineLight=light; baselinePressure=pressure; baselineReady=true;
        statusText.setText(String.format(Locale.US,
                "Baseline ready\nVector: %.2f, %.2f, %.2f µT\nTotal: %.2f µT",baselineMx,baselineMy,baselineMz,baselineMag));
        updateLiveText();
    }

    private void captureSample() {
        if (!baselineReady) { statusText.setText("Capture empty-space baseline first."); return; }
        if (motion > 0.35f) { statusText.setText("Sample rejected: phone moved during capture."); return; }

        PlantSample s = new PlantSample();
        s.timestamp=System.currentTimeMillis(); s.id=samples.size()+1;
        s.distance=distanceInput.getText().toString().trim(); s.notes=notesInput.getText().toString().trim();
        s.mx=mx; s.my=my; s.mz=mz; s.magnetic=magnetic;
        s.vectorDelta=magneticDeltaMagnitude(); s.totalDelta=magnetic-baselineMag;
        s.motion=motion; s.light=light; s.lightDelta=valid(light,baselineLight)?light-baselineLight:Float.NaN;
        s.pressure=pressure; s.pressureDelta=valid(pressure,baselinePressure)?pressure-baselinePressure:Float.NaN;
        s.confidence=confidenceScore(s.vectorDelta);
        samples.add(s);

        statusText.setText(String.format(Locale.US,
                "Sample %03d saved\nVector change: %.2f µT\nTotal change: %+.2f µT\nResponse confidence: %.0f%%\nRepeat this exact setup at least 3 times.",
                s.id,s.vectorDelta,s.totalDelta,s.confidence));
    }

    private float magneticDeltaMagnitude() {
        if (!baselineReady) return 0f;
        float dx=mx-baselineMx, dy=my-baselineMy, dz=mz-baselineMz;
        return (float)Math.sqrt(dx*dx+dy*dy+dz*dz);
    }

    private float confidenceScore(float delta) {
        float movementPenalty = Math.min(80f, motion*120f);
        return clamp(delta*8f - movementPenalty, 0f, 100f);
    }

    private void updateLiveText() {
        if (liveText == null) return;
        float delta = magneticDeltaMagnitude();
        float confidence = confidenceScore(delta);
        liveText.setText(String.format(Locale.US,
                "Magnetic total: %.2f µT\nX / Y / Z: %.2f / %.2f / %.2f\nMotion: %.3f m/s²\nLight: %s\nPressure: %s",
                magnetic,mx,my,mz,motion,
                light>=0?String.format(Locale.US,"%.2f lux",light):"Unavailable",
                pressure>=0?String.format(Locale.US,"%.2f hPa",pressure):"Unavailable"));
        if (responseText != null) responseText.setText(baselineReady ? String.format(Locale.US,
                "Relative vector change: %.2f µT\nResponse confidence: %.0f%%\nAudio rate: %s\nSamples saved: %d",
                delta,confidence,audioRateLabel(confidence),samples.size()) :
                "No baseline yet. Audio feedback begins after baseline capture.");
    }

    private String audioRateLabel(float confidence) {
        if (!audioEnabled) return "OFF";
        if (confidence>=80) return "VERY FAST";
        if (confidence>=50) return "FAST";
        if (confidence>=25) return "MEDIUM";
        if (confidence>=10) return "SLOW";
        return "SILENT / NO SIGNIFICANT CHANGE";
    }

    private void exportCsv() {
        if (samples.isEmpty()) { statusText.setText("No samples to export."); return; }
        try {
            File dir=getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if(dir==null)throw new IllegalStateException("Storage unavailable");
            if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("Cannot create export folder");
            String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date());
            File file=new File(dir,"universal_plant_response_"+stamp+".csv");
            StringBuilder csv=new StringBuilder("timestamp,sample_id,distance_cm,mx_uT,my_uT,mz_uT,total_uT,vector_delta_uT,total_delta_uT,motion,light_lux,light_delta_lux,pressure_hPa,pressure_delta_hPa,response_confidence,notes\n");
            for(PlantSample s:samples){csv.append(s.timestamp).append(',').append(s.id).append(',').append(q(s.distance)).append(',')
                    .append(f(s.mx)).append(',').append(f(s.my)).append(',').append(f(s.mz)).append(',').append(f(s.magnetic)).append(',')
                    .append(f(s.vectorDelta)).append(',').append(f(s.totalDelta)).append(',').append(f(s.motion)).append(',')
                    .append(f(s.light)).append(',').append(f(s.lightDelta)).append(',').append(f(s.pressure)).append(',')
                    .append(f(s.pressureDelta)).append(',').append(f(s.confidence)).append(',').append(q(s.notes)).append('\n');}
            try(FileOutputStream out=new FileOutputStream(file)){out.write(csv.toString().getBytes(StandardCharsets.UTF_8));}
            statusText.setText("Dataset exported:\n"+file.getAbsolutePath());
            Toast.makeText(this,"Plant response dataset exported",Toast.LENGTH_LONG).show();
        }catch(Exception e){statusText.setText("Export failed: "+e.getMessage());}
    }

    private TextView title(String text,int size,boolean bold){TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(bold?Color.WHITE:Color.rgb(155,185,165));if(bold)v.setTypeface(null,android.graphics.Typeface.BOLD);v.setLineSpacing(0f,1.15f);return v;}
    private TextView section(String text){TextView v=title(text,15,true);v.setPadding(0,dp(18),0,dp(8));return v;}
    private EditText input(String hint){EditText v=new EditText(this);v.setHint(hint);v.setHintTextColor(Color.rgb(115,145,125));v.setTextColor(Color.WHITE);v.setSingleLine(false);v.setBackgroundColor(Color.rgb(18,48,31));v.setPadding(dp(12),dp(10),dp(12),dp(10));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(5),0,dp(5));v.setLayoutParams(p);return v;}
    private TextView cardText(){TextView v=title("",14,false);v.setTextColor(Color.rgb(220,235,225));v.setBackgroundColor(Color.rgb(14,42,27));v.setPadding(dp(14),dp(12),dp(14),dp(12));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(6),0,dp(8));v.setLayoutParams(p);return v;}
    private Button button(String text){Button b=new Button(this);b.setText(text);b.setTextColor(Color.WHITE);b.setBackgroundColor(Color.rgb(29,115,67));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(54));p.setMargins(0,dp(5),0,dp(5));b.setLayoutParams(p);return b;}
    private View space(int n){View v=new View(this);v.setLayoutParams(new LinearLayout.LayoutParams(1,dp(n)));return v;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private static boolean valid(float a,float b){return a>=0&&b>=0;}
    private static float clamp(float v,float min,float max){return Math.max(min,Math.min(max,v));}
    private static String f(float v){return Float.isNaN(v)?"":String.format(Locale.US,"%.5f",v);}
    private static String q(String value){String v=value==null?"":value.replace("\"","\"\"");return "\""+v+"\"";}

    private static final class PlantSample {
        long timestamp; int id; String distance,notes;
        float mx,my,mz,magnetic,vectorDelta,totalDelta,motion,light,lightDelta,pressure,pressureDelta,confidence;
    }
}
