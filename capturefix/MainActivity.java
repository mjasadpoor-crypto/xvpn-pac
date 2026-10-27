package com.xgs.cap;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity implements ServiceConnection, Shizuku.OnRequestPermissionResultListener {
    public static final String TAG = "XGS";
    private static final int PORT = 34567;
    private static final int SHIZUKU_PERMISSION_REQUEST = 61;

    private Shizuku.UserServiceArgs userServiceArgs;
    private IBinder remoteBinder;
    private TextView statusText;
    private Button overlayButton;
    private WindowManager windowManager;
    private TextView floatingButton;
    private ServerSocket serverSocket;
    private boolean binding = false;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            userServiceArgs = new Shizuku.UserServiceArgs(
                    new ComponentName(getPackageName(), CaptureService.class.getName()))
                    .daemon(false)
                    .debuggable(false)
                    .processNameSuffix("capture_user_service")
                    .version(2);
            createScreen();
            startMessageServer();
        } catch (Throwable error) {
            showStartupError(error);
        }
    }

    private void createScreen() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(dp(20), dp(35), dp(20), dp(20));
        layout.setBackgroundColor(Color.rgb(18, 25, 37));
        TextView title = new TextView(this);
        title.setText("CaptureSecureScreen");
        title.setTextSize(24);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        layout.addView(title, new LinearLayout.LayoutParams(-1, dp(65)));

        statusText = new TextView(this);
        statusText.setTextSize(16);
        statusText.setTextColor(Color.rgb(220, 230, 243));
        statusText.setPadding(0, dp(12), 0, dp(20));
        statusText.setText("First start Shizuku, then connect.");
        layout.addView(statusText, new LinearLayout.LayoutParams(-1, -2));

        Button connectButton = addButton(layout, "1. Connect Shizuku");
        connectButton.setOnClickListener(v -> connectShizuku());

        overlayButton = addButton(layout, "2. Enable floating screenshot button");
        overlayButton.setOnClickListener(v -> enableOverlay());

        Button captureButton = addButton(layout, "Test capture while this app is open");
        captureButton.setOnClickListener(v -> capture());

        TextView help = new TextView(this);
        help.setText("\nThis app needs the separate Shizuku app running. The floating button also requires Android's Display over other apps permission. Saved images are reported in a toast. Some Android versions may still refuse secure screenshots.\n\nDo not share screenshots containing account numbers or private information.");
        help.setTextSize(13);
        help.setTextColor(Color.rgb(160, 176, 197));
        layout.addView(help, new LinearLayout.LayoutParams(-1, -2));

        setContentView(layout);
    }

    private Button addButton(LinearLayout layout, String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(12);
        layout.addView(b, params);
        return b;
    }

    private int dp(int x) {
        return Math.round(x * getResources().getDisplayMetrics().density);
    }

    private void showStatus(String text) {
        if (statusText != null) statusText.setText(text);
        Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show();
    }

    private boolean shizukuAvailable() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void connectShizuku() {
        try {
            if (!shizukuAvailable()) {
                showStatus("Shizuku is not running. Open the Shizuku app and start it first.");
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                showStatus("Grant this app permission in the Shizuku prompt.");
                Shizuku.addRequestPermissionResultListener(this);
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST);
                return;
            }
            if (remoteBinder != null && remoteBinder.pingBinder()) {
                showStatus("Shizuku connected. Enable floating button next.");
                return;
            }
            if (binding) {
                showStatus("Connecting to Shizuku...");
                return;
            }
            binding = true;
            showStatus("Connecting to Shizuku...");
            Shizuku.bindUserService(userServiceArgs, this);
        } catch (Throwable error) {
            binding = false;
            showStatus("Shizuku connection failed: " + error.getClass().getSimpleName());
        }
    }

    @Override
    public void onRequestPermissionResult(int requestCode, int grantResult) {
        if (requestCode != SHIZUKU_PERMISSION_REQUEST) return;
        try {
            Shizuku.removeRequestPermissionResultListener(this);
        } catch (Throwable ignored) {}
        if (grantResult == PackageManager.PERMISSION_GRANTED) connectShizuku();
        else showStatus("Shizuku permission denied.");
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        binding = false;
        remoteBinder = binder;
        runOnUiThread(() -> showStatus("Shizuku connected. Floating screenshot is ready after overlay permission."));
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        binding = false;
        remoteBinder = null;
        runOnUiThread(() -> showStatus("Shizuku disconnected. Reconnect in this app."));
    }

    private void enableOverlay() {
        try {
            if (!Settings.canDrawOverlays(this)) {
                showStatus("Allow Display over other apps, then return and press this button again.");
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
                return;
            }
            if (floatingButton != null) {
                showStatus("Floating button is already enabled.");
                return;
            }
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            floatingButton = new TextView(this);
            floatingButton.setText("CAP");
            floatingButton.setTextSize(15);
            floatingButton.setGravity(Gravity.CENTER);
            floatingButton.setTextColor(Color.WHITE);
            floatingButton.setBackgroundTintList(null);
            floatingButton.setBackgroundColor(Color.rgb(0, 130, 150));
            floatingButton.setOnClickListener(v -> capture());
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    dp(58), dp(58),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
            windowManager.addView(floatingButton, p);
            showStatus("Floating CAP button enabled. Open the desired screen and tap CAP.");
        } catch (Throwable error) {
            floatingButton = null;
            showStatus("Cannot show floating button: " + error.getClass().getSimpleName());
        }
    }

    private void capture() {
        try {
            if (remoteBinder == null || !remoteBinder.pingBinder()) {
                showStatus("Shizuku service not connected. Press Connect Shizuku.");
                return;
            }
            ICaptureService userService = ICaptureService.Stub.asInterface(remoteBinder);
            if (userService == null) {
                showStatus("Screenshot service unavailable.");
                return;
            }
            userService.doCap(PORT);
            showStatus("Capture requested. Watch for success/failure toast.");
        } catch (RemoteException error) {
            showStatus("Capture service error: " + error.getClass().getSimpleName());
        } catch (Throwable error) {
            showStatus("Capture failed: " + error.getClass().getSimpleName());
        }
    }

    private void startMessageServer() {
        Thread thread = new Thread(() -> {
            try (ServerSocket server = new ServerSocket(PORT, 1, InetAddress.getLoopbackAddress())) {
                serverSocket = server;
                while (!server.isClosed()) {
                    try (Socket socket = server.accept();
                         BufferedReader reader = new BufferedReader(
                                 new InputStreamReader(socket.getInputStream()))) {
                        String result = reader.readLine();
                        if (result != null) runOnUiThread(() -> showStatus(result));
                    } catch (IOException ignored) {
                        if (server.isClosed()) break;
                    }
                }
            } catch (IOException error) {
                runOnUiThread(() -> showStatus("Capture status listener unavailable."));
            }
        }, "capture-status-server");
        thread.setDaemon(true);
        thread.start();
    }

    private void showStartupError(Throwable error) {
        TextView t = new TextView(this);
        t.setPadding(dp(16), dp(25), dp(16), dp(25));
        t.setText("Failed to initialize app: " + error.getClass().getName() + ": " + error.getMessage());
        setContentView(t);
    }

    @Override
    protected void onDestroy() {
        if (floatingButton != null && windowManager != null) {
            try { windowManager.removeView(floatingButton); } catch (Throwable ignored) {}
            floatingButton = null;
        }
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
        }
        try { Shizuku.removeRequestPermissionResultListener(this); } catch (Throwable ignored) {}
        if (userServiceArgs != null && shizukuAvailable()) {
            try { Shizuku.unbindUserService(userServiceArgs, this, true); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }
}
