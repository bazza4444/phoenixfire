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
    private static final int MAX_RETRIES = 3;

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

    /**
     * Detect device type from its properties for a friendly name
     */
    private static String detectDeviceType(Dadb dadb, String ip) {
        try {
            AdbShellResponse r = dadb.shell("getprop ro.product.model");
            String model = r.getAllOutput().trim();
            if (!model.isEmpty()) return model + " (" + ip + ")";
        } catch (Exception ignored) {}
        return "Android Device (" + ip + ")";
    }

    public static void scanForDevices(Context context, ScanCallback callback) {
        new Thread(() -> {
            WifiManager wm = (WifiManager)
                context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) {
                callback.onError("WiFi is not enabled.\n\nConnect to the same WiFi as your device.");
                return;
            }
            List<String> subnets = getAllSubnets(context);
            if (subnets.isEmpty()) {
                callback.onError("No WiFi network detected.\n\nUse the manual IP box below.\nFind IP on device: Settings → About → Network / IP Address");
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
                                    ip, "Android Device (" + ip + ")", ADB_PORT);
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
                    callback.onError("That URL returns a webpage, not an APK.\nCheck the download URL in app_list.json.");
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

                // 3. Connect with retry on broken pipe
                callback.onProgress(70, "Connecting to device at " + deviceIp + "...");
                boolean installSuccess = false;
                String lastError = "";

                for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                    if (attempt > 1) {
                        callback.onProgress(70, "Retrying connection (attempt " + attempt + "/" + MAX_RETRIES + ")...");
                        Thread.sleep(2000); // wait before retry
                    }

                    Dadb dadb = null;
                    try {
                        dadb = Dadb.create(deviceIp, devicePort, keyPair);

                        // 4. Push APK
                        callback.onProgress(73, "Transferring APK to device...");
                        String remotePath = "/data/local/tmp/phoenix_install.apk";
                        dadb.push(apkFile, remotePath, 0644, System.currentTimeMillis());
                        Log.d(TAG, "Push complete on attempt " + attempt);

                        // 5. Install
                        callback.onProgress(86, "Installing " + app.getName() + "...");
                        AdbShellResponse installResponse = dadb.shell("pm install -r " + remotePath);
                        String installOutput = installResponse.getAllOutput().trim();
                        Log.d(TAG, "pm install output: [" + installOutput + "]");

                        // 6. Clean up
                        try { dadb.shell("rm " + remotePath); } catch (Exception ig) {}

                        // 7. Verify
                        callback.onProgress(95, "Verifying installation...");
                        boolean verified = false;
                        try {
                            AdbShellResponse checkResp = dadb.shell(
                                "pm list packages | grep " + app.getPackageName());
                            verified = checkResp.getAllOutput().trim()
                                    .contains(app.getPackageName());
                        } catch (Exception ig) {}

                        try { dadb.close(); } catch (Exception ig) {}

                        // 8. Check result
                        if (verified || installOutput.toLowerCase().contains("success")) {
                            installSuccess = true;
                            break;
                        } else if (installOutput.contains("INSTALL_FAILED_ALREADY_EXISTS")) {
                            installSuccess = true;
                            break;
                        } else if (installOutput.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE")) {
                            lastError = "Not enough storage on device.\n\nFree up space and try again.";
                            break; // No point retrying storage issues
                        } else if (installOutput.contains("INSTALL_PARSE_FAILED")) {
                            lastError = "APK not compatible with this device.\n\nThe app may not support this Android version.";
                            break; // No point retrying parse errors
                        } else if (!installOutput.isEmpty()) {
                            lastError = installOutput;
                            // Retry for unknown errors
                        }

                    } catch (Exception attemptEx) {
                        lastError = attemptEx.getMessage();
                        Log.e(TAG, "Attempt " + attempt + " failed: " + lastError);
                        if (dadb != null) {
                            try { dadb.close(); } catch (Exception ig) {}
                        }
                        // Broken pipe or connection reset — retry
                        if (lastError != null && (
                                lastError.contains("Broken pipe") ||
                                lastError.contains("Connection reset") ||
                                lastError.contains("ECONNRESET") ||
                                lastError.contains("EPIPE"))) {
                            Log.d(TAG, "Broken pipe — will retry");
                            continue;
                        }
                        // Other errors — don't retry
                        break;
                    }
                }

                if (installSuccess) {
                    callback.onProgress(100, "✅ Installed successfully!");
                    callback.onSuccess(app.getName());
                } else {
                    callback.onError("Install failed after " + MAX_RETRIES + " attempts.\n\n" +
                        "Last error: " + lastError + "\n\n" +
                        "Please check:\n" +
                        "• ADB Debugging is ON\n" +
                        "• Device and phone on same WiFi\n" +
                        "• Tap ALLOW if a popup appeared on your device");
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
