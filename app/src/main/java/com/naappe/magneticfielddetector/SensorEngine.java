package com.naappe.magneticfielddetector;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.GnssStatus;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.view.Display;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Collects Android-exposed hardware data and derives fusion, spatial and time intelligence. */
public final class SensorEngine implements SensorEventListener {
    public interface Listener { void onDataChanged(); }

    private final Activity activity;
    private final Listener listener;
    private final SensorManager sensors;
    private final WifiManager wifi;
    private final ConnectivityManager connectivity;
    private final TelephonyManager telephony;
    private final LocationManager location;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public float mx, my, mz, magnetic;
    public float ax, ay, az, gx, gy, gz, light = -1, proximity = -1, pressure = -1;
    public float vibration, vibrationPeak;
    public int wifiRssi = -127, wifiFrequency, wifiSpeed, cellLevel = -1;
    public String ssid = "Not connected";
    public int nearbyWifiCount, nearbyBleCount, strongestBle = -127, satellites;
    public int batteryPercent = -1;
    public float batteryTemp, batteryVoltage, storageFreeGb, storageTotalGb;
    public int sensorCount;
    public String cameraSummary = "Unknown", audioSummary = "Unknown", displaySummary = "Unknown";
    public boolean hasNfc, hasBle, hasUwb;

    public float stabilityScore, magneticAnomaly, motionComplexity, wirelessDensity, environmentScore;
    public int anomalyEvents;
    public String lastEvent = "No anomaly yet";

    public final float[] magneticMap = new float[36];
    public final float[] wifiMap = new float[36];
    public int mapCursor;

    public final Deque<Float> magneticHistory = new ArrayDeque<>();
    public final Deque<Float> vibrationHistory = new ArrayDeque<>();
    public final Deque<Float> wifiHistory = new ArrayDeque<>();
    public final Deque<Float> fusionHistory = new ArrayDeque<>();

    public boolean recording;
    public int recordedSamples;
    public String lastSavedPath = "No session saved";
    private final StringBuilder csv = new StringBuilder();
    private long recordStart;
    private float baselineMag = -1, baselineLight = -1;
    private long lastAnomalyAt;
    private final Set<String> bleSeen = new HashSet<>();
    private BluetoothLeScanner bleScanner;

