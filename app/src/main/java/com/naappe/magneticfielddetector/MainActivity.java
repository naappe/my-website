package com.naappe.magneticfielddetector;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener {
    private SensorManager sensorManager;
    private Sensor magneticSensor;
    private MagneticView magneticView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.rgb(8, 18, 32));
        getWindow().setNavigationBarColor(Color.rgb(8, 18, 32));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        magneticView = new MagneticView(this, magneticSensor != null);
        setContentView(magneticView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (magneticSensor != null) {
            sensorManager.registerListener(this, magneticSensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];
            float total = (float) Math.sqrt(x * x + y * y + z * z);
            magneticView.updateValues(x, y, z, total, event.accuracy);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        magneticView.setAccuracy(accuracy);
    }

    private static final class MagneticView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Deque<Float> history = new ArrayDeque<>();
        private final boolean sensorAvailable;
        private float x;
        private float y;
        private float z;
        private float total;
        private int accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE;

        MagneticView(Context context, boolean sensorAvailable) {
            super(context);
            this.sensorAvailable = sensorAvailable;
            setBackgroundColor(Color.rgb(8, 18, 32));
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeWidth(4f);
            linePaint.setColor(Color.rgb(71, 214, 180));
        }

        void updateValues(float x, float y, float z, float total, int accuracy) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.total = total;
            this.accuracy = accuracy;
            history.addLast(total);
            while (history.size() > 90) history.removeFirst();
            invalidate();
        }

        void setAccuracy(int accuracy) {
            this.accuracy = accuracy;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            float pad = width * 0.065f;

            paint.setColor(Color.WHITE);
            paint.setTextSize(width * 0.075f);
            paint.setFakeBoldText(true);
            canvas.drawText("Magnetic Field", pad, pad * 1.3f, paint);

            paint.setFakeBoldText(false);
            paint.setTextSize(width * 0.036f);
            paint.setColor(Color.rgb(150, 168, 190));
            canvas.drawText("Live phone magnetometer reading", pad, pad * 2.05f, paint);

            if (!sensorAvailable) {
                paint.setColor(Color.rgb(255, 110, 110));
                paint.setTextSize(width * 0.052f);
                canvas.drawText("No magnetometer sensor found", pad, height * 0.45f, paint);
                paint.setTextSize(width * 0.035f);
                paint.setColor(Color.rgb(180, 195, 215));
                canvas.drawText("This phone cannot measure magnetic fields.", pad, height * 0.51f, paint);
                return;
            }

            float gaugeCx = width / 2f;
            float gaugeCy = height * 0.31f;
            float radius = width * 0.29f;

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(width * 0.035f);
            paint.setColor(Color.rgb(26, 44, 63));
            canvas.drawCircle(gaugeCx, gaugeCy, radius, paint);

            float normalized = Math.min(total / 200f, 1f);
            paint.setColor(fieldColor(total));
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawArc(gaugeCx - radius, gaugeCy - radius, gaugeCx + radius, gaugeCy + radius,
                    -90f, 360f * normalized, false, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
            paint.setStyle(Paint.Style.FILL);

            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(Color.WHITE);
            paint.setFakeBoldText(true);
            paint.setTextSize(width * 0.13f);
            canvas.drawText(String.format(Locale.US, "%.1f", total), gaugeCx, gaugeCy + width * 0.025f, paint);
            paint.setTextSize(width * 0.04f);
            paint.setFakeBoldText(false);
            paint.setColor(Color.rgb(160, 180, 202));
            canvas.drawText("µT", gaugeCx, gaugeCy + width * 0.09f, paint);

            paint.setTextSize(width * 0.038f);
            paint.setColor(fieldColor(total));
            canvas.drawText(fieldLabel(total), gaugeCx, gaugeCy + radius + width * 0.09f, paint);
            paint.setTextAlign(Paint.Align.LEFT);

            float cardTop = height * 0.53f;
            float cardHeight = height * 0.13f;
            float gap = width * 0.025f;
            float cardWidth = (width - pad * 2 - gap * 2) / 3f;
            drawAxisCard(canvas, pad, cardTop, cardWidth, cardHeight, "X", x);
            drawAxisCard(canvas, pad + cardWidth + gap, cardTop, cardWidth, cardHeight, "Y", y);
            drawAxisCard(canvas, pad + (cardWidth + gap) * 2, cardTop, cardWidth, cardHeight, "Z", z);

            float graphTop = height * 0.71f;
            float graphBottom = height * 0.90f;
            paint.setColor(Color.rgb(15, 31, 49));
            canvas.drawRoundRect(pad, graphTop, width - pad, graphBottom, 28f, 28f, paint);

            paint.setColor(Color.rgb(150, 168, 190));
            paint.setTextSize(width * 0.035f);
            canvas.drawText("Live signal", pad + 24f, graphTop + 42f, paint);
            drawHistory(canvas, pad + 20f, graphTop + 60f, width - pad - 20f, graphBottom - 20f);

            paint.setTextSize(width * 0.03f);
            paint.setColor(Color.rgb(120, 140, 160));
            canvas.drawText("Accuracy: " + accuracyLabel(accuracy), pad, height * 0.96f, paint);
        }

        private void drawAxisCard(Canvas canvas, float left, float top, float width, float height, String axis, float value) {
            paint.setColor(Color.rgb(15, 31, 49));
            canvas.drawRoundRect(left, top, left + width, top + height, 24f, 24f, paint);
            paint.setColor(Color.rgb(135, 155, 177));
            paint.setTextSize(getWidth() * 0.034f);
            canvas.drawText(axis, left + 20f, top + 34f, paint);
            paint.setColor(Color.WHITE);
            paint.setFakeBoldText(true);
            paint.setTextSize(getWidth() * 0.043f);
            canvas.drawText(String.format(Locale.US, "%.1f", value), left + 20f, top + height - 25f, paint);
            paint.setFakeBoldText(false);
        }

        private void drawHistory(Canvas canvas, float left, float top, float right, float bottom) {
            if (history.size() < 2) return;
            Path path = new Path();
            int index = 0;
            int count = history.size();
            for (float value : history) {
                float px = left + (right - left) * index / (count - 1f);
                float py = bottom - Math.min(value / 200f, 1f) * (bottom - top);
                if (index == 0) path.moveTo(px, py); else path.lineTo(px, py);
                index++;
            }
            canvas.drawPath(path, linePaint);
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
