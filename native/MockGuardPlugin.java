package com.cuahang.tim;

import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.io.File;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Chống GPS giả: lấy 1 vị trí mới và đọc cờ "mock" do Android gắn (isMock / isFromMockProvider),
 * đồng thời liệt kê app cài trên máy có khai báo quyền ACCESS_MOCK_LOCATION (app giả GPS) để ghi nhật ký.
 *
 * QUAN TRỌNG: việc quét toàn bộ app cài trên máy (getInstalledPackages) có thể mất
 * hàng trăm mili-giây đến vài giây tuỳ số lượng app trên máy, và nếu chạy trên UI thread
 * sẽ gây "đơ/treo" cả app (ANR). Toàn bộ phần quét này được đẩy ra 1 thread nền riêng
 * (bgExecutor), callback vị trí (location) vốn đã chạy trên UI thread chỉ dùng để lấy
 * toạ độ rồi giao việc nặng cho thread nền, không tự xử lý trực tiếp.
 */
@CapacitorPlugin(name = "MockGuard")
public class MockGuardPlugin extends Plugin {

    // Thời gian chờ tối đa khi lấy vị trí để kiểm tra cờ "mock" (ms).
    // Hàm này chạy SAU KHI JS đã có vị trí (locate() ở index.html), nên đây là độ trễ CỘNG THÊM
    // vào mỗi lần locateSafe(). Giảm từ 8000 -> 3000 để tránh "đơ" kéo dài khi tín hiệu GPS yếu.
    private static final long CHECK_TIMEOUT_MS = 3000;

    // 1 luồng nền riêng, dùng chung cho mọi lượt gọi check() để không tạo/huỷ thread liên tục.
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    // Cache danh sách app "mock location" trong 20 giây: quét app cài trên máy không đổi
    // liên tục trong 1 phiên sử dụng, nên không cần quét lại mỗi lần bấm nút.
    private static final long CACHE_MS = 20000;
    private volatile long cacheTime = 0;
    private volatile JSArray cacheAppOps = null;
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Quét sẵn 1 lần ngay khi plugin được nạp để lần bấm đầu tiên không phải chờ.
    @Override
    public void load() {
        bgExecutor.execute(this::refreshMockApps);
    }

    @SuppressWarnings("deprecation")
    private static boolean isMock(Location l) {
        if (Build.VERSION.SDK_INT >= 31) return l.isMock();
        return l.isFromMockProvider();
    }

