package com.cuahang.tim;

import android.content.Intent;
import android.net.Uri;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Mở hộp thoại gỡ cài đặt của Android cho chính app này (Android không cho app tự gỡ ngầm). */
@CapacitorPlugin(name = "AppControl")
public class AppControlPlugin extends Plugin {
    @PluginMethod
    public void uninstall(PluginCall call) {
        try {
            Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }
}
