package com.cuahang.tim;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentSender;
import android.provider.Settings;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.Priority;

/**
 * Hiện hộp thoại chuẩn của Google "Bật vị trí để có kết quả chính xác hơn" (nút OK / Không, cảm ơn).
 * Người dùng chỉ cần bấm OK, không cần vào Cài đặt.
 */
@CapacitorPlugin(name = "LocationEnabler", requestCodes = {9341})
public class LocationEnablerPlugin extends Plugin {
    private static final int REQ_ENABLE = 9341;

    private void reply(PluginCall call, boolean enabled) {
        JSObject o = new JSObject();
        o.put("enabled", enabled);
        call.resolve(o);
    }

    @PluginMethod
    public void enable(final PluginCall call) {
        try {
            LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).build();
            LocationSettingsRequest settings = new LocationSettingsRequest.Builder()
                    .addLocationRequest(req)
                    .setAlwaysShow(true)
                    .build();
            LocationServices.getSettingsClient(getActivity())
                    .checkLocationSettings(settings)
                    .addOnSuccessListener(r -> reply(call, true))
                    .addOnFailureListener(e -> {
                        if (e instanceof ResolvableApiException) {
                            try {
                                saveCall(call);
                                ((ResolvableApiException) e).startResolutionForResult(getActivity(), REQ_ENABLE);
                            } catch (IntentSender.SendIntentException ex) {
                                reply(call, false);
                            }
                        } else {
                            reply(call, false);
                        }
                    });
        } catch (Exception ex) {
            reply(call, false);
        }
    }

    // Mở thẳng màn hình Cài đặt > Vị trí của Android, dùng khi hộp thoại tự động
    // (checkLocationSettings) không bật lên được hoặc người dùng cần tự bật tay.
    @PluginMethod
    public void openSettings(final PluginCall call) {
        try {
            Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getActivity().startActivity(intent);
            reply(call, true);
        } catch (Exception ex) {
            reply(call, false);
        }
    }

    @Override
    protected void handleOnActivityResult(int requestCode, int resultCode, Intent data) {
        super.handleOnActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_ENABLE) return;
        PluginCall call = getSavedCall();
        if (call == null) return;
        reply(call, resultCode == Activity.RESULT_OK);
        freeSavedCall();
    }
}
