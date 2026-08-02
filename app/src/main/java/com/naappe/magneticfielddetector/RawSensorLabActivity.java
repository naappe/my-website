package com.naappe.magneticfielddetector;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

public class RawSensorLabActivity extends Activity implements SensorEventListener {
    private SensorManager manager;
    private Sensor calibrated;
    private Sensor uncalibrated;
    private Sensor rotationVector;
    private Sensor accelerometer;

    private TextView liveOutput;
    private TextView algorithmOutput;
    private TextView statusOutput;

    private float cx, cy, cz;
    private float ux, uy, uz;
    private float bx, by, bz;
    private float ax, ay, az;
    private final float[] rotationMatrix = new float[9];
    private boolean hasRotation;

    private long lastCalNs, lastUncalNs;
    private float calHz, uncalHz;
    private int calSamples, uncalSamples;

    private boolean baselineReady;
    private float baseCx, baseCy, baseCz;
    private float baseUx, baseUy, baseUz;
    private float baseWx, baseWy, baseWz;
    private float baseNoiseMean;
    private float baseNoiseStd;

    private boolean collectingNoise;
    private long noiseStartMs;
    private int noiseCount;
    private double noiseSum;
    private double noiseSumSq;

    private final Deque<Float> magnitudeHistory = new ArrayDeque<>();
    private final Deque<Long> timeHistory = new ArrayDeque<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.rgb(8, 16, 28));
        getWindow().setNavigationBarColor(Color.rgb(8, 16, 28));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        manager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        calibrated = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        uncalibrated = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED);
        rotationVector = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        setContentView(buildUi());
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        register(calibrated, SensorManager.SENSOR_DELAY_FASTEST);
        register(uncalibrated, SensorManager.SENSOR_DELAY_FASTEST);
        register(rotationVector, SensorManager.SENSOR_DELAY_GAME);
        register(accelerometer, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override protected void onPause() {
        manager.unregisterListener(this);
        super.onPause();
    }

    private void register(Sensor sensor, int delay) {
        if (sensor != null) manager.registerListener(this, sensor, delay);
    }

    @Override public void onSensorChanged(SensorEvent event) {
        int type = event.sensor.getType();
        if (type == Sensor.TYPE_MAGNETIC_FIELD) {
            cx = event.values[0]; cy = event.values[1]; cz = event.values[2];
            calSamples++;
            if (lastCalNs != 0L) calHz = smooth(calHz, 1_000_000_000f / Math.max(1L, event.timestamp - lastCalNs));
            lastCalNs = event.timestamp;
            addFrequencySample(magnitude(cx, cy, cz));
            updateNoiseCollection();
        } else if (type == Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED) {
            ux = event.values[0]; uy = event.values[1]; uz = event.values[2];
            if (event.values.length >= 6) {
                bx = event.values[3]; by = event.values[4]; bz = event.values[5];
            }
            uncalSamples++;
            if (lastUncalNs != 0L) uncalHz = smooth(uncalHz, 1_000_000_000f / Math.max(1L, event.timestamp - lastUncalNs));
            lastUncalNs = event.timestamp;
        } else if (type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
            hasRotation = true;
        } else if (type == Sensor.TYPE_ACCELEROMETER) {
            ax = event.values[0]; ay = event.values[1]; az = event.values[2];
        }
        render();
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(8, 16, 28));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18);
        root.setPadding(p, p, p, p * 2);

        TextView title = text("Magnetic Algorithm Laboratory", 28, Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);
        root.addView(text("Runs independent algorithms on the lowest-level Android magnetic streams.", 14, Color.rgb(160, 180, 205)));
        root.addView(text("It compares raw, bias-corrected, baseline, noise-normalized and world-coordinate results. It does not alter chip firmware.", 13, Color.rgb(255, 205, 90)));

        liveOutput = card();
        root.addView(liveOutput);

        Button baseline = button("CAPTURE 3-SECOND NOISE BASELINE");
        baseline.setOnClickListener(v -> startNoiseBaseline());
        root.addView(baseline);

        Button reset = button("RESET BASELINE & HISTORY");
        reset.setOnClickListener(v -> resetAnalysis());
        root.addView(reset);

        algorithmOutput = card();
        root.addView(algorithmOutput);

        statusOutput = card();
        statusOutput.setText("Keep the phone fixed in open space, then capture the noise baseline.");
        root.addView(statusOutput);

        root.addView(text("How to test\n1. Fix the phone so it cannot rotate.\n2. Capture the 3-second baseline.\n3. Bring a test object to a marked distance without touching the phone.\n4. Compare the five algorithms.\n5. Repeat three times. A real response should exceed baseline noise and repeat.", 14, Color.rgb(190, 210, 230)));

        scroll.addView(root);
        return scroll;
    }

    private void startNoiseBaseline() {
        collectingNoise = true;
        baselineReady = false;
        noiseStartMs = SystemClock.elapsedRealtime();
        noiseCount = 0;
        noiseSum = 0;
        noiseSumSq = 0;
        statusOutput.setText("Collecting 3-second baseline. Do not move or rotate the phone.");
    }

    private void updateNoiseCollection() {
        if (!collectingNoise) return;
        float value = magnitude(cx, cy, cz);
        noiseCount++;
        noiseSum += value;
        noiseSumSq += value * value;
        long elapsed = SystemClock.elapsedRealtime() - noiseStartMs;
        if (elapsed >= 3000L && noiseCount >= 10) {
            collectingNoise = false;
            baseNoiseMean = (float)(noiseSum / noiseCount);
            double variance = Math.max(0d, noiseSumSq / noiseCount - baseNoiseMean * baseNoiseMean);
            baseNoiseStd = (float)Math.sqrt(variance);
            baseCx = cx; baseCy = cy; baseCz = cz;
            baseUx = ux; baseUy = uy; baseUz = uz;
            float[] world = worldVector(cx, cy, cz);
            baseWx = world[0]; baseWy = world[1]; baseWz = world[2];
            baselineReady = true;
            statusOutput.setText(String.format(Locale.US,
                    "Baseline ready from %d samples\nMean: %.4f µT\nNoise σ: %.4f µT\nMove only the test object; keep phone position and orientation fixed.",
                    noiseCount, baseNoiseMean, baseNoiseStd));
        }
    }

    private void resetAnalysis() {
        baselineReady = false;
        collectingNoise = false;
        magnitudeHistory.clear();
        timeHistory.clear();
        baseNoiseMean = 0;
        baseNoiseStd = 0;
        statusOutput.setText("Baseline and frequency history cleared.");
        render();
    }

    private void addFrequencySample(float magnitude) {
        magnitudeHistory.addLast(magnitude);
        timeHistory.addLast(SystemClock.elapsedRealtimeNanos());
        while (magnitudeHistory.size() > 256) magnitudeHistory.removeFirst();
        while (timeHistory.size() > 256) timeHistory.removeFirst();
    }

    private void render() {
        if (liveOutput == null) return;

        float calMag = magnitude(cx, cy, cz);
        float rawMag = magnitude(ux, uy, uz);
        float correctedX = ux - bx;
        float correctedY = uy - by;
        float correctedZ = uz - bz;
        float correctedMag = magnitude(correctedX, correctedY, correctedZ);
        float residual = magnitude(correctedX - cx, correctedY - cy, correctedZ - cz);
        float gravity = magnitude(ax, ay, az);

        liveOutput.setText(String.format(Locale.US,
                "HARDWARE STREAMS\n" +
                "Calibrated: %s · %.1f Hz\n" +
                "Uncalibrated: %s · %.1f Hz\n" +
                "Rotation vector: %s\n" +
                "Sensor: %s\nVendor: %s\nResolution: %.4f µT\n\n" +
                "Calibrated X/Y/Z: %.3f / %.3f / %.3f\n" +
                "Raw X/Y/Z: %.3f / %.3f / %.3f\n" +
                "Estimated bias: %.3f / %.3f / %.3f\n" +
                "Calibrated magnitude: %.3f µT\nRaw magnitude: %.3f µT\nBias-corrected magnitude: %.3f µT\n" +
                "Corrected-vs-calibrated residual: %.3f µT\nGravity magnitude: %.3f m/s²",
                yes(calibrated), calHz, yes(uncalibrated), uncalHz, yes(rotationVector),
                name(calibrated), vendor(calibrated), resolution(calibrated),
                cx, cy, cz, ux, uy, uz, bx, by, bz,
                calMag, rawMag, correctedMag, residual, gravity));

        if (algorithmOutput == null) return;
        if (!baselineReady) {
            algorithmOutput.setText(collectingNoise
                    ? "ALGORITHMS\nBaseline collection in progress…"
                    : "ALGORITHMS\nCapture a baseline to activate independent comparison.");
            return;
        }

        float calibratedDelta = magnitude(cx - baseCx, cy - baseCy, cz - baseCz);
        float rawDelta = magnitude(ux - baseUx, uy - baseUy, uz - baseUz);
        float correctedDelta = magnitude(correctedX - baseCx, correctedY - baseCy, correctedZ - baseCz);
        float[] world = worldVector(cx, cy, cz);
        float worldDelta = magnitude(world[0] - baseWx, world[1] - baseWy, world[2] - baseWz);
        float magnitudeDelta = Math.abs(calMag - baseNoiseMean);
        float sigma = Math.max(baseNoiseStd, Math.max(resolution(calibrated), 0.001f));
        float zScore = magnitudeDelta / sigma;
        float frequency = estimateFrequencyHz();
        String evidence = zScore >= 5f ? "STRONG ABOVE NOISE" : zScore >= 3f ? "ABOVE NOISE" : zScore >= 2f ? "BORDERLINE" : "NOT ABOVE NOISE";
        String motionRisk = Math.abs(gravity - SensorManager.GRAVITY_EARTH) > 0.25f ? "HIGH" : "LOW";

        algorithmOutput.setText(String.format(Locale.US,
                "INDEPENDENT ALGORITHMS\n\n" +
                "1 · CALIBRATED VECTOR DELTA\n%.4f µT\n\n" +
                "2 · RAW VECTOR DELTA\n%.4f µT\n\n" +
                "3 · BIAS-CORRECTED DELTA\n%.4f µT\n\n" +
                "4 · WORLD-COORDINATE DELTA\n%.4f µT · rotation compensation %s\n\n" +
                "5 · NOISE-NORMALIZED MAGNITUDE\nΔ %.4f µT · %.2f σ · %s\n\n" +
                "LOW-FREQUENCY OSCILLATION ESTIMATE\n%.2f Hz · magnetometer-band estimate only\n\n" +
                "Movement contamination risk: %s\n" +
                "Interpretation: %s",
                calibratedDelta,
                rawDelta,
                correctedDelta,
                worldDelta, hasRotation ? "ON" : "UNAVAILABLE",
                magnitudeDelta, zScore, evidence,
                frequency,
                motionRisk,
                interpretation(calibratedDelta, rawDelta, correctedDelta, worldDelta, zScore, frequency, motionRisk)));
    }

    private String interpretation(float cal, float raw, float corrected, float world, float z, float frequency, String motionRisk) {
        if ("HIGH".equals(motionRisk)) return "Phone movement can explain part of this response.";
        if (z < 2f) return "Current change is within baseline noise.";
        float agreement = maxDifference(cal, corrected, world);
        if (z >= 5f && agreement < Math.max(1f, cal * 0.25f)) {
            if (frequency > 1f) return "Repeatable candidate with oscillating low-frequency magnetic component.";
            return "Strong candidate response: several algorithms agree above noise.";
        }
        if (raw > cal * 2f) return "Raw stream changed much more than calibrated stream; vendor correction may be suppressing bias/drift.";
        if (world < cal * 0.5f) return "Much of the phone-coordinate change disappears after rotation compensation.";
        return "Possible response, but algorithms disagree. Repeat the controlled trial.";
    }

    private float estimateFrequencyHz() {
        int n = magnitudeHistory.size();
        if (n < 20 || timeHistory.size() != n) return 0f;
        float mean = 0f;
        for (float v : magnitudeHistory) mean += v;
        mean /= n;
        int crossings = 0;
        Float previous = null;
        for (float v : magnitudeHistory) {
            float centered = v - mean;
            if (previous != null && previous <= 0f && centered > 0f) crossings++;
            previous = centered;
        }
        long first = timeHistory.peekFirst() == null ? 0L : timeHistory.peekFirst();
        long last = timeHistory.peekLast() == null ? 0L : timeHistory.peekLast();
        float seconds = (last - first) / 1_000_000_000f;
        if (seconds <= 0f) return 0f;
        return crossings / seconds;
    }

    private float[] worldVector(float x, float y, float z) {
        if (!hasRotation) return new float[]{x, y, z};
        return new float[]{
                rotationMatrix[0] * x + rotationMatrix[1] * y + rotationMatrix[2] * z,
                rotationMatrix[3] * x + rotationMatrix[4] * y + rotationMatrix[5] * z,
                rotationMatrix[6] * x + rotationMatrix[7] * y + rotationMatrix[8] * z
        };
    }

    private TextView card() {
        TextView v = text("", 14, Color.rgb(220, 232, 245));
        v.setBackgroundColor(Color.rgb(15, 31, 50));
        v.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(14), 0, 0);
        v.setLayoutParams(lp);
        return v;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.rgb(32, 105, 150));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(54));
        lp.setMargins(0, dp(8), 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView text(String value, int size, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setLineSpacing(0f, 1.18f);
        return v;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static float magnitude(float x, float y, float z) { return (float)Math.sqrt(x*x + y*y + z*z); }
    private static float smooth(float oldValue, float newValue) { return oldValue == 0f ? newValue : oldValue * 0.9f + newValue * 0.1f; }
    private static float maxDifference(float a, float b, float c) { return Math.max(Math.abs(a-b), Math.max(Math.abs(a-c), Math.abs(b-c))); }
    private static String yes(Sensor s) { return s == null ? "NO" : "YES"; }
    private static String name(Sensor s) { return s == null ? "Unavailable" : s.getName(); }
    private static String vendor(Sensor s) { return s == null ? "Unavailable" : s.getVendor(); }
    private static float resolution(Sensor s) { return s == null ? 0f : s.getResolution(); }
}
