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

            long delay = 550L;
            if (audioEnabled && baselineReady && toneGenerator != null) {
                float delta = magneticDeltaMagnitude();
                float confidence = confidenceScore(delta);

                if (delta >= 0.15f) {
                    int tone = ToneGenerator.TONE_PROP_BEEP;
                    int duration = 90;

                    if (confidence >= 80f) {
                        tone = ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD;
                        duration = 180;
                        delay = 220L;
                    } else if (confidence >= 50f) {
                        tone = ToneGenerator.TONE_PROP_BEEP2;
                        duration = 150;
                        delay = 380L;
                    } else if (confidence >= 25f) {
                        tone = ToneGenerator.TONE_PROP_BEEP;
                        duration = 120;
                        delay = 620L;
                    } else {
                        tone = ToneGenerator.TONE_PROP_ACK;
                        duration = 100;
                        delay = 950L;
                    }

                    try {
                        toneGenerator.startTone(tone, duration);
                    } catch (RuntimeException ignored) {
                        recreateToneGenerator();
                    }
                }
            }
            audioHandler.postDelayed(this, delay);
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
        recreateToneGenerator();
        setContentView(buildUi());
    }

    private void recreateToneGenerator() {
        try {
            if (toneGenerator != null) toneGenerator.release();
        } catch (RuntimeException ignored) {
        }
        try {
            toneGenerator = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
        } catch (RuntimeException firstFailure) {
            try {
                toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
            } catch (RuntimeException secondFailure) {
                toneGenerator = null;
            }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        register(magnetometer);
        register(accelerometer);
        register(lightSensor);
        register(pressureSensor);
        audioLoopRunning = true;
        audioHandler.removeCallbacks(audioLoop);
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
        if (toneGenerator != null) {
            toneGenerator.release();
            toneGenerator = null;
        }
        super.onDestroy();
    }

    private void register(Sensor sensor) {
        if (sensor != null) {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override public void onSensorChanged(SensorEvent event) {
        float[] v = event.values;
        switch (event.sensor.getType()) {
            case Sensor.TYPE_MAGNETIC_FIELD:
                mx = v[0];
                my = v[1];
                mz = v[2];
                magnetic = (float) Math.sqrt(mx * mx + my * my + mz * mz);
                break;
            case Sensor.TYPE_ACCELEROMETER:
                float magnitude = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
                motion = Math.abs(magnitude - SensorManager.GRAVITY_EARTH);
                break;
            case Sensor.TYPE_LIGHT:
                light = v[0];
                break;
            case Sensor.TYPE_PRESSURE:
                pressure = v[0];
                break;
        }
        updateLiveText();
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private View buildUi() {
        int pad = dp(18);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(5, 28, 18));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad * 2);
        scroll.addView(root);

        root.addView(title("Universal Plant Response Lab", 28, true));
        root.addView(title("Anonymous samples · magnetic audio · repeatable trials", 14, false));
        root.addView(space(12));

        TextView warning = title(
                "The beep represents measured change above baseline. It does not prove plant identity, health or nutrient content.",
                13,
                false
        );
        warning.setTextColor(Color.rgb(255, 205, 90));
        warning.setBackgroundColor(Color.rgb(25, 55, 36));
        warning.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(warning);

        root.addView(section("Universal sample setup"));
        distanceInput = input("Distance from sensor (cm)");
        distanceInput.setInputType(
                android.text.InputType.TYPE_CLASS_NUMBER |
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        );
        notesInput = input("Trial notes (optional)");
        root.addView(distanceInput);
        root.addView(notesInput);

        root.addView(section("Live response"));
        liveText = cardText();
        root.addView(liveText);
        responseText = cardText();
        root.addView(responseText);

        Button baselineButton = button("CAPTURE EMPTY-SPACE BASELINE");
        baselineButton.setOnClickListener(v -> captureBaseline());
        root.addView(baselineButton);

        audioButton = button("AUDIO FEEDBACK: ON");
        audioButton.setOnClickListener(v -> toggleAudio());
        root.addView(audioButton);

        Button testSoundButton = button("TEST SOUND");
        testSoundButton.setOnClickListener(v -> testSound());
        root.addView(testSoundButton);

        Button sampleButton = button("CAPTURE ANONYMOUS SAMPLE");
        sampleButton.setOnClickListener(v -> captureSample());
        root.addView(sampleButton);

        Button exportButton = button("EXPORT RESEARCH CSV");
        exportButton.setOnClickListener(v -> exportCsv());
        root.addView(exportButton);

        statusText = cardText();
        statusText.setText(
                "Press TEST SOUND first. Then capture empty space and move the sample near the fixed sensor position."
        );
        root.addView(statusText);

        root.addView(section("Universal protocol"));
        TextView protocol = title(
                "1. Turn up alarm/media volume and press TEST SOUND.\n" +
                "2. Fix the phone on a non-metal support.\n" +
                "3. Enter a constant distance.\n" +
                "4. Capture empty-space baseline.\n" +
                "5. Bring any plant sample into the marked position.\n" +
                "6. Faster beeps mean a larger stable magnetic-vector change.\n" +
                "7. Capture at least three trials and export the data.",
                14,
                false
        );
        protocol.setTextColor(Color.rgb(205, 225, 212));
        root.addView(protocol);

        updateLiveText();
        return scroll;
    }

    private void testSound() {
        if (toneGenerator == null) recreateToneGenerator();
        boolean started = false;
        if (toneGenerator != null) {
            try {
                started = toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 700);
            } catch (RuntimeException ignored) {
                recreateToneGenerator();
            }
        }
        statusText.setText(
                started
                        ? "Test tone played. If you cannot hear it, increase alarm/media volume and disable Silent or Do Not Disturb."
                        : "Audio generator could not start. Increase volume, disable Silent/DND, then reopen the app."
        );
    }

    private void toggleAudio() {
        audioEnabled = !audioEnabled;
        if (!audioEnabled && toneGenerator != null) toneGenerator.stopTone();
        audioButton.setText("AUDIO FEEDBACK: " + (audioEnabled ? "ON" : "OFF"));
        if (audioEnabled) testSound();
        updateLiveText();
    }

    private void captureBaseline() {
        if (motion > 0.55f) {
            statusText.setText("Baseline rejected: phone is moving. Fix the phone and retry.");
            return;
        }
        baselineMx = mx;
        baselineMy = my;
        baselineMz = mz;
        baselineMag = magnetic;
        baselineLight = light;
        baselinePressure = pressure;
        baselineReady = true;

        statusText.setText(String.format(
                Locale.US,
                "Baseline ready\nVector: %.2f, %.2f, %.2f µT\nTotal: %.2f µT\nMove a sample near the sensor; audio starts at about 0.15 µT vector change.",
                baselineMx,
                baselineMy,
                baselineMz,
                baselineMag
        ));
        testSound();
        updateLiveText();
    }

    private void captureSample() {
        if (!baselineReady) {
            statusText.setText("Capture empty-space baseline first.");
            return;
        }
        if (motion > 0.55f) {
            statusText.setText("Sample rejected: phone moved during capture.");
            return;
        }

        PlantSample sample = new PlantSample();
        sample.timestamp = System.currentTimeMillis();
        sample.id = samples.size() + 1;
        sample.distance = distanceInput.getText().toString().trim();
        sample.notes = notesInput.getText().toString().trim();
        sample.mx = mx;
        sample.my = my;
        sample.mz = mz;
        sample.magnetic = magnetic;
        sample.vectorDelta = magneticDeltaMagnitude();
        sample.totalDelta = magnetic - baselineMag;
        sample.motion = motion;
        sample.light = light;
        sample.lightDelta = valid(light, baselineLight) ? light - baselineLight : Float.NaN;
        sample.pressure = pressure;
        sample.pressureDelta = valid(pressure, baselinePressure)
                ? pressure - baselinePressure
                : Float.NaN;
        sample.confidence = confidenceScore(sample.vectorDelta);
        samples.add(sample);

        statusText.setText(String.format(
                Locale.US,
                "Sample %03d saved\nVector change: %.2f µT\nTotal change: %+.2f µT\nResponse confidence: %.0f%%\nRepeat this exact setup at least 3 times.",
                sample.id,
                sample.vectorDelta,
                sample.totalDelta,
                sample.confidence
        ));
    }

    private float magneticDeltaMagnitude() {
        if (!baselineReady) return 0f;
        float dx = mx - baselineMx;
        float dy = my - baselineMy;
        float dz = mz - baselineMz;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private float confidenceScore(float delta) {
        float movementPenalty = Math.min(70f, motion * 80f);
        return clamp(delta * 10f - movementPenalty, 0f, 100f);
    }

    private void updateLiveText() {
        if (liveText == null) return;

        float delta = magneticDeltaMagnitude();
        float confidence = confidenceScore(delta);

        liveText.setText(String.format(
                Locale.US,
                "Magnetic total: %.2f µT\nX / Y / Z: %.2f / %.2f / %.2f\nMotion: %.3f m/s²\nLight: %s\nPressure: %s",
                magnetic,
                mx,
                my,
                mz,
                motion,
                light >= 0 ? String.format(Locale.US, "%.2f lux", light) : "Unavailable",
                pressure >= 0 ? String.format(Locale.US, "%.2f hPa", pressure) : "Unavailable"
        ));

        if (responseText != null) {
            responseText.setText(
                    baselineReady
                            ? String.format(
                                    Locale.US,
                                    "Relative vector change: %.2f µT\nResponse confidence: %.0f%%\nAudio rate: %s\nSamples saved: %d",
                                    delta,
                                    confidence,
                                    audioRateLabel(delta, confidence),
                                    samples.size()
                            )
                            : "No baseline yet. Press TEST SOUND, then capture the empty-space baseline."
            );
        }
    }

    private String audioRateLabel(float delta, float confidence) {
        if (!audioEnabled) return "OFF";
        if (delta < 0.15f) return "SILENT / BELOW THRESHOLD";
        if (confidence >= 80f) return "VERY FAST";
        if (confidence >= 50f) return "FAST";
        if (confidence >= 25f) return "MEDIUM";
        return "SLOW";
    }

    private void exportCsv() {
        if (samples.isEmpty()) {
            statusText.setText("No samples to export.");
            return;
        }

        try {
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (dir == null) throw new IllegalStateException("Storage unavailable");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("Cannot create export folder");
            }

            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File file = new File(dir, "universal_plant_response_" + stamp + ".csv");

            StringBuilder csv = new StringBuilder(
                    "timestamp,sample_id,distance_cm,mx_uT,my_uT,mz_uT,total_uT,vector_delta_uT,total_delta_uT,motion,light_lux,light_delta_lux,pressure_hPa,pressure_delta_hPa,response_confidence,notes\n"
            );

            for (PlantSample sample : samples) {
                csv.append(sample.timestamp).append(',')
                        .append(sample.id).append(',')
                        .append(q(sample.distance)).append(',')
                        .append(f(sample.mx)).append(',')
                        .append(f(sample.my)).append(',')
                        .append(f(sample.mz)).append(',')
                        .append(f(sample.magnetic)).append(',')
                        .append(f(sample.vectorDelta)).append(',')
                        .append(f(sample.totalDelta)).append(',')
                        .append(f(sample.motion)).append(',')
                        .append(f(sample.light)).append(',')
                        .append(f(sample.lightDelta)).append(',')
                        .append(f(sample.pressure)).append(',')
                        .append(f(sample.pressureDelta)).append(',')
                        .append(f(sample.confidence)).append(',')
                        .append(q(sample.notes)).append('\n');
            }

            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(csv.toString().getBytes(StandardCharsets.UTF_8));
            }

            statusText.setText("Dataset exported:\n" + file.getAbsolutePath());
            Toast.makeText(this, "Plant response dataset exported", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            statusText.setText("Export failed: " + e.getMessage());
        }
    }

    private TextView title(String text, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(bold ? Color.WHITE : Color.rgb(155, 185, 165));
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setLineSpacing(0f, 1.15f);
        return view;
    }

    private TextView section(String text) {
        TextView view = title(text, 15, true);
        view.setPadding(0, dp(18), 0, dp(8));
        return view;
    }

    private EditText input(String hint) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setHintTextColor(Color.rgb(115, 145, 125));
        view.setTextColor(Color.WHITE);
        view.setSingleLine(false);
        view.setBackgroundColor(Color.rgb(18, 48, 31));
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(5), 0, dp(5));
        view.setLayoutParams(params);
        return view;
    }

    private TextView cardText() {
        TextView view = title("", 14, false);
        view.setTextColor(Color.rgb(220, 235, 225));
        view.setBackgroundColor(Color.rgb(14, 42, 27));
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(6), 0, dp(8));
        view.setLayoutParams(params);
        return view;
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.rgb(29, 115, 67));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(54));
        params.setMargins(0, dp(5), 0, dp(5));
        button.setLayoutParams(params);
        return button;
    }

    private View space(int heightDp) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(heightDp)));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static boolean valid(float a, float b) {
        return a >= 0 && b >= 0;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String f(float value) {
        return Float.isNaN(value) ? "" : String.format(Locale.US, "%.5f", value);
    }

    private static String q(String value) {
        String safe = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + safe + "\"";
    }

    private static final class PlantSample {
        long timestamp;
        int id;
        String distance;
        String notes;
        float mx;
        float my;
        float mz;
        float magnetic;
        float vectorDelta;
        float totalDelta;
        float motion;
        float light;
        float lightDelta;
        float pressure;
        float pressureDelta;
        float confidence;
    }
}
