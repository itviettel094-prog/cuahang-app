package com.cuahang.tim;

import android.Manifest;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;
import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.Priority;

public class MainActivity extends BridgeActivity {

    private final String[] requiredPermissions = new String[]{
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    };

    private boolean askingGps = false;

    // Kết quả hộp thoại "Bật vị trí" của Google
    private final ActivityResultLauncher<IntentSenderRequest> gpsLauncher =
        registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
            askingGps = false;
            if (result.getResultCode() == RESULT_OK) {
                onGpsReady();
            } else {
                // Từ chối -> hỏi lại sau 0.5 giây, cho đến khi đồng ý
                new Handler(Looper.getMainLooper()).postDelayed(this::ensureGpsOn, 500);
            }
        });

    // Kết quả xin quyền hệ thống
    private final ActivityResultLauncher<String[]> permissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), perms -> {
            if (hasLocationPermission()) ensureGpsOn();
        });

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(LocationEnablerPlugin.class);
        registerPlugin(MockGuardPlugin.class);
        registerPlugin(AppControlPlugin.class);
        super.onCreate(savedInstanceState);
        checkAndExecutePermissions();
    }

    // Mỗi lần quay lại app (kể cả khi vừa tắt GPS) đều kiểm tra lại
    @Override
    public void onResume() {
        super.onResume();
        if (hasLocationPermission()) ensureGpsOn();
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void checkAndExecutePermissions() {
        boolean all = true;
        for (String p : requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                all = false;
                break;
            }
        }
        if (all) ensureGpsOn();
        else permissionLauncher.launch(requiredPermissions);
    }

    /** Kiểm tra GPS; nếu tắt thì hiện hộp thoại Google xin bật. */
    private void ensureGpsOn() {
        if (askingGps) return;
        try {
            LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).build();
            LocationSettingsRequest settings = new LocationSettingsRequest.Builder()
                    .addLocationRequest(req)
                    .setAlwaysShow(true)
                    .build();
            LocationServices.getSettingsClient(this)
                .checkLocationSettings(settings)
                .addOnSuccessListener(r -> onGpsReady())          // GPS đang bật
                .addOnFailureListener(e -> {                      // GPS đang tắt
                    if (e instanceof ResolvableApiException) {
                        try {
                            askingGps = true;
                            IntentSender is = ((ResolvableApiException) e).getResolution().getIntentSender();
                            gpsLauncher.launch(new IntentSenderRequest.Builder(is).build());
                        } catch (Exception ex) {
                            askingGps = false;
                        }
                    }
                });
        } catch (Exception ignored) {}
    }

    /** GPS đã bật: báo cho trang web để bắt đầu tìm vị trí. */
    private void onGpsReady() {
        try {
            getBridge().triggerWindowJSEvent("gpsReady", "{}");
        } catch (Exception ignored) {}
    }
}
