package com.cuahang.tim;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    // Khai báo danh sách các quyền cần kiểm tra/xin phép
    private final String[] requiredPermissions = new String[]{
        Manifest.permission.CAMERA,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALL_LOG
    };

    // Launcher xử lý kết quả trả về từ hộp thoại xin quyền của hệ thống
    private final ActivityResultLauncher<String[]> permissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), permissions -> {
            boolean allGranted = true;
            for (Boolean granted : permissions.values()) {
                if (granted != null && !granted) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                // Người dùng vừa bấm "Allow" -> Tiến hành chạy tính năng
                executeAppFeatures();
            } else {
                // Người dùng từ chối cấp quyền
                handlePermissionDenied();
            }
        });

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Đăng ký các plugin hiện tại của app
        registerPlugin(LocationEnablerPlugin.class);
        registerPlugin(MockGuardPlugin.class);
        registerPlugin(AppControlPlugin.class);
        
        super.onCreate(savedInstanceState);

        // Gọi hàm kiểm tra quyền mỗi khi mở màn hình
        checkAndExecutePermissions();
    }

    private void checkAndExecutePermissions() {
        boolean hasAllPermissions = true;
        for (String permission : requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                hasAllPermissions = false;
                break;
            }
        }

        if (hasAllPermissions) {
            // ĐÃ ĐỒNG Ý TỪ TRƯỚC: Chạy thẳng luôn, hệ thống KHÔNG hiện lại popup
            executeAppFeatures();
        } else {
            // CHƯA ĐỒNG Ý HOẶC LẦN ĐẦU: Kích hoạt hộp thoại hệ thống xin quyền
            permissionLauncher.launch(requiredPermissions);
        }
    }

    private void executeAppFeatures() {
        // Nơi bạn viết code đọc danh bạ, nhật ký cuộc gọi hoặc mở Camera
    }

    private void handlePermissionDenied() {
        // Xử lý khi người dùng chọn từ chối
    }
}
