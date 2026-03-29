package com.phoenixfire.installer;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import dadb.AdbKeyPair;
import dadb.Dadb;

public class AdbManager {

    private static final String TAG = "AdbManager";
    private static final int ADB_PORT = 5555;
    private static final int SCAN_TIMEOUT_MS = 500;

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

    private static AdbKeyPair getOrCreateKeyPair(Context context) throws Exception {
        File keyDir = new File(context.getFilesDir(), "adbkeys");
        keyDir.mkdirs();
        File privateKey = new File(keyDir, "adbkey");
        File publicKey = new File(keyDir, "adbkey.pub");
        if (!privateKey.exists() || !publicKey.exists()) {
            AdbKeyPair.generate(privateKey, publicKey);
        }
        return AdbKeyPair.read(privateKey, publicKey);
    }

    /**
     * Returns only valid home network subnets.
     * Strictly excludes 10.0.2.x (USB/emulator) and loopback.
     * Prioritises 192.168.x.x which is what every home router uses.
     */
    private static List<String> getAllSubnets(Context context) {
        List<String> subnets = new ArrayList<>();

        // Method 1: WifiManager - most accurate for the actual WiFi IP
        try {
            WifiManager wifiManager = (WifiManager)
                    context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null && wifiManager.isWifiEnabled()) {
                int ipInt = wifiManager.getConnectionInfo().getIpAddress();
                if (ipInt != 0) {
                    String ip = formatIp(ipInt);
                    Log.d(TAG, "WifiManager IP: " + ip);
                    if (!ip.startsWith("127.") && !ip.equals("0.0.0.0")
                            && !ip.startsWith("10.0.2.") && !ip.startsWith("10.0.3.")) {
                        String subnet = ip.substring(0, ip.lastIndexOf('.') + 1);
                        if (!subnets.contains(subnet)) {
                            subnets.add(subnet);
                            Log.d(TAG, "Added subnet from WifiManager: " + subnet);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "WifiManager error: " + e.getMessage());
        }

        // Method 2: Network interfaces
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                for (NetworkInterface iface : Collections.list(interfaces)) {
                    if (iface.isLoopback()) continue;
                    String name = iface.getName().toLowerCase();
                    // Skip virtual/USB/mobile interfaces
                    if (name.contains("dummy") || name.contains("tun") ||
                        name.contains("ppp") || name.contains("rmnet") ||
                        name.contains("usb") || name.contains("rndis") ||
                        name.contains("p2p")) continue;

                    for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
                        if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                            String ip = addr.getHostAddress();
                            // Skip USB/emulator ranges
                            if (ip.startsWith("10.0.2.") || ip.startsWith("10.0.3.")) continue;

                            if (ip.startsWith("192.168.") || ip.startsWith("172.") ||
                                ip.startsWith("10.")) {
                                String subnet = ip.substring(0, ip.lastIndexOf('.') + 1);
                                if (!subnets.contains(subnet)) {
                                    subnets.add(subnet);
                                    Log.d(TAG, "Added subnet from " + name + ": " + subnet);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Interface scan error: " + e.getMessage());
        }

        // Always ensure we scan 192.168.x.x ranges if any were found
        // Sort so 192.168.x.x comes first
        subnets.sort((a, b) -> {
            boolean a192 = a.startsWith("192.168.");
            boolean b192 = b.startsWith("192.168.");
            if (a192 && !b192) return -1;
            if (!a192 && b192) return 1;
            return 0;
        });

        return subnets;
    }

    public static void scanForDevices(Context context, ScanCallback callback) {
        new Thread(() -> {
            List<FirestickDevice> found = new ArrayList<>();
            List<String> alreadyFoundIps = new ArrayList<>();

            WifiManager wifiManager = (WifiManager)
                    context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null || !wifiManager.isWifiEnabled()) {
                callback.onError("WiFi is not enabled.\n\nPlease connect to the same WiFi as your Firestick.");
                return;
            }

            List<String> subnets = getAllSubnets(context);
            Log.d(TAG, "Subnets to scan: " + subnets);

            if (subnets.isEmpty()) {
                callback.onError("Could not detect WiFi network.\n\nPlease use the manual IP entry box below.\n\nFind IP on Firestick: Settings → My Fire TV → About → Network");
                return;
            }

            ExecutorService executor = Executors.newFixedThreadPool(50);

            for (String subnet : subnets) {
                for (int i = 1; i <= 254; i++) {
                    final String ip = subnet + i;
                    executor.submit(() -> {
                        try {
                            Socket socket = new Socket();
                            socket.connect(new InetSocketAddress(ip, ADB_PORT), SCAN_TIMEOUT_MS);
                            socket.close();
                            synchronized (alreadyFoundIps) {
                                if (alreadyFoundIps.contains(ip)) return;
                                alreadyFoundIps.add(ip);
                            }
                            FirestickDevice device = new FirestickDevice(
                                    ip, "Amazon Fire TV (" + ip + ")", ADB_PORT);
                            synchronized (found) { found.add(device); }
                            callback.onDeviceFound(device);
                            Log.d(TAG, "Found ADB device at: " + ip);
                        } catch (Exception ignored) {}
                    });
                }
            }

            executor.shutdown();
            try { executor.awaitTermination(25, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) {}

            callback.onScanComplete(found);
        }).start();
    }

    public static void installApp(String deviceIp, int devicePort,
                                   AppModel app, InstallCallback callback,
                                   File cacheDir, Context context) {
        new Thread(() -> {
            try {
                callback.onProgress(5, "Preparing download...");
                File apkFile = new File(cacheDir,
                        app.getPackageName().replaceAll("[^a-zA-Z0-9._]", "_") + ".apk");

                // Download
                callback.onProgress(10, "Downloading " + app.getName() + "...");
                URL url = new URL(app.getApkUrl());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(120000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android 11; Mobile)");
                conn.connect();

                int responseCode = conn.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    callback.onError("Download failed (HTTP " + responseCode + ").");
                    return;
                }

                String contentType = conn.getContentType();
                if (contentType != null && contentType.contains("text/html")) {
                    callback.onError("The APK URL returned a webpage, not an APK.\n\nPlease update the URL in app_list.json to a direct download link.");
                    conn.disconnect();
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
                        int pct = 10 + (int)(55.0 * downloaded / total);
                        callback.onProgress(pct, "Downloading... " + formatSize(downloaded) + " / " + formatSize(total));
                    }
                }
                fos.close();
                inStream.close();
                conn.disconnect();

                Log.d(TAG, "Downloaded " + apkFile.length() + " bytes to " + apkFile.getAbsolutePath());

                // ADB keys
                callback.onProgress(68, "Preparing secure connection...");
                AdbKeyPair keyPair = getOrCreateKeyPair(context);

                // Connect
                callback.onProgress(72, "Connecting to Firestick at " + deviceIp + "...");

                Dadb connection;
                try {
                    connection = Dadb.create(deviceIp, devicePort, keyPair);
                } catch (Exception connectEx) {
                    callback.onError("Could not connect to Firestick at " + deviceIp + ".\n\n" +
                            "If a popup appeared on your Firestick — tap ALLOW and try again.\n\n" +
                            "Check ADB Debugging is ON:\nSettings → My Fire TV → Developer Options\n\n" +
                            "Error: " + connectEx.getMessage());
                    return;
                }

                // Install — this can take 30-90 seconds for large apps, keep user informed
                callback.onProgress(78, "Sending " + app.getName() + " to Firestick...\n\nThis may take 1-2 minutes for large apps. Please wait...");

                try {
                    // Run install on a thread with progress updates
                    final boolean[] done = {false};
                    final Exception[] installError = {null};

                    Thread installThread = new Thread(() -> {
                        try {
                            connection.install(apkFile);
                        } catch (Exception e) {
                            installError[0] = e;
                        } finally {
                            done[0] = true;
                        }
                    });
                    installThread.start();

                    // Show animated progress while install runs
                    int progress = 78;
                    String[] messages = {
                        "Sending to Firestick... please wait",
                        "Installing on Firestick... almost there",
                        "Finalising install... nearly done"
                    };
                    int msgIdx = 0;
                    while (!done[0]) {
                        Thread.sleep(3000);
                        if (progress < 95) progress += 2;
                        callback.onProgress(progress, messages[msgIdx % messages.length]);
                        msgIdx++;
                    }

                    installThread.join();

                    if (installError[0] != null) {
                        throw installError[0];
                    }

                    connection.close();
                    callback.onProgress(100, "✅ Installed successfully!");
                    callback.onSuccess(app.getName());

                } catch (Exception installEx) {
                    Log.e(TAG, "Install failed: " + installEx.getMessage());
                    try { connection.close(); } catch (Exception ignored) {}
                    callback.onError("Install failed.\n\n" +
                            "If a popup appeared on Firestick — tap ALLOW and try again.\n\n" +
                            "Error: " + installEx.getMessage());
                }

            } catch (Exception e) {
                Log.e(TAG, "Install error", e);
                callback.onError("Error: " + e.getMessage());
            }
        }).start();
    }

    private static String formatIp(int ip) {
        return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "."
                + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
    }

    private static String formatSize(int bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
