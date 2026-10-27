package com.xgs.cap;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
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
    private WindowManager.LayoutParams floatingParams;
    private ServerSocket serverSocket;
    private boolean binding = false;
    private final Handler countdownHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingCapture = null;

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

        overlayButton = addButton(layout, "2. Enable mini draggable CAP button");
        overlayButton.setOnClickListener(v -> enableOverlay());

        Button hideFloatingButton = addButton(layout, "Hide / disable floating CAP button");
        hideFloatingButton.setOnClickListener(v -> disableOverlay(true));

        Button captureButton = addButton(layout, "Test capture while this app is open");
        captureButton.setOnClickListener(v -> capture());

        Button delayedButton = addButton(layout, "3. Screenshot in 10 seconds (no floating button)");
        delayedButton.setOnClickListener(v -> startDelayedCapture());

        TextView help = new TextView(this);
        help.setText("\nThis app needs the separate Shizuku app running. The mini CAP can be dragged. Tap to capture; hold it for one second to hide, or use the Hide button above. It needs Display over other apps permission. For apps that hide floating buttons, use the 10-second capture timer and immediately switch to the target app using Recents. Results are shown here. Some Android versions may still refuse secure screenshots.\n\nDo not share screenshots containing account numbers or private information.");
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
                showStatus("Allow Display over other apps, then return and press Enable again.");
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                return;
            }
            if (floatingButton != null) {
                showStatus("Mini CAP already enabled. Drag to move; hold to hide.");
                return;
            }
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            floatingButton = new TextView(this);
            floatingButton.setText("CAP");
            floatingButton.setTextSize(10);
            floatingButton.setGravity(Gravity.CENTER);
            floatingButton.setIncludeFontPadding(false);
            floatingButton.setTextColor(Color.WHITE);

            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(Color.argb(225, 0, 139, 150));
            shape.setStroke(dp(1), Color.argb(190, 255, 255, 255));
            floatingButton.setBackground(shape);
            floatingButton.setElevation(dp(4));

            final int diameter = dp(38);
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;
            android.content.SharedPreferences preferences =
                    getSharedPreferences("floating_cap", MODE_PRIVATE);
            int px = preferences.getInt("x", w - diameter - dp(12));
            int py = preferences.getInt("y", h / 2);
            floatingParams = new WindowManager.LayoutParams(
                    diameter, diameter,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            floatingParams.gravity = Gravity.TOP | Gravity.LEFT;
            floatingParams.x = Math.max(0, Math.min(px, Math.max(0, w - diameter)));
            floatingParams.y = Math.max(0, Math.min(py, Math.max(0, h - diameter)));

            floatingButton.setOnTouchListener(new View.OnTouchListener() {
                private float startRawX, startRawY;
                private int startX, startY;
                private long pressedAt;
                private boolean moved;

                @Override
                public boolean onTouch(View view, MotionEvent event) {
                    if (floatingParams == null || windowManager == null) return true;
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            startRawX = event.getRawX();
                            startRawY = event.getRawY();
                            startX = floatingParams.x;
                            startY = floatingParams.y;
                            pressedAt = android.os.SystemClock.uptimeMillis();
                            moved = false;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            float dx = event.getRawX() - startRawX;
                            float dy = event.getRawY() - startRawY;
                            if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                            if (moved) {
                                int maxX = Math.max(0, getResources().getDisplayMetrics().widthPixels - diameter);
                                int maxY = Math.max(0, getResources().getDisplayMetrics().heightPixels - diameter);
                                floatingParams.x = Math.max(0, Math.min(startX + Math.round(dx), maxX));
                                floatingParams.y = Math.max(0, Math.min(startY + Math.round(dy), maxY));
                                try {
                                    windowManager.updateViewLayout(view, floatingParams);
                                } catch (RuntimeException ex) {
                                    showStatus("Unable to move CAP: " + ex.getClass().getSimpleName());
                                }
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                            if (moved) {
                                getSharedPreferences("floating_cap", MODE_PRIVATE).edit()
                                        .putInt("x", floatingParams.x)
                                        .putInt("y", floatingParams.y)
                                        .apply();
                            } else if (android.os.SystemClock.uptimeMillis() - pressedAt >= 850) {
                                disableOverlay(true);
                            } else {
                                capture();
                            }
                            return true;
                        case MotionEvent.ACTION_CANCEL:
                            return true;
                        default:
                            return true;
                    }
                }
            });
            windowManager.addView(floatingButton, floatingParams);
            overlayButton.setText("2. Mini draggable CAP enabled");
            showStatus("CAP enabled. Tap = capture, drag = move, hold = hide.");
        } catch (Throwable err) {
            floatingButton = null;
            floatingParams = null;
            showStatus("Cannot show floating button: " + err.getClass().getSimpleName());
        }
    }

    private void disableOverlay(boolean notify) {
        if (floatingButton != null && windowManager != null) {
            try { windowManager.removeView(floatingButton); } catch (Throwable ignored) {}
        }
        floatingButton = null;
        floatingParams = null;
        if (overlayButton != null) overlayButton.setText("2. Enable mini draggable CAP button");
        if (notify) showStatus("Floating CAP disabled. Use Enable to turn it back on.");
    }

    private void startDelayedCapture() {
        if (remoteBinder == null || !remoteBinder.pingBinder()) {
            showStatus("First connect Shizuku, then press the 10-second capture button.");
            return;
        }
        if (pendingCapture != null) countdownHandler.removeCallbacks(pendingCapture);
        statusText.setText("10-second capture armed. Switch to the desired app NOW using Recents.");
        Toast.makeText(getApplicationContext(), "Switch to the desired screen now. Capture in 10 seconds.", Toast.LENGTH_LONG).show();
        pendingCapture = () -> {
            pendingCapture = null;
            capture(false);
        };
        countdownHandler.postDelayed(pendingCapture, 10000L);
    }

    private void capture() {
        capture(true);
    }

    private void capture(boolean toastOnRequest) {
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
            if (toastOnRequest) {
                showStatus("Capture requested. Watch for success/failure toast.");
            } else if (statusText != null) {
                statusText.setText("Delayed capture requested. Return here to view SUCCESS/FAILED result.");
            }
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
        if (pendingCapture != null) countdownHandler.removeCallbacks(pendingCapture);
        disableOverlay(false);
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