    // Quét app đang được cấp OP_MOCK_LOCATION. Chỉ chạy trên bgExecutor.
    // Nhẹ hơn bản cũ: (1) bỏ hẳn quét GET_PERMISSIONS (nặng, vô dụng từ Android 6+),
    // (2) chỉ quét khi đã bật Tuỳ chọn nhà phát triển (không bật thì không thể chọn app giả GPS),
    // (3) dùng getInstalledApplications thay vì getInstalledPackages.
    @SuppressLint("QueryPermissionsNeeded")
    @SuppressWarnings("deprecation")
    private JSArray mockAppsViaAppOps() {
        JSArray arr = new JSArray();
        try {
            if (!devOptionsOn()) return arr;
            AppOpsManager aom = (AppOpsManager) getContext().getSystemService(Context.APP_OPS_SERVICE);
            PackageManager pm = getContext().getPackageManager();
            if (aom == null) return arr;
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            for (ApplicationInfo ai : apps) {
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                try {
                    int mode = aom.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, ai.uid, ai.packageName);
                    if (mode == AppOpsManager.MODE_ALLOWED) {
                        arr.put(String.valueOf(pm.getApplicationLabel(ai)) + " (" + ai.packageName + ")");
                    }
                } catch (Exception ignored) {}
            }
        } catch (Throwable ignored) {}
        return arr;
    }

    private boolean devOptionsOn() {
        try {
            return Settings.Global.getInt(getContext().getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
        } catch (Exception e) { return false; }
    }

    private static final String[] ROOT_PATHS = {
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/system/app/Superuser.apk", "/system/app/SuperSU", "/data/local/xbin/su",
        "/data/local/bin/su", "/system/bin/.ext/.su"
    };
    private static final String[] XPOSED_INDICATORS = {
        "/system/framework/XposedBridge.jar", "/sbin/.magisk", "/data/adb/magisk",
        "/data/adb/modules"
    };

    // Chỉ là heuristic tham khảo (KHÔNG tự động kết luận "giả GPS" chỉ vì máy root):
    // nhiều máy root hợp pháp không hề giả vị trí. Dùng để cộng thêm tín hiệu rủi ro,
    // không dùng để tự ý chặn người dùng.
    private boolean rootOrXposedDetected() {
        try {
            for (String p : ROOT_PATHS) if (new File(p).exists()) return true;
            for (String p : XPOSED_INDICATORS) if (new File(p).exists()) return true;
            String tags = Build.TAGS;
            if (tags != null && tags.contains("test-keys")) return true;
        } catch (Exception ignored) {}
        return false;
    }

    private void refreshMockApps() {
        if (!scanning.compareAndSet(false, true)) return;
        try {
            cacheAppOps = mockAppsViaAppOps();
            cacheTime = System.currentTimeMillis();
        } finally {
            scanning.set(false);
        }
    }

    // Callback vị trí (loc) chạy trên UI thread -> chỉ đóng gói toạ độ rồi giao hết việc
    // nặng (quét app, kiểm tra root...) cho bgExecutor, resolve() sẽ chạy từ thread nền đó.
    private void reply(PluginCall call, AtomicBoolean done, Location loc) {
        if (!done.compareAndSet(false, true)) return;
        final double lat = loc != null ? loc.getLatitude() : 0;
        final double lng = loc != null ? loc.getLongitude() : 0;
        final float acc = loc != null ? loc.getAccuracy() : 0;
        final boolean hasLoc = loc != null;
        final boolean osFlaggedMock = loc != null && isMock(loc);

        bgExecutor.execute(() -> {
            try {
                // Lần đầu chưa có cache: quét 1 lần. Các lần sau: dùng cache ngay, nếu cũ thì
                // trả kết quả trước rồi mới quét lại (stale-while-revalidate) -> không bao giờ chờ lâu.
                if (cacheAppOps == null) refreshMockApps();
                JSArray appOpsMockApps = cacheAppOps != null ? cacheAppOps : new JSArray();
                boolean mock = osFlaggedMock || appOpsMockApps.length() > 0;

                JSObject o = new JSObject();
                o.put("known", hasLoc);
                o.put("mock", mock);
                o.put("osFlaggedMock", osFlaggedMock);
                if (hasLoc) {
                    o.put("lat", lat);
                    o.put("lng", lng);
                    o.put("acc", acc);
                }
                o.put("mockApps", appOpsMockApps);
                o.put("legacyMockPermApps", new JSArray());
                o.put("devOptions", devOptionsOn());
                o.put("rootOrXposed", rootOrXposedDetected());
                call.resolve(o);
                if ((System.currentTimeMillis() - cacheTime) >= CACHE_MS) refreshMockApps();
            } catch (Throwable e) {
                JSObject o = new JSObject();
                o.put("known", hasLoc);
                o.put("mock", osFlaggedMock);
                call.resolve(o);
            }
        });
    }

    @PluginMethod
    public void check(final PluginCall call) {
        final AtomicBoolean done = new AtomicBoolean(false);
        try {
            FusedLocationProviderClient c = LocationServices.getFusedLocationProviderClient(getActivity());
            final CancellationTokenSource cts = new CancellationTokenSource();
            final Runnable timeout = () -> { cts.cancel(); reply(call, done, null); };
            mainHandler.postDelayed(timeout, CHECK_TIMEOUT_MS);
            c.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.getToken())
                .addOnSuccessListener(loc -> { mainHandler.removeCallbacks(timeout); reply(call, done, loc); })
                .addOnFailureListener(e -> { mainHandler.removeCallbacks(timeout); reply(call, done, null); });
        } catch (Throwable e) {
            reply(call, done, null);
        }
    }
}
