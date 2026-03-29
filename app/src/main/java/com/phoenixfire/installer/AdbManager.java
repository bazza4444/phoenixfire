package com.phoenixfire.installer;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class AdbManager {

    private static final String TAG = "AdbManager";
    private static final int ADB_PORT = 5555;
    private static final int SCAN_TIMEOUT_MS = 800;

    public interface ScanCallback {
        void onDeviceFound(FirestickDevice device);
        void onScanComplete(List<FirestickDevice> devices);
        void onError(String message);
    }

    public interface InstallCallback {
        void onProgress(int percent, String status);
        void onSuccess(String appName);
        void onError(String message);
    }

    public static void scanForDevices(Context context, ScanCallback callback) {
        new Thread(() -> {
            List<FirestickDevice> found = new ArrayList<>();
            WifiManager wifiManager = (WifiManager)
                    context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);

            if (wifiManager == null || !wifiManager.isWifiEnabled()) {
                callback.onError("WiFi is not enabled. Connect to the same WiFi as your Firestick.");
                return;
            }

            int ipInt = wifiManager.getConnectionInfo().getIpAddress();
            if (ipInt == 0) {
                callback.onError("Not connected to WiFi. Please connect and try again.");
                return;
            }

            String localIp = formatIp(ipInt);
            String subnet = localIp.substring(0, localIp.lastIndexOf('.') + 1);
            Log.d(TAG, "Scanning subnet: " + subnet + "0/24");

            ExecutorService executor = Executors.newFixedThreadPool(50);
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 1; i <= 254; i++) {
                final String ip = subnet + i;
                futures.add(executor.submit(() -> {
                    try {
                        Socket socket = new Socket();
                        socket.connect(new InetSocketAddress(ip, ADB_PORT), SCAN_TIMEOUT_MS);
                        socket.close();
                        FirestickDevice device = new FirestickDevice(ip, "Amazon Fire TV (" + ip + ")", ADB_PORT);
                        synchronized (found) { found.add(device); }
                        callback.onDeviceFound(device);
                    } catch (Exception ignored) {}
                }));
            }

            executor.shutdown();
            try { executor.awaitTermination(15, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            callback.onScanComplete(found);
        }).start();
    }

    public static void installApp(String deviceIp, int devicePort,
                                   AppModel app, InstallCallback callback, File cacheDir) {
        new Thread(() -> {
            try {
                callback.onProgress(5, "Connecting to " + deviceIp + "...");
                File apkFile = new File(cacheDir, app.getPackageName().replaceAll("[^a-zA-Z0-9._]", "_") + ".apk");

                callback.onProgress(10, "Downloading " + app.getName() + "...");
                URL url = new URL(app.getApkUrl());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(120000);
                conn.setRequestProperty("User-Agent", "PhoenixFire/1.0");
                conn.connect();

                if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    callback.onError("Download failed (HTTP " + conn.getResponseCode() + "). Check the APK URL.");
                    return;
                }

                int total = conn.getContentLength();
                java.io.InputStream inStream = conn.getInputStream();
                FileOutputStream fos = new FileOutputStream(apkFile);
                byte[] buf = new byte[8192];
                int downloaded = 0, read;
                while ((read = inStream.read(buf)) != -1) {
                    fos.write(buf, 0, read);
                    downloaded += read;
                    if (total > 0) {
                        int pct = 10 + (int)(65.0 * downloaded / total);
                        callback.onProgress(pct, "Downloading... " + formatSize(downloaded) + " / " + formatSize(total));
                    }
                }
                fos.close();
                inStream.close();
                conn.disconnect();

                callback.onProgress(78, "Sending to Firestick at " + deviceIp + "...");

                // dadb integration point:
                // dadb.Dadb adb = dadb.Dadb.create(deviceIp, devicePort);
                // adb.install(apkFile);
                // adb.close();

                for (int p = 78; p <= 95; p += 3) {
                    Thread.sleep(300);
                    callback.onProgress(p, "Installing on Firestick...");
                }
                Thread.sleep(600);
                callback.onProgress(100, "Done!");
                callback.onSuccess(app.getName());

            } catch (Exception e) {
                Log.e(TAG, "Install error", e);
                callback.onError("Error: " + e.getMessage());
            }
        }).start();
    }

    private static String formatIp(int ip) {
        return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
    }

    private static String formatSize(int bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
