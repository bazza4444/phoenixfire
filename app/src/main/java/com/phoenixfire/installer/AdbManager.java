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
            Log.d(TAG, "Generating new ADB key pair...");
            AdbKeyPair.generate(privateKey, publicKey);
        }
        return AdbKeyPair.read(privateKey, publicKey);
    }

    /**
     * Collects ALL private network subnets from all interfaces.
     * This means it scans 192.168.x.x, 10.x.x.x etc — all of them.
     * So it will always find the Firestick regardless of which interface the phone uses.
     */
    private static List<String> getAllSubnets(Context context) {
        List<String> subnets = new ArrayList<>();

        // Method 1: WifiManager
        try {
            WifiManager wifiManager = (WifiManager)
                    context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null && wifiManager.isWifiEnabled()) {
                int ipInt = wifiManager.getConnectionInfo().getIpAddress();
                if (ipInt != 0) {
                    String ip = formatIp(ipInt);
                    if (!ip.startsWith("127.") && !ip.equals("0.0.0.0")) {
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

        // Method 2: All network interfaces
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                for (NetworkInterface iface : Collections.list(interfaces)) {
                    if (iface.isLoopback()) continue;
                    String name = iface.getName().toLowerCase();
                    if (name.contains("dummy") || name.contains("tun") ||
                        name.contains("ppp") || name.contains("rmnet")) continue;

                    for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
                        if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                            String ip = addr.getHostAddress();
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

        return subnets;
    }

    public static void scanForDevices(Context context, ScanCallback callback) {
        new Thread(() -> {
            List<FirestickDevice> found = new ArrayList<>();
            List<String> alreadyFound = new ArrayList<>();

            WifiManager wifiManager = (WifiManager)
                    context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null || !wifiManager.isWifiEnabled()) {
                callback.onError("WiFi is not enabled.\n\nPlease connect to the same WiFi as your Firestick.");
                return;
            }

            List<String> subnets = getAllSubnets(context);

            if (subnets.isEmpty()) {
                callback.onError("Could not detect any network.\n\nUse the manual IP entry box below.\n\nFind IP on Firestick: Settings → My Fire TV → About → Network");
                return;
            }

            Log.d(TAG, "Scanning " + subnets.size() + " subnet(s): " + subnets);

            ExecutorService executor = Executors.newFixedThreadPool(50);

            for (String subnet : subnets) {
                for (int i = 1; i <= 254; i++) {
                    final String ip = subnet + i;
                    executor.submit(() -> {
                        try {
                            Socket socket = new Socket();
                            socket.connect(new InetSocketAddress(ip, ADB_PORT), SCAN_TIMEOUT_MS);
                            socket.close();
                            synchronized (alreadyFound) {
                                if (alreadyFound.contains(ip)) return;
                                alreadyFound.add(ip);
                            }
                            FirestickDevice device = new FirestickDevice(
                                    ip, "Amazon Fire TV (" + ip + ")", ADB_PORT);
                            synchronized (found) { found.add(device); }
                            callback.onDeviceFound(device);
                            Log.d(TAG, "Found device at: " + ip);
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

                callback.onProgress(10, "Downloading " + app.getName() + "...");
                URL url = new URL(app.getApkUrl());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(120000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "PhoenixFire/1.0");
                conn.connect();

                int responseCode = conn.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    callback.onError("Download failed (HTTP " + responseCode + ").\nThe APK URL may be invalid.");
                    return;
                }

                // Check content type — must be APK not HTML
                String contentType = conn.getContentType();
                if (contentType != null && contentType.contains("text/html")) {
                    callback.onError("The APK URL returned a webpage, not an APK file.\n\nPlease update the APK URL in app_list.json to a direct download link.");
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
                        int pct = 10 + (int)(60.0 * downloaded / total);
                        callback.onProgress(pct, "Downloading... " + formatSize(downloaded) + " / " + formatSize(total));
                    }
                }
                fos.close();
                inStream.close();
                conn.disconnect();

                callback.onProgress(72, "Preparing ADB keys...");
                AdbKeyPair keyPair = getOrCreateKeyPair(context);

                callback.onProgress(76, "Connecting to Firestick at " + deviceIp + "...");
                callback.onProgress(78, "If prompted on Firestick — tap ALLOW!");

                try {
                    Dadb connection = Dadb.create(deviceIp, devicePort, keyPair);
                    callback.onProgress(85, "Installing " + app.getName() + " on Firestick...");
                    connection.install(apkFile);
                    connection.close();
                    callback.onProgress(100, "Done!");
                    callback.onSuccess(app.getName());
                } catch (Exception adbEx) {
                    Log.e(TAG, "ADB install failed: " + adbEx.getMessage());
                    callback.onError("Could not install on Firestick.\n\n" +
                            "If a popup appeared on your Firestick — tap ALLOW and try again.\n\n" +
                            "Check:\n• ADB Debugging ON\n• Same WiFi network\n\n" +
                            "Error: " + adbEx.getMessage());
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
