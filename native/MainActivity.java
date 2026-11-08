package com.cuahang.tim;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(LocationEnablerPlugin.class);
        registerPlugin(MockGuardPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
