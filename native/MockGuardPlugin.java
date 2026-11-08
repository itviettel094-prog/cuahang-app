package com.cuahang.tim;

import android.annotation.SuppressLint;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

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

/**
 * Chống GPS giả: lấy 1 vị trí mới và đọc cờ "mock" do Android gắn (isMock / isFromMockProvider),
 * đồng thời liệt kê app cài trên máy có khai báo quyền ACCESS_MOCK_LOCATION (app giả GPS) để ghi nhật ký.
 */
@CapacitorPlugin(name = "MockGuard")
public class MockGuardPlugin extends Plugin {

    @SuppressWarnings("deprecation")
    private static boolean isMock(Location l) {
        if (Build.VERSION.SDK_INT >= 31) return l.isMock();
        return l.isFromMockProvider();
    }

    @SuppressLint("QueryPermissionsNeeded")
    @SuppressWarnings("deprecation")
    private JSArray mockApps() {
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

    private boolean devOptionsOn() {
        try {
            return Settings.Global.getInt(getContext().getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
        } catch (Exception e) { return false; }
    }

    private void reply(PluginCall call, AtomicBoolean done, Location loc) {
        if (!done.compareAndSet(false, true)) return;
        JSObject o = new JSObject();
        o.put("known", loc != null);
        o.put("mock", loc != null && isMock(loc));
        if (loc != null) {
            o.put("lat", loc.getLatitude());
            o.put("lng", loc.getLongitude());
            o.put("acc", loc.getAccuracy());
        }
        o.put("mockApps", mockApps());
        o.put("devOptions", devOptionsOn());
        call.resolve(o);
    }

    @PluginMethod
    public void check(final PluginCall call) {
        final AtomicBoolean done = new AtomicBoolean(false);
        try {
            FusedLocationProviderClient c = LocationServices.getFusedLocationProviderClient(getActivity());
            final CancellationTokenSource cts = new CancellationTokenSource();
            new Handler(Looper.getMainLooper()).postDelayed(() -> { cts.cancel(); reply(call, done, null); }, 8000);
            c.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.getToken())
                .addOnSuccessListener(loc -> reply(call, done, loc))
                .addOnFailureListener(e -> reply(call, done, null));
        } catch (Exception e) {
            reply(call, done, null);
        }
    }
}
