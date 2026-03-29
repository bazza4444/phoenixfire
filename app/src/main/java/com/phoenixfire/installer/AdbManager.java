package com.phoenixfire.installer;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
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

    /**
     * Gets the WiFi IP address by iterating ALL network interfaces
     * and picking the one that is actually on WiFi (not loopback, not mobile).
     * This fixes the bug where the wrong IP was returned.
     */
    private static String getWifiIpAddress(Context context) {
        // Method 1: Use WifiManager directly
        try {
            WifiManager wifiManager = (WifiManager)
                    context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null && wifiManager.isWifiEnabled()) {
                int ipInt = wifiManager.getConnectionInfo().getIpAddress();
                if (ipInt != 0) {
                    String ip = formatIp(ipInt);
                    // Make sure it's not a loopback or weird address
                    if (!ip.startsWith("127.") && !ip.equals("0.0.0.0")) {
                        Log.d(TAG, "WiFi IP from WifiManager: " + ip);
                        return ip;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "WifiManager failed: " + e.getMessage());
        }

        // Method 2: Iterate all network interfaces, find WiFi one (wlan0, eth0 etc)
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                List<NetworkInterface> ifaceList = Collections.list(interfaces);
                for (NetworkInterface iface : ifaceList) {
                    String name = iface.getName().toLowerCase();
                    // Skip loopback and mobile data interfaces
                    if (iface.isLoopback()) continue;
                    if (name.contains("rmnet") || name.contains("dummy") ||
                        name.contains("p2p") || name.contains("tun") ||
                        name.contains("ppp")) continue;

                    // Look for WiFi/ethernet interfaces
                    Enumeration<InetAddress> addresses = iface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                            String ip = addr.getHostAddress();
                            // Must be a private network range
                            if (ip.startsWith("192.168.") || ip.startsWith("10.") ||
                                ip.startsWith("172.")) {
                                Log.d(TAG, "WiFi IP from interface " + name + ": " + ip);
                                return ip;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "NetworkInterface scan failed: " + e.getMessage());
        }

        return null;
    }

    public static void scanForDevices(Context context, ScanCallback callback) {
        new Thread(() -> {
            List<FirestickDevice> found = new ArrayList<>();

            // Check WiFi is connected
            WifiManager wifiManager = (WifiManager)
                    context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null || !wifiManager.isWifiEnabled()) {
                callback.onError("WiFi is not enabled.\n\nPlease connect your phone to the same WiFi network as your Firestick.");
                return;
            }

            String localIp = getWifiIpAddress(context);
            if (localIp == null) {
                callback.onError("Could not detect your WiFi IP address.\n\nMake sure your phone is connected to WiFi (not just mobile data).");
                return;
            }

            // Extract subnet (e.g. "192.168.4." from "192.168.4.26")
            String subnet = localIp.substring(0, localIp.lastIndexOf('.') + 1);
            Log.d(TAG, "Scanning subnet: " + subnet + "0/24  (phone IP: " + localIp + ")");

            ExecutorService executor = Executors.newFixedThreadPool(50);

            for (int i = 1; i <= 254; i++) {
                final String ip = subnet + i;
                executor.submit(() -> {
                    try {
                        Socket socket = new Socket();
                        socket.connect(new InetSocketAddress(ip, ADB_PORT), SCAN_TIMEOUT_MS);
                        socket.close();
                        // Port 5555 open — likely a Fire TV with ADB enabled
                        FirestickDevice device = new FirestickDevice(
                                ip, "Amazon Fire TV (" + ip + ")", ADB_PORT);
                        synchronized (found) { found.add(device); }
                        callback.onDeviceFound(device);
                        Log.d(TAG, "Found ADB device at: " + ip);
                    } catch (Exception ignored) {}
                });
            }

            executor.shutdown();
            try { executor.awaitTermination(20, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) {}

            callback.onScanComplete(found);
        }).start();
    }

    public static void installApp(String deviceIp, int devicePort,
                                   AppModel app, InstallCallback callback,
                                   File cacheDir) {
        new Thread(() -> {
            try {
                callback.onProgress(5, "Preparing download...");
                File apkFile = new File(cacheDir,
                        app.getPackageName().replaceAll("[^a-zA-Z0-9._]", "_") + ".apk");

                // Download APK
                callback.onProgress(10, "Downloading " + app.getName() + "...");
                URL url = new URL(app.getApkUrl());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(120000);
                conn.setRequestProperty("User-Agent", "PhoenixFire/1.0");
                conn.connect();

                if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    callback.onError("Download failed (HTTP " + conn.getResponseCode() + ").\nCheck the APK URL is correct.");
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

                // Install via ADB (dadb library)
                callback.onProgress(75, "Connecting to Firestick at " + deviceIp + "...");

                try {
                    dadb.Dadb dadb = dadb.Dadb.create(deviceIp, devicePort);
                    callback.onProgress(82, "Installing " + app.getName() + " on Firestick...");
                    dadb.install(apkFile);
                    dadb.close();
                    callback.onProgress(100, "Done!");
                    callback.onSuccess(app.getName());
                } catch (Exception adbEx) {
                    Log.e(TAG, "ADB install failed: " + adbEx.getMessage());
                    callback.onError("Could not install on Firestick.\n\nPlease check:\n" +
                            "• ADB Debugging is ON (Settings → My Fire TV → Developer Options)\n" +
                            "• Phone and Firestick are on same WiFi\n" +
                            "• Error: " + adbEx.getMessage());
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
