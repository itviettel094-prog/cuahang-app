package com.cuahang.tim;

import android.Manifest;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;

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

    // Kết quả hộp thoại "Bật vị trí" của Google.
    // KHÔNG còn tự hỏi lại liên tục khi bị từ chối (trước đây lặp mỗi 0,5s -> hộp thoại bật mãi, app đơ
    // khi dùng app giả GPS vì chế độ vị trí bị đổi). Lần quay lại app kế tiếp sẽ kiểm tra lại 1 lần.
    private final ActivityResultLauncher<IntentSenderRequest> gpsLauncher =
        registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
            askingGps = false;
            if (result.getResultCode() == RESULT_OK) onGpsReady();
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

    // Mỗi lần quay lại app chỉ kiểm tra: vị trí có đang TẮT hẳn không (không xét độ chính xác).
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

    // Vị trí của máy đang bật? (chế độ "chỉ thiết bị" khi dùng giả GPS vẫn tính là BẬT)
    private boolean isLocationOn() {
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm == null) return true;
            if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) { return true; }
    }

    /** Chỉ hiện hộp thoại Google khi vị trí đang TẮT hẳn, và chỉ hỏi 1 lần mỗi lượt. */
    private void ensureGpsOn() {
        if (askingGps) return;
        if (isLocationOn()) { onGpsReady(); return; }
        try {
            LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).build();
            LocationSettingsRequest settings = new LocationSettingsRequest.Builder()
                    .addLocationRequest(req)
                    .setAlwaysShow(false)
                    .build();
            LocationServices.getSettingsClient(this)
                .checkLocationSettings(settings)
                .addOnSuccessListener(r -> onGpsReady())
                .addOnFailureListener(e -> {
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
