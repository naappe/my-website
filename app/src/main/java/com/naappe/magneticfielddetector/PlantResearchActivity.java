package com.naappe.magneticfielddetector;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Environment;
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

    private float magnetic;
    private float motion;
    private float light = -1f;
    private float pressure = -1f;

    private float baselineMag;
    private float baselineMotion;
    private float baselineLight;
    private float baselinePressure;
    private boolean baselineReady;

    private final List<PlantSample> samples = new ArrayList<>();

    private TextView liveText;
    private TextView statusText;
    private EditText plantNameInput;
    private EditText notesInput;
    private EditText nitrogenInput;
    private EditText phosphorusInput;
    private EditText potassiumInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
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

        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        register(magnetometer);
        register(accelerometer);
        register(lightSensor);
        register(pressureSensor);
    }

    @Override
    protected void onPause() {
        sensorManager.unregisterListener(this);
        super.onPause();
    }

    private void register(Sensor sensor) {
        if (sensor != null) {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float[] v = event.values;
        switch (event.sensor.getType()) {
            case Sensor.TYPE_MAGNETIC_FIELD:
                magnetic = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
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

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private View buildUi() {
        int pad = dp(18);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(5, 28, 18));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad * 2);
        scroll.addView(root);

        root.addView(title("Plant Research Lab", 28, true));
        root.addView(title("Experimental multi-sensor plant fingerprint foundation", 14, false));
        root.addView(space(12));

        TextView warning = title("This app does not directly measure N, P or K. Reference values must come from a trusted soil or leaf test.", 13, false);
        warning.setTextColor(Color.rgb(255, 205, 90));
        warning.setBackgroundColor(Color.rgb(25, 55, 36));
        warning.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(warning);
        root.addView(space(14));

        plantNameInput = input("Plant name / sample ID");
        notesInput = input("Notes: leaf condition, location, growth stage");
        root.addView(plantNameInput);
        root.addView(notesInput);

        root.addView(section("Trusted reference NPK (optional)"));
        LinearLayout npkRow = new LinearLayout(this);
        npkRow.setOrientation(LinearLayout.HORIZONTAL);
        nitrogenInput = compactInput("N");
        phosphorusInput = compactInput("P");
        potassiumInput = compactInput("K");
        npkRow.addView(nitrogenInput, new LinearLayout.LayoutParams(0, dp(54), 1));
        npkRow.addView(phosphorusInput, new LinearLayout.LayoutParams(0, dp(54), 1));
        npkRow.addView(potassiumInput, new LinearLayout.LayoutParams(0, dp(54), 1));
        root.addView(npkRow);

        root.addView(section("Live sensor state"));
        liveText = cardText();
        root.addView(liveText);

        Button baselineButton = button("CAPTURE 3-SECOND BASELINE");
        baselineButton.setOnClickListener(v -> captureBaseline());
        root.addView(baselineButton);

        Button sampleButton = button("CAPTURE PLANT SAMPLE");
        sampleButton.setOnClickListener(v -> captureSample());
        root.addView(sampleButton);

        Button exportButton = button("EXPORT DATASET CSV");
        exportButton.setOnClickListener(v -> exportCsv());
        root.addView(exportButton);

        statusText = cardText();
        statusText.setText("Foundation ready. Keep the phone still and capture a baseline before each plant measurement.");
        root.addView(statusText);

        root.addView(section("Foundation protocol"));
        TextView protocol = title(
                "1. Keep the phone at a fixed height and orientation.\n" +
                "2. Capture baseline away from the plant.\n" +
                "3. Move the plant into the marked position without moving the phone.\n" +
                "4. Capture three or more repeats.\n" +
                "5. Enter laboratory or meter NPK values when available.\n" +
                "6. Export CSV for later model training.", 14, false);
        protocol.setTextColor(Color.rgb(205, 225, 212));
        root.addView(protocol);

        updateLiveText();
        return scroll;
    }

    private void captureBaseline() {
        if (motion > 0.35f) {
            statusText.setText("Phone is moving. Place it on a stable non-metal surface and try again.");
            return;
        }
        baselineMag = magnetic;
        baselineMotion = motion;
        baselineLight = light;
        baselinePressure = pressure;
        baselineReady = true;
        statusText.setText(String.format(Locale.US,
                "Baseline captured: %.2f µT | %.2f lux | %.2f hPa", baselineMag, baselineLight, baselinePressure));
    }

    private void captureSample() {
        if (!baselineReady) {
            statusText.setText("Capture a baseline first.");
            return;
        }
        if (motion > 0.35f) {
            statusText.setText("Sample rejected because the phone moved. Hold the setup still.");
            return;
        }

        PlantSample sample = new PlantSample();
        sample.timestamp = System.currentTimeMillis();
        sample.name = clean(plantNameInput.getText().toString(), "Unnamed plant");
        sample.notes = notesInput.getText().toString().trim();
        sample.magnetic = magnetic;
        sample.magneticDelta = magnetic - baselineMag;
        sample.motion = motion;
        sample.light = light;
        sample.lightDelta = valid(light, baselineLight) ? light - baselineLight : Float.NaN;
        sample.pressure = pressure;
        sample.pressureDelta = valid(pressure, baselinePressure) ? pressure - baselinePressure : Float.NaN;
        sample.n = nitrogenInput.getText().toString().trim();
        sample.p = phosphorusInput.getText().toString().trim();
        sample.k = potassiumInput.getText().toString().trim();
        samples.add(sample);

        statusText.setText(String.format(Locale.US,
                "Sample %d saved for %s\nMagnetic delta: %+.2f µT\nLight delta: %s\nNo species or NPK conclusion is generated yet.",
                samples.size(), sample.name, sample.magneticDelta,
                Float.isNaN(sample.lightDelta) ? "unavailable" : String.format(Locale.US, "%+.2f lux", sample.lightDelta)));
    }

    private void exportCsv() {
        if (samples.isEmpty()) {
            statusText.setText("No samples to export.");
            return;
        }
        try {
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (dir == null) throw new IllegalStateException("Storage unavailable");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Cannot create export folder");
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File file = new File(dir, "plant_research_" + stamp + ".csv");
            StringBuilder csv = new StringBuilder();
            csv.append("timestamp,plant_id,magnetic_uT,magnetic_delta_uT,motion_residual,light_lux,light_delta_lux,pressure_hPa,pressure_delta_hPa,reference_N,reference_P,reference_K,notes\n");
            for (PlantSample s : samples) {
                csv.append(s.timestamp).append(',')
                        .append(q(s.name)).append(',')
                        .append(f(s.magnetic)).append(',')
                        .append(f(s.magneticDelta)).append(',')
                        .append(f(s.motion)).append(',')
                        .append(f(s.light)).append(',')
                        .append(f(s.lightDelta)).append(',')
                        .append(f(s.pressure)).append(',')
                        .append(f(s.pressureDelta)).append(',')
                        .append(q(s.n)).append(',')
                        .append(q(s.p)).append(',')
                        .append(q(s.k)).append(',')
                        .append(q(s.notes)).append('\n');
            }
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(csv.toString().getBytes(StandardCharsets.UTF_8));
            }
            statusText.setText("Dataset exported:\n" + file.getAbsolutePath());
            Toast.makeText(this, "Plant dataset exported", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            statusText.setText("Export failed: " + e.getMessage());
        }
    }

    private void updateLiveText() {
        if (liveText == null) return;
        liveText.setText(String.format(Locale.US,
                "Magnetic: %.2f µT\nMotion residual: %.3f m/s²\nLight: %s\nPressure: %s\nSamples: %d | Baseline: %s",
                magnetic,
                motion,
                light >= 0 ? String.format(Locale.US, "%.2f lux", light) : "Unavailable",
                pressure >= 0 ? String.format(Locale.US, "%.2f hPa", pressure) : "Unavailable",
                samples.size(), baselineReady ? "Ready" : "Not captured"));
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

    private EditText compactInput(String hint) {
        EditText view = input(hint);
        view.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(54), 1);
        params.setMargins(dp(3), 0, dp(3), 0);
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

    private View space(int dp) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(dp)));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static boolean valid(float a, float b) {
        return a >= 0 && b >= 0;
    }

    private static String clean(String value, String fallback) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static String f(float value) {
        return Float.isNaN(value) ? "" : String.format(Locale.US, "%.5f", value);
    }

    private static String q(String value) {
        String v = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + v + "\"";
    }

    private static final class PlantSample {
        long timestamp;
        String name;
        String notes;
        float magnetic;
        float magneticDelta;
        float motion;
        float light;
        float lightDelta;
        float pressure;
        float pressureDelta;
        String n;
        String p;
        String k;
    }
}
