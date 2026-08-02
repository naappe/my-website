package com.naappe.magneticfielddetector;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
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
    private Sensor magneticSensor;
    private WifiManager wifiManager;
    private ConnectivityManager connectivityManager;
    private TelephonyManager telephonyManager;
    private DetectorView detectorView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final PhoneStateListener phoneStateListener = new PhoneStateListener() {
        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            super.onSignalStrengthsChanged(signalStrength);
            if (detectorView != null && signalStrength != null) {
                detectorView.updateCellular(signalStrength.getLevel(), signalStrength.toString());
            }
        }
    };

    private final Runnable signalUpdater = new Runnable() {
        @Override
        public void run() {
            updateNetworkReadings();
            handler.postDelayed(this, 1500L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.rgb(8, 18, 32));
        getWindow().setNavigationBarColor(Color.rgb(8, 18, 32));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);

        detectorView = new DetectorView(this, magneticSensor != null);
        setContentView(detectorView);
        requestRequiredPermissions();
    }

    private void requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED ||
                (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED)) {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.READ_PHONE_STATE,
                        Manifest.permission.NEARBY_WIFI_DEVICES
                }, PERMISSION_REQUEST);
            } else {
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.READ_PHONE_STATE
                }, PERMISSION_REQUEST);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (magneticSensor != null) {
            sensorManager.registerListener(this, magneticSensor, SensorManager.SENSOR_DELAY_GAME);
        }
        try {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
        } catch (SecurityException ignored) {
            detectorView.setPermissionMessage("Phone permission is needed for cellular signal.");
        }
        handler.post(signalUpdater);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
        handler.removeCallbacks(signalUpdater);
        try {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
        } catch (SecurityException ignored) {
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];
            float total = (float) Math.sqrt(x * x + y * y + z * z);
            detectorView.updateMagnetic(x, y, z, total, event.accuracy);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        detectorView.setAccuracy(accuracy);
    }

    private void updateNetworkReadings() {
        int rssi = -127;
        int linkSpeed = 0;
        int frequency = 0;
        String ssid = "Not connected";
        boolean wifiConnected = false;

        try {
            Network active = connectivityManager.getActiveNetwork();
            NetworkCapabilities caps = active == null ? null : connectivityManager.getNetworkCapabilities(active);
            wifiConnected = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);

            WifiInfo info = wifiManager.getConnectionInfo();
            if (wifiConnected && info != null) {
                rssi = info.getRssi();
                linkSpeed = info.getLinkSpeed();
                frequency = info.getFrequency();
                String rawSsid = info.getSSID();
                if (rawSsid != null && !WifiManager.UNKNOWN_SSID.equals(rawSsid)) {
                    ssid = rawSsid.replace("\"", "");
                } else {
                    ssid = "Connected Wi-Fi";
                }
            }
        } catch (SecurityException e) {
            detectorView.setPermissionMessage("Location / Nearby devices permission is needed for Wi-Fi details.");
        }

        detectorView.updateWifi(wifiConnected, ssid, rssi, linkSpeed, frequency);
    }

    private static final class DetectorView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Deque<Float> magneticHistory = new ArrayDeque<>();
        private final Deque<Float> wifiHistory = new ArrayDeque<>();
        private final boolean sensorAvailable;

        private int page = 0;
        private float x, y, z, total;
        private int accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE;
        private boolean wifiConnected;
        private String ssid = "Not connected";
        private int wifiRssi = -127;
        private int linkSpeed;
        private int frequency;
        private int cellLevel = -1;
        private String permissionMessage = "";

        DetectorView(Context context, boolean sensorAvailable) {
            super(context);
            this.sensorAvailable = sensorAvailable;
            setBackgroundColor(Color.rgb(8, 18, 32));
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeWidth(4f);
            linePaint.setColor(Color.rgb(71, 214, 180));
        }

        void updateMagnetic(float x, float y, float z, float total, int accuracy) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.total = total;
            this.accuracy = accuracy;
            magneticHistory.addLast(total);
            while (magneticHistory.size() > 90) magneticHistory.removeFirst();
            invalidate();
        }

        void updateWifi(boolean connected, String ssid, int rssi, int linkSpeed, int frequency) {
            this.wifiConnected = connected;
            this.ssid = ssid;
            this.wifiRssi = rssi;
            this.linkSpeed = linkSpeed;
            this.frequency = frequency;
            if (connected && rssi > -127) {
                wifiHistory.addLast((float) rssi);
                while (wifiHistory.size() > 90) wifiHistory.removeFirst();
            }
            invalidate();
        }

        void updateCellular(int level, String ignoredRawData) {
            this.cellLevel = level;
            invalidate();
        }

        void setAccuracy(int accuracy) {
            this.accuracy = accuracy;
            invalidate();
        }

        void setPermissionMessage(String message) {
            this.permissionMessage = message;
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_UP && event.getY() < getHeight() * 0.17f) {
                page = event.getX() < getWidth() / 2f ? 0 : 1;
                invalidate();
                return true;
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            drawHeader(canvas);
            if (page == 0) drawMagnetic(canvas); else drawSignals(canvas);
        }

        private void drawHeader(Canvas canvas) {
            float w = getWidth();
            float pad = w * 0.06f;
            paint.setColor(Color.WHITE);
            paint.setFakeBoldText(true);
            paint.setTextSize(w * 0.064f);
            canvas.drawText("Field Detector", pad, pad * 1.25f, paint);
            paint.setFakeBoldText(false);

            float top = w * 0.18f;
            float tabH = w * 0.105f;
            float gap = w * 0.018f;
            float tabW = (w - pad * 2 - gap) / 2f;
            drawTab(canvas, pad, top, tabW, tabH, "MAGNETIC", page == 0);
            drawTab(canvas, pad + tabW + gap, top, tabW, tabH, "RADIO SIGNALS", page == 1);
        }

        private void drawTab(Canvas canvas, float left, float top, float width, float height, String label, boolean active) {
            paint.setColor(active ? Color.rgb(30, 100, 132) : Color.rgb(15, 31, 49));
            canvas.drawRoundRect(left, top, left + width, top + height, 22f, 22f, paint);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(getWidth() * 0.033f);
            paint.setFakeBoldText(active);
            paint.setColor(active ? Color.WHITE : Color.rgb(150, 168, 190));
            canvas.drawText(label, left + width / 2f, top + height * 0.65f, paint);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setFakeBoldText(false);
        }

        private void drawMagnetic(Canvas canvas) {
            float width = getWidth();
            float height = getHeight();
            float pad = width * 0.065f;
            float offset = height * 0.12f;

            if (!sensorAvailable) {
                paint.setColor(Color.rgb(255, 110, 110));
                paint.setTextSize(width * 0.05f);
                canvas.drawText("No magnetometer sensor found", pad, height * 0.48f, paint);
                return;
            }

            float gaugeCx = width / 2f;
            float gaugeCy = height * 0.36f;
            float radius = width * 0.25f;
            drawGauge(canvas, gaugeCx, gaugeCy, radius, Math.min(total / 200f, 1f), fieldColor(total),
                    String.format(Locale.US, "%.1f", total), "µT", fieldLabel(total));

            float cardTop = height * 0.57f;
            float cardHeight = height * 0.115f;
            float gap = width * 0.025f;
            float cardWidth = (width - pad * 2 - gap * 2) / 3f;
            drawMetricCard(canvas, pad, cardTop, cardWidth, cardHeight, "X", String.format(Locale.US, "%.1f", x), "µT");
            drawMetricCard(canvas, pad + cardWidth + gap, cardTop, cardWidth, cardHeight, "Y", String.format(Locale.US, "%.1f", y), "µT");
            drawMetricCard(canvas, pad + (cardWidth + gap) * 2, cardTop, cardWidth, cardHeight, "Z", String.format(Locale.US, "%.1f", z), "µT");

            drawGraphCard(canvas, magneticHistory, pad, height * 0.72f, width - pad, height * 0.91f, false);
            paint.setTextSize(width * 0.029f);
            paint.setColor(Color.rgb(120, 140, 160));
            canvas.drawText("Accuracy: " + accuracyLabel(accuracy), pad, height * 0.96f, paint);
        }

        private void drawSignals(Canvas canvas) {
            float width = getWidth();
            float height = getHeight();
            float pad = width * 0.065f;
            float gaugeCx = width / 2f;
            float gaugeCy = height * 0.36f;
            float radius = width * 0.25f;

            float normalized = wifiConnected ? Math.max(0f, Math.min(1f, (wifiRssi + 100f) / 70f)) : 0f;
            int color = signalColor(wifiRssi, wifiConnected);
            String value = wifiConnected ? String.valueOf(wifiRssi) : "--";
            String label = wifiConnected ? wifiLabel(wifiRssi) : "Wi-Fi not connected";
            drawGauge(canvas, gaugeCx, gaugeCy, radius, normalized, color, value, "dBm", label);

            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(width * 0.035f);
            paint.setColor(Color.rgb(180, 198, 218));
            canvas.drawText(ssid, gaugeCx, height * 0.535f, paint);
            paint.setTextAlign(Paint.Align.LEFT);

            float top = height * 0.58f;
            float gap = width * 0.025f;
            float cardW = (width - pad * 2 - gap) / 2f;
            float cardH = height * 0.105f;
            drawMetricCard(canvas, pad, top, cardW, cardH, "LINK SPEED", wifiConnected ? String.valueOf(linkSpeed) : "--", "Mbps");
            drawMetricCard(canvas, pad + cardW + gap, top, cardW, cardH, "FREQUENCY", wifiConnected ? String.valueOf(frequency) : "--", "MHz");
            drawMetricCard(canvas, pad, top + cardH + gap, cardW, cardH, "WI-FI QUALITY", wifiConnected ? qualityPercent(wifiRssi) + "%" : "--", "RSSI");
            drawMetricCard(canvas, pad + cardW + gap, top + cardH + gap, cardW, cardH, "CELLULAR", cellLevel >= 0 ? (cellLevel + "/4") : "--", "signal level");

            drawGraphCard(canvas, wifiHistory, pad, height * 0.82f, width - pad, height * 0.95f, true);

            if (!permissionMessage.isEmpty()) {
                paint.setTextSize(width * 0.026f);
                paint.setColor(Color.rgb(255, 191, 71));
                canvas.drawText(permissionMessage, pad, height * 0.985f, paint);
            }
        }

        private void drawGauge(Canvas canvas, float cx, float cy, float radius, float normalized, int color,
                               String value, String unit, String label) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(getWidth() * 0.032f);
            paint.setColor(Color.rgb(26, 44, 63));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setColor(color);
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, -90f, 360f * normalized, false, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
            paint.setStyle(Paint.Style.FILL);

            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(Color.WHITE);
            paint.setFakeBoldText(true);
            paint.setTextSize(getWidth() * 0.12f);
            canvas.drawText(value, cx, cy + getWidth() * 0.02f, paint);
            paint.setFakeBoldText(false);
            paint.setTextSize(getWidth() * 0.036f);
            paint.setColor(Color.rgb(160, 180, 202));
            canvas.drawText(unit, cx, cy + getWidth() * 0.08f, paint);
            paint.setTextSize(getWidth() * 0.035f);
            paint.setColor(color);
            canvas.drawText(label, cx, cy + radius + getWidth() * 0.065f, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        private void drawMetricCard(Canvas canvas, float left, float top, float width, float height,
                                    String title, String value, String unit) {
            paint.setColor(Color.rgb(15, 31, 49));
            canvas.drawRoundRect(left, top, left + width, top + height, 22f, 22f, paint);
            paint.setColor(Color.rgb(135, 155, 177));
            paint.setTextSize(getWidth() * 0.029f);
            canvas.drawText(title, left + 18f, top + 30f, paint);
            paint.setColor(Color.WHITE);
            paint.setFakeBoldText(true);
            paint.setTextSize(getWidth() * 0.041f);
            canvas.drawText(value, left + 18f, top + height - 27f, paint);
            paint.setFakeBoldText(false);
            paint.setTextSize(getWidth() * 0.025f);
            paint.setColor(Color.rgb(120, 140, 160));
            float valueWidth = paint.measureText(value);
            canvas.drawText(unit, left + 26f + valueWidth, top + height - 27f, paint);
        }

        private void drawGraphCard(Canvas canvas, Deque<Float> history, float left, float top, float right, float bottom, boolean wifi) {
            paint.setColor(Color.rgb(15, 31, 49));
            canvas.drawRoundRect(left, top, right, bottom, 24f, 24f, paint);
            paint.setColor(Color.rgb(150, 168, 190));
            paint.setTextSize(getWidth() * 0.031f);
            canvas.drawText(wifi ? "Wi-Fi signal history" : "Live magnetic signal", left + 20f, top + 34f, paint);
            if (history.size() < 2) return;

            Path path = new Path();
            int index = 0;
            int count = history.size();
            float graphTop = top + 48f;
            float graphBottom = bottom - 16f;
            for (float v : history) {
                float px = left + 18f + (right - left - 36f) * index / (count - 1f);
                float normalized = wifi ? Math.max(0f, Math.min(1f, (v + 100f) / 70f)) : Math.min(v / 200f, 1f);
                float py = graphBottom - normalized * (graphBottom - graphTop);
                if (index == 0) path.moveTo(px, py); else path.lineTo(px, py);
                index++;
            }
            linePaint.setColor(wifi ? signalColor(wifiRssi, wifiConnected) : Color.rgb(71, 214, 180));
            canvas.drawPath(path, linePaint);
        }

        private int qualityPercent(int rssi) {
            return Math.max(0, Math.min(100, Math.round((rssi + 100f) * 100f / 70f)));
        }

        private int signalColor(int rssi, boolean connected) {
            if (!connected) return Color.rgb(120, 140, 160);
            if (rssi >= -60) return Color.rgb(71, 214, 180);
            if (rssi >= -75) return Color.rgb(255, 191, 71);
            return Color.rgb(255, 92, 92);
        }

        private String wifiLabel(int rssi) {
            if (rssi >= -50) return "Excellent Wi-Fi signal";
            if (rssi >= -60) return "Strong Wi-Fi signal";
            if (rssi >= -70) return "Good Wi-Fi signal";
            if (rssi >= -80) return "Weak Wi-Fi signal";
            return "Very weak Wi-Fi signal";
        }

        private int fieldColor(float value) {
            if (value < 70f) return Color.rgb(71, 214, 180);
            if (value < 150f) return Color.rgb(255, 191, 71);
            return Color.rgb(255, 92, 92);
        }

        private String fieldLabel(float value) {
            if (value < 70f) return "Normal ambient field";
            if (value < 150f) return "Elevated magnetic field";
            return "Strong magnetic field";
        }

        private String accuracyLabel(int value) {
            if (value == SensorManager.SENSOR_STATUS_ACCURACY_HIGH) return "High";
            if (value == SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) return "Medium";
            if (value == SensorManager.SENSOR_STATUS_ACCURACY_LOW) return "Low — move phone in a figure-eight";
            return "Unreliable — calibrate sensor";
        }
    }
}
