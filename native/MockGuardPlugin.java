package com.cuahang.tim;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Đã TẮT chống GPS giả. Giữ lại class rỗng để MainActivity (registerPlugin) không bị lỗi biên dịch.
 * Không quét app, không lấy vị trí, không tạo thread -> không thể gây treo.
 */
@CapacitorPlugin(name = "MockGuard")
public class MockGuardPlugin extends Plugin {
    @PluginMethod
    public void check(final PluginCall call) {
        JSObject o = new JSObject();
        o.put("known", false);
        o.put("mock", false);
        call.resolve(o);
    }
}
