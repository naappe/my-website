package com.naappe.airvisualizer;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.IOException;
import java.util.List;

@SuppressWarnings("deprecation")
public class MainActivity extends Activity implements Camera.PreviewCallback {
    private static final int REQ_CAMERA = 10;
    private static final int NORMAL = 0, MOTION = 1, SHIMMER = 2, FINE = 3;

    private Camera camera;
    private int facing = Camera.CameraInfo.CAMERA_FACING_BACK;
    private int mode = MOTION;
    private ImageView image;
    private TextView status, stats, gainLabel, thresholdLabel;
    private Button normalBtn, motionBtn, shimmerBtn, fineBtn, flipBtn;
    private SeekBar gainBar, thresholdBar;
    private byte[] previousY;
    private int[] pixels;
    private Bitmap bitmap;
    private int previewW, previewH;
    private long lastFrameAt = 0;
    private long fpsStart = 0;
    private int fpsFrames = 0;
    private float currentFps = 0f;
    private SurfaceTexture dummyTexture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(5, 15, 28));
        getWindow().setNavigationBarColor(Color.rgb(5, 15, 28));
        buildUi();
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    private TextView text(String value, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(20, 44, 70)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(44), 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(p);
        return b;
    }

    private void buildUi() {
        int bg = Color.rgb(5, 15, 28);
        int panel = Color.rgb(11, 28, 47);
        int muted = Color.rgb(158, 184, 209);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.setBackgroundColor(bg);

        TextView title = text("Air Visualizer Lab", 24, Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);
        TextView sub = text("Native camera motion & shimmer experiment", 12, muted);
        root.addView(sub, new LinearLayout.LayoutParams(-1, dp(30)));

        LinearLayout viewer = new LinearLayout(this);
        viewer.setOrientation(LinearLayout.VERTICAL);
        viewer.setBackgroundColor(Color.BLACK);
        viewer.setPadding(dp(2), dp(2), dp(2), dp(2));

        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        viewer.addView(image, new LinearLayout.LayoutParams(-1, 0, 1f));

        status = text("Waiting for camera permission…", 12, Color.WHITE);
        status.setPadding(dp(10), dp(8), dp(10), dp(8));
        status.setBackgroundColor(Color.rgb(12, 29, 48));
        viewer.addView(status, new LinearLayout.LayoutParams(-1, dp(38)));
        root.addView(viewer, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(8), dp(10), dp(8), dp(6));
        controls.setBackgroundColor(panel);

        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        normalBtn = button("Normal");
        motionBtn = button("Air Motion");
        shimmerBtn = button("Heat/Shimmer");
        fineBtn = button("Fine Move");
        modes.addView(normalBtn); modes.addView(motionBtn); modes.addView(shimmerBtn); modes.addView(fineBtn);
        controls.addView(modes);

        normalBtn.setOnClickListener(v -> setMode(NORMAL));
        motionBtn.setOnClickListener(v -> setMode(MOTION));
        shimmerBtn.setOnClickListener(v -> setMode(SHIMMER));
        fineBtn.setOnClickListener(v -> setMode(FINE));

        gainLabel = text("Amplification: 5×", 12, muted);
        controls.addView(gainLabel);
        gainBar = new SeekBar(this);
        gainBar.setMax(11);
        gainBar.setProgress(4);
        controls.addView(gainBar, new LinearLayout.LayoutParams(-1, dp(38)));
        gainBar.setOnSeekBarChangeListener(simpleSeek(() -> gainLabel.setText("Amplification: " + (gainBar.getProgress()+1) + "×")));

        thresholdLabel = text("Noise threshold: 12", 12, muted);
        controls.addView(thresholdLabel);
        thresholdBar = new SeekBar(this);
        thresholdBar.setMax(50);
        thresholdBar.setProgress(12);
        controls.addView(thresholdBar, new LinearLayout.LayoutParams(-1, dp(38)));
        thresholdBar.setOnSeekBarChangeListener(simpleSeek(() -> thresholdLabel.setText("Noise threshold: " + thresholdBar.getProgress())));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        stats = text("Motion 0.0  •  Active 0%  •  FPS 0", 12, Color.WHITE);
        stats.setGravity(Gravity.CENTER_VERTICAL);
        bottom.addView(stats, new LinearLayout.LayoutParams(0, dp(46), 1f));
        flipBtn = button("Flip camera");
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(dp(120), dp(44));
        flipBtn.setLayoutParams(fp);
        flipBtn.setOnClickListener(v -> { facing = facing == Camera.CameraInfo.CAMERA_FACING_BACK ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo.CAMERA_FACING_BACK; restartCamera(); });
        bottom.addView(flipBtn);
        controls.addView(bottom);

        TextView note = text("Tip: keep the phone still and point at a detailed background. Put warm water, a warm hand, an AC stream, steam or fine visible particles between the camera and background. This app detects optical/visible effects of air; it does not directly image air molecules.", 11, muted);
        note.setPadding(0, dp(6), 0, 0);
        controls.addView(note);

        root.addView(controls, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        setMode(MOTION);
    }

    private SeekBar.OnSeekBarChangeListener simpleSeek(final Runnable r) {
        return new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) { r.run(); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        };
    }

    private void setMode(int newMode) {
        mode = newMode;
        previousY = null;
        Button[] bs = {normalBtn, motionBtn, shimmerBtn, fineBtn};
        for (int i=0;i<bs.length;i++) {
            int c = (i == mode) ? Color.rgb(16, 135, 199) : Color.rgb(20, 44, 70);
            bs[i].setBackgroundTintList(android.content.res.ColorStateList.valueOf(c));
        }
    }

    private void restartCamera() {
        stopCamera();
        startCamera();
    }

    private int findCameraId(int wantedFacing) {
        int count = Camera.getNumberOfCameras();
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i=0;i<count;i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == wantedFacing) return i;
        }
        return count > 0 ? 0 : -1;
    }

    private void startCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        try {
            int id = findCameraId(facing);
            if (id < 0) { status.setText("No camera found"); return; }
            camera = Camera.open(id);
            Camera.Parameters params = camera.getParameters();
            params.setPreviewFormat(ImageFormat.NV21);
            Camera.Size best = chooseSize(params.getSupportedPreviewSizes());
            params.setPreviewSize(best.width, best.height);
            List<String> focus = params.getSupportedFocusModes();
            if (focus != null && focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            camera.setParameters(params);
            previewW = best.width; previewH = best.height;
            pixels = new int[previewW * previewH];
            bitmap = Bitmap.createBitmap(previewH, previewW, Bitmap.Config.ARGB_8888);
            dummyTexture = new SurfaceTexture(15);
            camera.setPreviewTexture(dummyTexture);
            camera.setPreviewCallback(this);
            camera.startPreview();
            previousY = null;
            fpsStart = SystemClock.elapsedRealtime(); fpsFrames = 0;
            status.setText("Live • " + (facing == Camera.CameraInfo.CAMERA_FACING_BACK ? "rear" : "front") + " camera • " + previewW + "×" + previewH);
        } catch (Exception e) {
            status.setText("Camera error: " + e.getClass().getSimpleName());
            stopCamera();
        }
    }

    private Camera.Size chooseSize(List<Camera.Size> sizes) {
        Camera.Size best = sizes.get(0);
        long target = 320L * 240L;
        long bestDiff = Long.MAX_VALUE;
        for (Camera.Size s : sizes) {
            long area = (long)s.width * s.height;
            long diff = Math.abs(area - target);
            if (diff < bestDiff) { bestDiff = diff; best = s; }
        }
        return best;
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera cam) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastFrameAt < 55) return;
        lastFrameAt = now;
        int n = previewW * previewH;
        if (data == null || data.length < n || pixels == null) return;

        if (previousY == null || previousY.length != n) {
            previousY = new byte[n];
            System.arraycopy(data, 0, previousY, 0, n);
        }

        int gain = gainBar == null ? 5 : gainBar.getProgress() + 1;
        int threshold = thresholdBar == null ? 12 : thresholdBar.getProgress();
        long diffSum = 0;
        int active = 0;

        for (int y=0; y<previewH; y++) {
            for (int x=0; x<previewW; x++) {
                int si = y * previewW + x;
                int lum = data[si] & 0xff;
                int old = previousY[si] & 0xff;
                int diff = Math.abs(lum - old) - threshold;
                if (diff < 0) diff = 0;
                diff *= gain;
                if (diff > 255) diff = 255;
                diffSum += diff;
                if (diff > 18) active++;

                int color;
                if (mode == NORMAL) {
                    color = Color.rgb(lum, lum, lum);
                } else if (mode == MOTION) {
                    int r = Math.min(255, 5 + diff/3);
                    int g = Math.min(255, 20 + diff);
                    int b = Math.min(255, 35 + diff);
                    color = Color.rgb(r,g,b);
                } else if (mode == SHIMMER) {
                    color = heat(diff);
                } else {
                    int right = (x < previewW-1) ? (data[si+1] & 0xff) : lum;
                    int down = (y < previewH-1) ? (data[si+previewW] & 0xff) : lum;
                    int edge = Math.min(255, (Math.abs(lum-right) + Math.abs(lum-down)) * 2);
                    int v = Math.min(255, edge/2 + diff);
                    color = Color.rgb(v, Math.min(255, (int)(v*1.08f)), Math.min(255, (int)(v*1.15f)));
                }

                int dx = previewH - 1 - y;
                int dy = x;
                pixels[dy * previewH + dx] = color;
            }
        }
        System.arraycopy(data, 0, previousY, 0, n);
        bitmap.setPixels(pixels, 0, previewH, 0, 0, previewH, previewW);

        fpsFrames++;
        long elapsed = now - fpsStart;
        if (elapsed >= 800) {
            currentFps = fpsFrames * 1000f / elapsed;
            fpsFrames = 0; fpsStart = now;
        }
        final float score = diffSum / (float)n;
        final int activePct = Math.round(active * 100f / n);
        final float fps = currentFps;
        runOnUiThread(() -> {
            image.setImageBitmap(bitmap);
            stats.setText(String.format(java.util.Locale.US, "Motion %.1f  •  Active %d%%  •  FPS %.0f", score, activePct, fps));
        });
    }

    private int heat(int v) {
        int r,g,b;
        if (v < 64) { r=0; g=v*2; b=50+v*3; }
        else if (v < 128) { int q=v-64; r=q*2; g=128+q*2; b=242-q*3; }
        else if (v < 192) { int q=v-128; r=128+q*2; g=255; b=Math.max(0,50-q); }
        else { int q=v-192; r=255; g=Math.max(80,255-q*2); b=30; }
        return Color.rgb(clamp(r),clamp(g),clamp(b));
    }

    private int clamp(int v) { return Math.max(0, Math.min(255, v)); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void stopCamera() {
        if (camera != null) {
            try { camera.setPreviewCallback(null); camera.stopPreview(); } catch (Exception ignored) {}
            camera.release(); camera = null;
        }
        if (dummyTexture != null) { dummyTexture.release(); dummyTexture = null; }
    }

    @Override protected void onPause() { super.onPause(); stopCamera(); }
    @Override protected void onResume() { super.onResume(); if (camera == null && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera(); }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else status.setText("Camera permission is required for this experiment.");
    }
}
