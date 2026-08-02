package com.naappe.magneticfielddetector;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements SensorEngine.Listener {
    private static final int PERMISSION_REQUEST = 400;
    private SensorEngine engine;
    private LabView labView;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.rgb(6,15,27));
        getWindow().setNavigationBarColor(Color.rgb(6,15,27));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        engine = new SensorEngine(this, this);
        labView = new LabView(this, engine);
        setContentView(labView);
        requestRequiredPermissions();
    }

    private void requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        List<String> permissions = new ArrayList<>();
        addIfMissing(permissions, Manifest.permission.ACCESS_FINE_LOCATION);
        addIfMissing(permissions, Manifest.permission.READ_PHONE_STATE);
        if (Build.VERSION.SDK_INT >= 31) {
            addIfMissing(permissions, Manifest.permission.BLUETOOTH_SCAN);
            addIfMissing(permissions, Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33) addIfMissing(permissions, Manifest.permission.NEARBY_WIFI_DEVICES);
        if (!permissions.isEmpty()) requestPermissions(permissions.toArray(new String[0]), PERMISSION_REQUEST);
    }

    private void addIfMissing(List<String> target, String permission) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) target.add(permission);
    }

    @Override protected void onResume() {
        super.onResume();
        engine.start();
    }

    @Override protected void onPause() {
        engine.stop();
        super.onPause();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST) {
            engine.stop();
            engine.start();
        }
    }

    @Override public void onDataChanged() {
        if (labView != null) labView.invalidate();
    }
}
