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
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

public class RawSensorLabActivity extends Activity implements SensorEventListener {
    private SensorManager manager;
    private Sensor calibrated;
    private Sensor uncalibrated;
    private TextView output;

    private float cx, cy, cz;
    private float ux, uy, uz;
    private float bx, by, bz;
    private long lastCalNs, lastUncalNs;
    private float calHz, uncalHz;
    private int calSamples, uncalSamples;
    private long startedMs;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.rgb(8, 16, 28));
        getWindow().setNavigationBarColor(Color.rgb(8, 16, 28));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        manager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        calibrated = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        uncalibrated = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED);
        startedMs = SystemClock.elapsedRealtime();
        setContentView(buildUi());
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (calibrated != null) manager.registerListener(this, calibrated, SensorManager.SENSOR_DELAY_FASTEST);
        if (uncalibrated != null) manager.registerListener(this, uncalibrated, SensorManager.SENSOR_DELAY_FASTEST);
    }

    @Override protected void onPause() {
        manager.unregisterListener(this);
        super.onPause();
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            cx = event.values[0]; cy = event.values[1]; cz = event.values[2];
            calSamples++;
            if (lastCalNs != 0L) calHz = smooth(calHz, 1_000_000_000f / Math.max(1L, event.timestamp - lastCalNs));
            lastCalNs = event.timestamp;
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED) {
            ux = event.values[0]; uy = event.values[1]; uz = event.values[2];
            if (event.values.length >= 6) {
                bx = event.values[3]; by = event.values[4]; bz = event.values[5];
            }
            uncalSamples++;
            if (lastUncalNs != 0L) uncalHz = smooth(uncalHz, 1_000_000_000f / Math.max(1L, event.timestamp - lastUncalNs));
            lastUncalNs = event.timestamp;
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
        root.setPadding(p, p, p, p);

        TextView title = text("Raw Sensor Reverse Lab", 28, Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);
        root.addView(text("Reads Android's calibrated and uncalibrated magnetometer streams side by side.", 14, Color.rgb(160, 180, 205)));
        root.addView(text("This does not bypass chip firmware. It exposes the lowest-level magnetic data Android makes available to a normal app.", 13, Color.rgb(255, 205, 90)));

        output = text("", 14, Color.rgb(220, 232, 245));
        output.setBackgroundColor(Color.rgb(15, 31, 50));
        output.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(18), 0, 0);
        output.setLayoutParams(lp);
        root.addView(output);

        root.addView(text("Test protocol\n1. Hold the phone still in open space.\n2. Rotate slowly through 360°.\n3. Compare raw field, calibrated field and estimated bias.\n4. Repeat near metal, a magnet and a plant without moving the phone position.\n5. Look for repeatable differences larger than noise.", 14, Color.rgb(190, 210, 230)));

        scroll.addView(root);
        return scroll;
    }

    private void render() {
        if (output == null) return;
        float cm = magnitude(cx, cy, cz);
        float um = magnitude(ux, uy, uz);
        float bm = magnitude(bx, by, bz);
        float residual = magnitude(ux - bx - cx, uy - by - cy, uz - bz - cz);
        long elapsed = (SystemClock.elapsedRealtime() - startedMs) / 1000L;

        output.setText(String.format(Locale.US,
                "CALIBRATED STREAM\n" +
                "Available: %s\nSensor: %s\nVendor: %s\nRange: %.1f µT\nResolution: %.4f µT\nMin delay: %d µs\nRate: %.1f Hz\nX / Y / Z: %.3f / %.3f / %.3f µT\nMagnitude: %.3f µT\nSamples: %d\n\n" +
                "UNCALIBRATED STREAM\n" +
                "Available: %s\nSensor: %s\nVendor: %s\nRate: %.1f Hz\nRaw X / Y / Z: %.3f / %.3f / %.3f µT\nRaw magnitude: %.3f µT\nEstimated bias X / Y / Z: %.3f / %.3f / %.3f µT\nBias magnitude: %.3f µT\nRaw - bias - calibrated residual: %.3f µT\nSamples: %d\n\nElapsed: %d s",
                yes(calibrated), name(calibrated), vendor(calibrated), range(calibrated), resolution(calibrated), delay(calibrated), calHz,
                cx, cy, cz, cm, calSamples,
                yes(uncalibrated), name(uncalibrated), vendor(uncalibrated), uncalHz,
                ux, uy, uz, um, bx, by, bz, bm, residual, uncalSamples, elapsed));
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
    private static String yes(Sensor s) { return s == null ? "NO" : "YES"; }
    private static String name(Sensor s) { return s == null ? "Unavailable" : s.getName(); }
    private static String vendor(Sensor s) { return s == null ? "Unavailable" : s.getVendor(); }
    private static float range(Sensor s) { return s == null ? 0f : s.getMaximumRange(); }
    private static float resolution(Sensor s) { return s == null ? 0f : s.getResolution(); }
    private static int delay(Sensor s) { return s == null ? 0 : s.getMinDelay(); }
}
