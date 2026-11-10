package com.cuahang.tim;

import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
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
    private long cacheTime = 0;
    private JSArray cacheAppOps = null;
    private JSArray cacheLegacy = null;

    @SuppressWarnings("deprecation")
    private static boolean isMock(Location l) {
        if (Build.VERSION.SDK_INT >= 31) return l.isMock();
        return l.isFromMockProvider();
    }

    @SuppressLint("QueryPermissionsNeeded")
    @SuppressWarnings("deprecation")
    private JSArray legacyMockPermApps() {
        // Giữ lại để tham khảo/ghi log, nhưng từ Android 6.0 (API 23) app KHÔNG CẦN khai
        // permission ACCESS_MOCK_LOCATION nữa để được chọn làm "mock location app" trong
        // Developer Options, nên danh sách này gần như luôn rỗng với app giả GPS hiện đại.
        JSArray arr = new JSArray();
        try {
            PackageManager pm = getContext().getPackageManager();
            for (PackageInfo pi : pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
                if (pi.applicationInfo == null || (pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                if (pi.requestedPermissions == null) continue;
                for (String p : pi.requestedPermissions) {
                    if ("android.permission.ACCESS_MOCK_LOCATION".equals(p)) {
                        arr.put(String.valueOf(pm.getApplicationLabel(pi.applicationInfo)) + " (" + pi.packageName + ")");
                        break;
                    }
                }
            }
        } catch (Exception ignored) {}
        return arr;
    }

    @SuppressLint("QueryPermissionsNeeded")
    @SuppressWarnings("deprecation")
    private JSArray mockAppsViaAppOps() {
        // Cách đúng cho Android 6.0+: dò qua AppOpsManager xem app nào đang được hệ thống
        // cấp OP_MOCK_LOCATION (tức app đang được chọn làm "mock location app" trong
        // Developer Options), bất kể app đó có khai permission cũ hay không.
        JSArray arr = new JSArray();
        try {
            AppOpsManager aom = (AppOpsManager) getContext().getSystemService(Context.APP_OPS_SERVICE);
            PackageManager pm = getContext().getPackageManager();
            if (aom == null) return arr;
            for (PackageInfo pi : pm.getInstalledPackages(0)) {
                if (pi.applicationInfo == null || (pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                try {
                    int mode = aom.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, pi.applicationInfo.uid, pi.packageName);
                    if (mode == AppOpsManager.MODE_ALLOWED) {
                        arr.put(String.valueOf(pm.getApplicationLabel(pi.applicationInfo)) + " (" + pi.packageName + ")");
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
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

    // Lấy danh sách app mock (có cache) - hàm NẶNG, chỉ được gọi từ bgExecutor, KHÔNG BAO GIỜ
    // gọi trực tiếp từ UI thread.
    private synchronized void refreshMockAppsIfNeeded() {
        long now = System.currentTimeMillis();
        if (cacheAppOps != null && (now - cacheTime) < CACHE_MS) return;
        cacheAppOps = mockAppsViaAppOps();
        cacheLegacy = legacyMockPermApps();
        cacheTime = now;
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
                refreshMockAppsIfNeeded();
                JSArray appOpsMockApps = cacheAppOps != null ? cacheAppOps : new JSArray();
                JSArray legacyApps = cacheLegacy != null ? cacheLegacy : new JSArray();
                // "mock" = cờ chính thức của OS trên chính location vừa lấy được, HOẶC có app đang
                // được hệ thống cấp quyền mock-location ngay lúc này (bắt được cả trường hợp OS
                // không gắn cờ lên object Location vì app giả hook thẳng vào provider).
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
                o.put("legacyMockPermApps", legacyApps);
                o.put("devOptions", devOptionsOn());
                o.put("rootOrXposed", rootOrXposedDetected());
                call.resolve(o);
            } catch (Exception e) {
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
            new Handler(Looper.getMainLooper()).postDelayed(() -> { cts.cancel(); reply(call, done, null); }, CHECK_TIMEOUT_MS);
            c.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.getToken())
                .addOnSuccessListener(loc -> reply(call, done, loc))
                .addOnFailureListener(e -> reply(call, done, null));
        } catch (Exception e) {
            reply(call, done, null);
        }
    }
}
