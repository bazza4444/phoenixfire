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

    /**
     * Gets or creates the ADB key pair used to authenticate with the Firestick.
     * Stored in the app's private files directory so it persists between sessions.
     */
    private static AdbKeyPair getOrCreateKeyPair(Context context) throws Exception {
        File keyDir = new File(context.getFilesDir(), "adbkeys");
        keyDir.mkdirs();
        File privateKey = new File(keyDir, "adbkey");
        File publicKey = new File(keyDir, "adbkey.pub");

        if (!privateKey.exists() || !publicKey.exists()) {
            Log.d(TAG, "Generating new ADB key pair...");
            AdbKeyPair.generate(privateKey, publicKey);
            Log.d(TAG, "ADB key pair generated successfully");
        } else {
            Log.d(TAG, "Using existing ADB key pair");
        }

        return AdbKeyPair.read(privateKey, publicKey);
    }

    private static String getWifiIpAddress(Context context) {
        try {
            WifiManager wifiManager = (WifiManager)
                    context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null && wifiManager.isWifiEnabled()) {
                int ipInt = wifiManager.getConnectionInfo().getIpAddress();
                if (ipInt != 0) {
                    String ip = formatIp(ipInt);
                    if (!ip.startsWith("127.") && !ip.startsWith("10.0.2.")
                            && !ip.equals("0.0.0.0")) {
                        Log.d(TAG, "WiFi IP from WifiManager: " + ip);
                        return ip;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "WifiManager failed: " + e.getMessage());
        }

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                List<NetworkInterface> ifaceList = Collections.list(interfaces);

                for (NetworkInterface iface : ifaceList) {
                    String name = iface.getName().toLowerCase();
                    if (iface.isLoopback()) continue;
                    if (name.contains("rmnet") || name.contains("dummy") ||
                        name.contains("p2p") || name.contains("tun") ||
                        name.contains("ppp") || name.contains("usb") ||
                        name.contains("rndis")) continue;

                    Enumeration<InetAddress> addresses = iface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                            String ip = addr.getHostAddress();
                            if (ip.startsWith("192.168.") || ip.startsWith("172.")) {
                                Log.d(TAG, "WiFi IP from interface " + name + ": " + ip);
                                return ip;
                            }
                        }
                    }
                }

                for (NetworkInterface iface : ifaceList) {
                    String name = iface.getName().toLowerCase();
                    if (iface.isLoopback()) continue;
                    if (name.contains("rmnet") || name.contains("dummy") ||
                        name.contains("p2p") || name.contains("tun") ||
                        name.contains("ppp") || name.contains("usb") ||
                        name.contains("rndis")) continue;

                    Enumeration<InetAddress> addresses = iface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                            String ip = addr.getHostAddress();
                            if (ip.startsWith("10.") && !ip.startsWith("10.0.2.")) {
                                Log.d(TAG, "WiFi IP (10.x) from interface " + name + ": " + ip);
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

            WifiManager wifiManager = (WifiManager)
                    context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null || !wifiManager.isWifiEnabled()) {
                callback.onError("WiFi is not enabled.\n\nPlease connect your phone to the same WiFi network as your Firestick.");
                return;
            }

            String localIp = getWifiIpAddress(context);
            if (localIp == null) {
                callback.onError("Could not detect your WiFi IP.\n\nPlease use the manual IP entry box below.\n\nFind your Firestick IP: Settings → My Fire TV → About → Network");
                return;
            }

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
                                   File cacheDir, Context context) {
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
                    callback.onError("Download failed (HTTP " + conn.getResponseCode() + ").\nCheck the APK URL is valid.");
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

                // Generate/load ADB key pair
                callback.onProgress(72, "Preparing ADB keys...");
                AdbKeyPair keyPair = getOrCreateKeyPair(context);

                // Connect and install via ADB
                callback.onProgress(76, "Connecting to Firestick at " + deviceIp + "...");
                callback.onProgress(78, "⚠️ If prompted on your Firestick — tap ALLOW to authorise this device!");

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
                            "If your Firestick showed an 'Allow ADB debugging' popup — tap ALLOW and try again.\n\n" +
                            "Also check:\n" +
                            "• ADB Debugging is ON (Settings → My Fire TV → Developer Options)\n" +
                            "• Phone and Firestick on same WiFi\n\n" +
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
