package com.xgs.cap;

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.OutcomeReceiver;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class CaptureService extends ICaptureService.Stub {
    private static final String TAG = "XGS";
    private static final int DISPLAY_ID = 0;
    private volatile boolean running = true;

    @Override
    public void destroy() {
        running = false;
    }

    @Override
    public void doCap(int port) {
        Thread worker = new Thread(() -> {
            if (!running) return;
            String message;
            try {
                Bitmap bitmap = captureScreenshot();
                if (bitmap == null) {
                    throw new IllegalStateException("Capture returned no bitmap");
                }
                File folder = new File("/sdcard/Download/CaptureSecureScreen");
                if (!folder.exists() && !folder.mkdirs()) {
                    bitmap.recycle();
                    throw new IllegalStateException("Cannot create Download/CaptureSecureScreen directory");
                }
                String fileName = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                        .format(new Date()) + ".jpg";
                File imageFile = new File(folder, fileName);
                try (OutputStream stream = new FileOutputStream(imageFile)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                        throw new IllegalStateException("Bitmap JPEG encoding failed");
                    }
                } finally {
                    bitmap.recycle();
                }
                message = "SUCCESS: Download/CaptureSecureScreen/" + fileName;
            } catch (Throwable exception) {
                Throwable root = unwrap(exception);
                String reason = root.getMessage();
                message = "FAILED: " + root.getClass().getSimpleName()
                        + (reason == null ? "" : " - " + reason);
                Log.e(TAG, "Screenshot capture failed", exception);
            }

            message = message.replace('\n', ' ').replace('\r', ' ');
            if (message.length() > 500) message = message.substring(0, 500);
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                socket.getOutputStream().write((message + "\n").getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
            } catch (Exception e) {
                Log.e(TAG, "Unable to send capture status back to app", e);
            }
        }, "secure-capture");
        worker.setDaemon(true);
        worker.start();
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        for (int n = 0; n < 8; n++) {
            if (current instanceof InvocationTargetException) {
                Throwable next = ((InvocationTargetException) current).getTargetException();
                if (next != null) {
                    current = next;
                    continue;
                }
            }
            if (current.getCause() != null && current.getCause() != current) {
                current = current.getCause();
                continue;
            }
            break;
        }
        return current;
    }

    private static Bitmap captureScreenshot() throws Exception {
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                return captureNewScreenCaptureApi();
            } catch (ClassNotFoundException | NoSuchMethodException unsupported) {
                Log.w(TAG, "Modern API unavailable; testing legacy API", unsupported);
            }
        }
        return captureLegacyScreenCaptureApi();
    }

    /**
     * Android 16/17 exposes ScreenCaptureParams and asynchronous ScreenCapture.capture,
     * replacing the older ScreenCapture CaptureArgs/SynchronousScreenCaptureListener.
     * The server will reject capture if the Shizuku shell lacks READ_FRAME_BUFFER.
     */
    private static Bitmap captureNewScreenCaptureApi() throws Exception {
        Class<?> screenCapture = Class.forName("android.window.ScreenCapture");
        Class<?> params = Class.forName("android.window.ScreenCapture$ScreenCaptureParams");
        Class<?> builderClass = Class.forName("android.window.ScreenCapture$ScreenCaptureParams$Builder");
        Class<?> resultClass = Class.forName("android.window.ScreenCapture$ScreenCaptureResult");

        Constructor<?> constructor = builderClass.getConstructor(int.class);
        Object builder = constructor.newInstance(DISPLAY_ID);
        builderClass.getMethod("setSecureContentPolicy", int.class).invoke(builder, 1);
        Object captureParams = builderClass.getMethod("build").invoke(builder);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Object> captured = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();

        OutcomeReceiver<Object, Exception> receiver = new OutcomeReceiver<Object, Exception>() {
            @Override
            public void onResult(Object value) {
                captured.set(value);
                latch.countDown();
            }

            @Override
            public void onError(Exception problem) {
                error.set(problem);
                latch.countDown();
            }
        };

        Executor directExecutor = Runnable::run;
        Method capture = screenCapture.getMethod(
                "capture", params, Executor.class, OutcomeReceiver.class);
        capture.invoke(null, captureParams, directExecutor, receiver);

        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("ScreenCapture callback timeout");
        }
        if (error.get() != null) {
            throw new IllegalStateException("ScreenCapture callback error", error.get());
        }
        Object result = captured.get();
        if (result == null) {
            throw new IllegalStateException("ScreenCapture callback returned null");
        }

        HardwareBuffer buffer = (HardwareBuffer) resultClass.getMethod("getHardwareBuffer")
                .invoke(result);
        ColorSpace colorSpace = (ColorSpace) resultClass.getMethod("getColorSpace")
                .invoke(result);
        return bufferToBitmap(buffer, colorSpace);
    }

    private static Bitmap bufferToBitmap(HardwareBuffer buffer, ColorSpace colorSpace) {
        if (buffer == null) throw new IllegalStateException("Screenshot hardware buffer is null");
        try (HardwareBuffer unused = buffer) {
            ColorSpace cs = colorSpace != null
                    ? colorSpace
                    : ColorSpace.get(ColorSpace.Named.SRGB);
            Bitmap hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, cs);
            if (hardwareBitmap == null) {
                throw new IllegalStateException("Unable to wrap hardware bitmap");
            }
            Bitmap softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (softwareBitmap == null) {
                throw new IllegalStateException("Unable to convert hardware bitmap");
            }
            return softwareBitmap;
        }
    }

    private static Bitmap captureLegacyScreenCaptureApi() throws Exception {
        String screenSize = runCommand("wm size");
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("(\\d+)x(\\d+)").matcher(screenSize);
        if (!matcher.find()) throw new IllegalStateException("Unable to read screen dimensions: " + screenSize);
        int width = Integer.parseInt(matcher.group(1));
        int height = Integer.parseInt(matcher.group(2));

        Class<?> builderClass = Class.forName("android.window.ScreenCapture$CaptureArgs$Builder");
        Constructor<?> constructor = builderClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object builder = constructor.newInstance();
        builderClass.getMethod("setSourceCrop", Rect.class)
                .invoke(builder, new Rect(0, 0, width, height));
        builderClass.getMethod("setCaptureSecureLayers", boolean.class)
                .invoke(builder, true);
        Object captureArgs = builderClass.getMethod("build").invoke(builder);

        Class<?> screenCapture = Class.forName("android.window.ScreenCapture");
        Class<?> listenerClass = Class.forName(
                "android.window.ScreenCapture$SynchronousScreenCaptureListener");
        Object listener = screenCapture.getMethod("createSyncCaptureListener").invoke(null);
        Class<?> globalClass = Class.forName("android.view.WindowManagerGlobal");
        Object wm = globalClass.getMethod("getWindowManagerService").invoke(null);
        Class<?> argsClass = Class.forName("android.window.ScreenCapture$CaptureArgs");
        Class<?> callbackClass = Class.forName("android.window.ScreenCapture$ScreenCaptureListener");
        Method capture = wm.getClass().getMethod(
                "captureDisplay", int.class, argsClass, callbackClass);
        capture.invoke(wm, DISPLAY_ID, captureArgs, listener);

        Object screenBuffer = listenerClass.getMethod("getBuffer").invoke(listener);
        if (screenBuffer == null) throw new IllegalStateException("No buffer from legacy capture");
        Object bitmapObj = screenBuffer.getClass().getMethod("asBitmap").invoke(screenBuffer);
        if (!(bitmapObj instanceof Bitmap)) throw new IllegalStateException("Legacy screenshot not Bitmap");
        Bitmap original = (Bitmap) bitmapObj;
        Bitmap copy = original.copy(Bitmap.Config.ARGB_8888, false);
        if (copy == null) throw new IllegalStateException("Legacy bitmap conversion failed");
        return copy;
    }

    private static String runCommand(String command) throws Exception {
        Process process = Runtime.getRuntime().exec(command);
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line).append('\n');
            }
        }
        int code = process.waitFor();
        if (code != 0) throw new IllegalStateException("Command " + command + " exited " + code);
        return result.toString();
    }
}
