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
import dadb.AdbShellResponse;
import dadb.Dadb;

public class AdbManager {

    private static final String TAG = "AdbManager";
    private static final int ADB_PORT = 5555;
    private static final int SCAN_TIMEOUT_MS = 400;

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
        File publicKey  = new File(keyDir, "adbkey.pub");
        if (!privateKey.exists() || !publicKey.exists()) {
            AdbKeyPair.generate(privateKey, publicKey);
        }
        return AdbKeyPair.read(privateKey, publicKey);
    }

    private static List<String> getAllSubnets(Context context) {
        List<String> subnets = new ArrayList<>();
        try {
            WifiManager wm = (WifiManager)
                context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) {
                int ipInt = wm.getConnectionInfo().getIpAddress();
                if (ipInt != 0) {
                    String ip = formatIp(ipInt);
                    if (!ip.startsWith("127.") && !ip.equals("0.0.0.0")
                            && !ip.startsWith("10.0.2.") && !ip.startsWith("10.0.3.")) {
                        String sub = ip.substring(0, ip.lastIndexOf('.') + 1);
                        if (!subnets.contains(sub)) subnets.add(sub);
                    }
                }
            }
        } catch (Exception e) { Log.e(TAG, "WM: " + e.getMessage()); }

        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces != null) {
                for (NetworkInterface iface : Collections.list(ifaces)) {
                    if (iface.isLoopback()) continue;
                    String n = iface.getName().toLowerCase();
                    if (n.contains("dummy") || n.contains("tun") || n.contains("ppp")
                            || n.contains("rmnet") || n.contains("usb")
                            || n.contains("rndis") || n.contains("p2p")) continue;
                    for (InetAddress a : Collections.list(iface.getInetAddresses())) {
                        if (a.isLoopbackAddress()) continue;
                        if (!(a instanceof java.net.Inet4Address)) continue;
                        String ip = a.getHostAddress();
                        if (ip.startsWith("10.0.2.") || ip.startsWith("10.0.3.")) continue;
                        if (ip.startsWith("192.168.") || ip.startsWith("172.")
                                || ip.startsWith("10.")) {
                            String sub = ip.substring(0, ip.lastIndexOf('.') + 1);
                            if (!subnets.contains(sub)) subnets.add(sub);
                        }
                    }
                }
            }
        } catch (Exception e) { Log.e(TAG, "Ifaces: " + e.getMessage()); }

        subnets.sort((a, b) -> Boolean.compare(
                !a.startsWith("192.168."), !b.startsWith("192.168.")));
        return subnets;
    }

    public static void scanForDevices(Context context, ScanCallback callback) {
        new Thread(() -> {
            WifiManager wm = (WifiManager)
                context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) {
                callback.onError("WiFi is not enabled.\n\nConnect to the same WiFi as your Firestick.");
                return;
            }
            List<String> subnets = getAllSubnets(context);
            if (subnets.isEmpty()) {
                callback.onError("No WiFi network detected.\n\nUse the manual IP box below.\nFind IP: Settings → My Fire TV → About → Network");
                return;
            }
            List<FirestickDevice> found = new ArrayList<>();
            List<String> seen = new ArrayList<>();
            ExecutorService ex = Executors.newFixedThreadPool(60);
            for (String subnet : subnets) {
                for (int i = 1; i <= 254; i++) {
                    final String ip = subnet + i;
                    ex.submit(() -> {
                        try {
                            Socket s = new Socket();
                            s.connect(new InetSocketAddress(ip, ADB_PORT), SCAN_TIMEOUT_MS);
                            s.close();
                            synchronized (seen) {
                                if (seen.contains(ip)) return;
                                seen.add(ip);
                            }
                            FirestickDevice dev = new FirestickDevice(
                                    ip, "Amazon Fire TV (" + ip + ")", ADB_PORT);
                            synchronized (found) { found.add(dev); }
                            callback.onDeviceFound(dev);
                        } catch (Exception ignored) {}
                    });
                }
            }
            ex.shutdown();
            try { ex.awaitTermination(20, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) {}
            callback.onScanComplete(found);
        }).start();
    }

    public static void installApp(String deviceIp, int devicePort,
                                   AppModel app, InstallCallback callback,
                                   File cacheDir, Context context) {
        new Thread(() -> {
            try {
                // 1. Download APK
                callback.onProgress(5, "Starting download...");
                File apkFile = new File(cacheDir,
                        app.getPackageName().replaceAll("[^a-zA-Z0-9._]", "_") + ".apk");

                callback.onProgress(10, "Downloading " + app.getName() + "...");
                URL url = new URL(app.getApkUrl());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(180000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36");
                conn.connect();

                if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    callback.onError("Download failed (HTTP " + conn.getResponseCode() + ").");
                    return;
                }
                String ct = conn.getContentType();
                if (ct != null && ct.contains("text/html")) {
                    callback.onError("That URL points to a webpage, not an APK.\nPlease use a direct download link.");
                    conn.disconnect();
                    return;
                }

                int total = conn.getContentLength();
                java.io.InputStream in = conn.getInputStream();
                FileOutputStream fos = new FileOutputStream(apkFile);
                byte[] buf = new byte[16384];
                int dl = 0, r;
                while ((r = in.read(buf)) != -1) {
                    fos.write(buf, 0, r);
                    dl += r;
                    if (total > 0) {
                        int pct = 10 + (int)(55.0 * dl / total);
                        callback.onProgress(pct,
                            "Downloading... " + formatSize(dl) + " / " + formatSize(total));
                    }
                }
                fos.close();
                in.close();
                conn.disconnect();
                Log.d(TAG, "Downloaded: " + apkFile.length() + " bytes");

                // 2. ADB keys
                callback.onProgress(67, "Setting up secure connection...");
                AdbKeyPair keyPair = getOrCreateKeyPair(context);

                // 3. Connect
                callback.onProgress(70, "Connecting to Firestick at " + deviceIp + "...");
                Dadb dadb;
                try {
                    dadb = Dadb.create(deviceIp, devicePort, keyPair);
                } catch (Exception ce) {
                    callback.onError("Cannot connect to Firestick.\n\n" +
                        "If a popup appeared — tap ALLOW and try again.\n\n" +
                        "Check: Settings → My Fire TV → Developer Options → ADB Debugging ON\n\n" +
                        "Error: " + ce.getMessage());
                    return;
                }

                // 4. Push APK to Firestick
                callback.onProgress(73, "Transferring APK to Firestick...");
                String remotePath = "/data/local/tmp/phoenix_install.apk";
                try {
                    dadb.push(apkFile, remotePath, 0644, System.currentTimeMillis());
                    Log.d(TAG, "Push complete");
                } catch (Exception pushEx) {
                    try { dadb.close(); } catch (Exception ignored) {}
                    callback.onError("Failed to transfer APK.\n\nError: " + pushEx.getMessage());
                    return;
                }

                // 5. Verify the file arrived on Firestick
                callback.onProgress(83, "Verifying transfer...");
                try {
                    AdbShellResponse lsResponse = dadb.shell("ls -la " + remotePath);
                    Log.d(TAG, "ls output: " + lsResponse.getAllOutput());
                } catch (Exception ignored) {}

                // 6. Run pm install and READ the output to confirm success
                callback.onProgress(86, "Installing " + app.getName() + "...");
                String installOutput = "";
                try {
                    AdbShellResponse installResponse = dadb.shell(
                        "pm install -r --allow-test " + remotePath);
                    installOutput = installResponse.getAllOutput();
                    Log.d(TAG, "pm install output: " + installOutput);
                } catch (Exception shellEx) {
                    Log.e(TAG, "Shell error: " + shellEx.getMessage());
                    // Try without flags if first attempt failed
                    try {
                        AdbShellResponse retryResponse = dadb.shell(
                            "pm install -r " + remotePath);
                        installOutput = retryResponse.getAllOutput();
                        Log.d(TAG, "Retry pm install output: " + installOutput);
                    } catch (Exception retryEx) {
                        try { dadb.shell("rm " + remotePath); } catch (Exception ig) {}
                        try { dadb.close(); } catch (Exception ig) {}
                        callback.onError("Install command failed.\n\nError: " + retryEx.getMessage());
                        return;
                    }
                }

                // 7. Clean up temp file
                try { dadb.shell("rm " + remotePath); } catch (Exception ignored) {}

                // 8. Check output for success or failure
                callback.onProgress(97, "Checking result...");
                if (installOutput.toLowerCase().contains("success")) {
                    dadb.close();
                    callback.onProgress(100, "✅ Installed successfully!");
                    callback.onSuccess(app.getName());
                } else if (installOutput.toLowerCase().contains("failure")
                        || installOutput.toLowerCase().contains("error")) {
                    dadb.close();
                    // Parse the failure reason
                    String reason = installOutput.trim();
                    if (reason.contains("INSTALL_FAILED_ALREADY_EXISTS")) {
                        // Already installed - that's fine!
                        callback.onProgress(100, "✅ Already installed!");
                        callback.onSuccess(app.getName());
                    } else if (reason.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE")) {
                        callback.onError("Not enough storage on your Firestick.\n\nFree up space and try again.");
                    } else if (reason.contains("INSTALL_PARSE_FAILED")) {
                        callback.onError("The APK file is corrupted or not compatible with your Firestick.\n\nTry again — it may have downloaded incorrectly.");
                    } else {
                        callback.onError("Install failed.\n\nReason: " + reason);
                    }
                } else {
                    // Output unclear — check if app is actually installed
                    try {
                        AdbShellResponse checkResponse = dadb.shell(
                            "pm list packages | grep " + app.getPackageName());
                        String checkOutput = checkResponse.getAllOutput();
                        Log.d(TAG, "Package check: " + checkOutput);
                        dadb.close();
                        if (checkOutput.contains(app.getPackageName())) {
                            callback.onProgress(100, "✅ Installed successfully!");
                            callback.onSuccess(app.getName());
                        } else {
                            callback.onError("Install result unclear.\n\nOutput: " + installOutput
                                + "\n\nCheck your Firestick manually.");
                        }
                    } catch (Exception checkEx) {
                        try { dadb.close(); } catch (Exception ig) {}
                        callback.onError("Could not verify install.\n\nOutput: " + installOutput);
                    }
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

    private static String formatSize(int b) {
        if (b < 1024) return b + " B";
        if (b < 1048576) return (b / 1024) + " KB";
        return String.format("%.1f MB", b / 1048576.0);
    }
}