    private final PhoneStateListener phoneListener = new PhoneStateListener() {
        @Override public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            if (signalStrength != null) cellLevel = signalStrength.getLevel();
        }
    };

    private final ScanCallback bleCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            if (result == null || result.getDevice() == null) return;
            try {
                String key = result.getDevice().getAddress();
                if (key != null) bleSeen.add(key);
                nearbyBleCount = bleSeen.size();
                strongestBle = Math.max(strongestBle, result.getRssi());
            } catch (SecurityException ignored) { }
        }
    };

    private final GnssStatus.Callback gnssCallback = new GnssStatus.Callback() {
        @Override public void onSatelliteStatusChanged(GnssStatus status) {
            satellites = status == null ? 0 : status.getSatelliteCount();
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateNetwork();
            updateDevice();
            updateFusion();
            if (recording) appendSample();
            listener.onDataChanged();
            handler.postDelayed(this, 500L);
        }
    };

    public SensorEngine(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        sensors = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
        wifi = (WifiManager) activity.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        connectivity = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
        telephony = (TelephonyManager) activity.getSystemService(Context.TELEPHONY_SERVICE);
        location = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
        inspectCapabilities();
    }

    public void start() {
        for (Sensor sensor : sensors.getSensorList(Sensor.TYPE_ALL)) {
            sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
        }
        try { telephony.listen(phoneListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS); } catch (SecurityException ignored) { }
        try {
            if (Build.VERSION.SDK_INT >= 24 && activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                location.registerGnssStatusCallback(gnssCallback, handler);
            }
        } catch (Exception ignored) { }
        startBleScan();
        handler.post(tick);
    }

    public void stop() {
        sensors.unregisterListener(this);
        handler.removeCallbacks(tick);
        try { telephony.listen(phoneListener, PhoneStateListener.LISTEN_NONE); } catch (Exception ignored) { }
        try { if (Build.VERSION.SDK_INT >= 24) location.unregisterGnssStatusCallback(gnssCallback); } catch (Exception ignored) { }
        stopBleScan();
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event == null || event.values.length == 0) return;
        switch (event.sensor.getType()) {
            case Sensor.TYPE_MAGNETIC_FIELD:
                mx = event.values[0]; my = event.values[1]; mz = event.values[2];
                magnetic = (float)Math.sqrt(mx*mx + my*my + mz*mz);
                push(magneticHistory, magnetic); break;
            case Sensor.TYPE_ACCELEROMETER:
                ax = event.values[0]; ay = event.values[1]; az = event.values[2];
                float totalAcceleration = (float)Math.sqrt(ax*ax + ay*ay + az*az);
                vibration = Math.abs(totalAcceleration - SensorManager.GRAVITY_EARTH);
                vibrationPeak = Math.max(vibrationPeak * 0.985f, vibration);
                push(vibrationHistory, vibration); break;
            case Sensor.TYPE_GYROSCOPE:
                gx = event.values[0]; gy = event.values[1]; gz = event.values[2]; break;
            case Sensor.TYPE_LIGHT: light = event.values[0]; break;
            case Sensor.TYPE_PROXIMITY: proximity = event.values[0]; break;
            case Sensor.TYPE_PRESSURE: pressure = event.values[0]; break;
            default: break;
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void inspectCapabilities() {
        sensorCount = sensors.getSensorList(Sensor.TYPE_ALL).size();
        PackageManager pm = activity.getPackageManager();
        hasNfc = pm.hasSystemFeature(PackageManager.FEATURE_NFC);
        hasBle = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
        hasUwb = Build.VERSION.SDK_INT >= 31 && pm.hasSystemFeature("android.hardware.uwb");

        try {
            CameraManager cm = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            String[] ids = cm.getCameraIdList();
            int raw = 0, manual = 0;
            for (String id : ids) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                int[] caps = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                if (caps != null) for (int cap : caps) {
                    if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) raw++;
                    if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) manual++;
                }
            }
            cameraSummary = ids.length + " cameras · RAW " + raw + " · manual " + manual;
        } catch (Exception e) { cameraSummary = "Camera metadata restricted"; }

        AudioManager am = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        String rate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
        String frames = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
        audioSummary = (rate == null ? "?" : rate) + " Hz · buffer " + (frames == null ? "?" : frames);

        try {
            Display d = activity.getWindowManager().getDefaultDisplay();
            displaySummary = String.format(Locale.US, "%.0f Hz · %dx%d", d.getRefreshRate(), d.getMode().getPhysicalWidth(), d.getMode().getPhysicalHeight());
        } catch (Exception e) { displaySummary = "Display metadata unavailable"; }
    }

    private void updateNetwork() {
        try {
            Network network = connectivity.getActiveNetwork();
            NetworkCapabilities caps = network == null ? null : connectivity.getNetworkCapabilities(network);
            boolean connected = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            WifiInfo info = wifi.getConnectionInfo();
            if (connected && info != null) {
                wifiRssi = info.getRssi(); wifiFrequency = info.getFrequency(); wifiSpeed = info.getLinkSpeed();
                String raw = info.getSSID();
                ssid = raw == null || WifiManager.UNKNOWN_SSID.equals(raw) ? "Connected Wi-Fi" : raw.replace("\"", "");
                push(wifiHistory, (float)wifiRssi);
            } else { ssid = "Not connected"; wifiRssi = -127; }

            try {
                wifi.startScan();
                List<android.net.wifi.ScanResult> results = wifi.getScanResults();
                nearbyWifiCount = results == null ? 0 : results.size();
            } catch (Exception ignored) { }
        } catch (SecurityException ignored) { }
    }

    private void startBleScan() {
        if (!hasBle) return;
        try {
            BluetoothManager bm = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
            bleScanner = adapter == null ? null : adapter.getBluetoothLeScanner();
            if (bleScanner != null) bleScanner.startScan(bleCallback);
        } catch (SecurityException ignored) { }
    }

    private void stopBleScan() {
        try { if (bleScanner != null) bleScanner.stopScan(bleCallback); } catch (SecurityException ignored) { }
    }

    private void updateDevice() {
        Intent b = activity.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b != null) {
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            batteryPercent = scale > 0 ? Math.round(level * 100f / scale) : -1;
            batteryTemp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
            batteryVoltage = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000f;
        }
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        storageTotalGb = fs.getTotalBytes() / 1073741824f;
        storageFreeGb = fs.getAvailableBytes() / 1073741824f;
    }

    private void updateFusion() {
        if (baselineMag < 0) captureBaseline();
        magneticAnomaly = clamp(Math.abs(magnetic - baselineMag) / 80f * 100f);
        motionComplexity = clamp(vibration * 35f + (Math.abs(gx)+Math.abs(gy)+Math.abs(gz))*7f);
        wirelessDensity = clamp(nearbyWifiCount * 2.5f + nearbyBleCount * 3f + Math.max(0, wifiRssi + 100));
        float lightChange = baselineLight < 0 || light < 0 ? 0 : Math.min(100, Math.abs(light-baselineLight)/Math.max(10, baselineLight)*100);
        environmentScore = clamp(magneticAnomaly*.35f + motionComplexity*.25f + wirelessDensity*.20f + lightChange*.20f);
        stabilityScore = 100f - environmentScore;
        push(fusionHistory, environmentScore);

        long now = System.currentTimeMillis();
        if (environmentScore > 55f && now - lastAnomalyAt > 3000) {
            anomalyEvents++; lastAnomalyAt = now;
            if (magneticAnomaly > motionComplexity && magneticAnomaly > wirelessDensity) lastEvent = "Magnetic environment changed";
            else if (motionComplexity > wirelessDensity) lastEvent = "Motion/vibration event";
            else lastEvent = "Wireless environment changed";
        }
    }

    public void captureBaseline() {
        baselineMag = magnetic;
        baselineLight = light;
        anomalyEvents = 0;
        lastEvent = "Baseline captured";
    }

    public void captureMapCell() {
        if (mapCursor >= magneticMap.length) mapCursor = 0;
        magneticMap[mapCursor] = magnetic;
        wifiMap[mapCursor] = wifiRssi;
        mapCursor++;
    }

    public void clearMap() {
        for (int i=0;i<magneticMap.length;i++) { magneticMap[i]=0; wifiMap[i]=0; }
        mapCursor = 0;
    }

    public void toggleRecording() {
        if (!recording) {
            recording = true; recordedSamples = 0; recordStart = System.currentTimeMillis();
            csv.setLength(0);
            csv.append("elapsed_ms,magnetic_uT,vibration_ms2,wifi_dbm,light_lux,pressure_hpa,environment_score,event\n");
        } else {
            recording = false;
            saveCsv();
        }
    }

    private void appendSample() {
        long elapsed = System.currentTimeMillis() - recordStart;
        csv.append(elapsed).append(',').append(fmt(magnetic)).append(',').append(fmt(vibration)).append(',')
                .append(wifiRssi).append(',').append(fmt(light)).append(',').append(fmt(pressure)).append(',')
                .append(fmt(environmentScore)).append(',').append(lastEvent.replace(',', ';')).append('\n');
        recordedSamples++;
    }

    private void saveCsv() {
        try {
            File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (dir == null) dir = activity.getFilesDir();
            if (!dir.exists()) dir.mkdirs();
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File file = new File(dir, "sensor-session-" + stamp + ".csv");
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(csv.toString().getBytes(StandardCharsets.UTF_8));
            }
            lastSavedPath = file.getAbsolutePath();
        } catch (Exception e) { lastSavedPath = "Save failed: " + e.getMessage(); }
    }

    public List<String> capabilityLines() {
        List<String> lines = new ArrayList<>();
        lines.add("Physical/virtual sensors: " + sensorCount);
        lines.add("Camera: " + cameraSummary);
        lines.add("Audio: " + audioSummary);
        lines.add("Display: " + displaySummary);
        lines.add("Wi-Fi networks visible: " + nearbyWifiCount);
        lines.add("Bluetooth LE devices visible: " + nearbyBleCount);
        lines.add("GNSS satellites visible: " + satellites);
        lines.add("NFC " + yes(hasNfc) + " · BLE " + yes(hasBle) + " · UWB " + yes(hasUwb));
        lines.add("Device: " + Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE);
        return lines;
    }

    private static String yes(boolean value) { return value ? "YES" : "NO"; }
    private static String fmt(float value) { return String.format(Locale.US, "%.3f", value); }
    private static float clamp(float value) { return Math.max(0, Math.min(100, value)); }
    private static void push(Deque<Float> queue, float value) { queue.addLast(value); while (queue.size()>120) queue.removeFirst(); }
}
