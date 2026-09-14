package com.cabin.hardware;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public final class ToolkitService extends Service {
    private ToolkitBridge bridge;
    @Override public void onCreate() {
        super.onCreate();
        bridge = new ToolkitBridge(new SimulatedBackend());
    }
    @Override public IBinder onBind(Intent intent) { return bridge; }
    @Override public void onDestroy() { bridge.close(); super.onDestroy(); }
}
