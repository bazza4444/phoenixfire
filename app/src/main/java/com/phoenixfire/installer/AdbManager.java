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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import dadb.AdbKeyPair;
import dadb.Dadb;

public class AdbManager {

    private static final String TAG = "AdbManager";
    private static final int ADB_PORT = 5555;
    private static final int SCAN_TIMEOUT_MS = 400;
    private static final int INSTALL_TIMEOUT_MINUTES = 5;

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

    // ─── Scanner ─────────────────────────────────────────────────────────────

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
                        Log.d(TAG, "WifiManager subnet: " + sub);
                    }
                }
            }
        } catch (Exception e) { Log.e(TAG, "WifiManager: " + e.getMessage()); }

        try {
            for (NetworkInterface iface : Collections.list(
                    NetworkInterface.getNetworkInterfaces())) {
                if (iface.isLoopback()) continue;
                String n = iface.getName().toLowerCase();
                if (n.contains("dummy") || n.contains("tun") || n.contains("ppp")
                        || n.contains("rmnet") || n.contains("usb")
                        || n.contains("rndis") || n.contains("p2p")) continue;

                for (InetAddress a : Collections.list(iface.getInetAddresses())) {
                    if (a.isLoopbackAddress() || !(a instanceof java.net.Inet4Address)) continue;
                    String ip = a.getHostAddress();
                    if (ip.startsWith("10.0.2.") || ip.startsWith("10.0.3.")) continue;
                    if (ip.startsWith("192.168.") || ip.startsWith("172.")
                            || ip.startsWith("10.")) {
                        String sub = ip.substring(0, ip.lastIndexOf('.') + 1);
                        if (!subnets.contains(sub)) {
                            subnets.add(sub);
                            Log.d(TAG, "Interface " + n + " subnet: " + sub);
                        }
                    }
                }
            }
        } catch (Exception e) { Log.e(TAG, "Interfaces: " + e.getMessage()); }

        // 192.168.x first
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
                callback.onError("Could not detect WiFi network.\n\nUse the manual IP box below.\n\nFind IP: Settings → My Fire TV → About → Network");
                return;
            }

            Log.d(TAG, "Scanning subnets: " + subnets);
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
                            Log.d(TAG, "Found: " + ip);
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

    // ─── Installer ───────────────────────────────────────────────────────────

    public static void installApp(String deviceIp, int devicePort,
                                   AppModel app, InstallCallback callback,
                                   File cacheDir, Context context) {
        new Thread(() -> {
            try {
                // 1. Download
                callback.onProgress(5, "Preparing download...");
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
                        "If a popup appeared on Firestick — tap ALLOW and try again.\n\n" +
                        "Make sure ADB Debugging is ON:\n" +
                        "Settings → My Fire TV → Developer Options\n\n" +
                        "Error: " + ce.getMessage());
                    return;
                }

                // 4. Install with timeout + live progress ticks
                callback.onProgress(73, "Sending " + app.getName() + " to Firestick...");

                ExecutorService installEx = Executors.newSingleThreadExecutor();
                final Exception[] err = {null};

                Future<?> installFuture = installEx.submit(() -> {
                    try {
                        dadb.install(apkFile);
                    } catch (Exception e) {
                        err[0] = e;
                    }
                });

                // Tick progress while waiting (73 → 97 over up to 5 min)
                String[] ticks = {
                    "Sending APK to Firestick...",
                    "Installing on Firestick...",
                    "Writing app to storage...",
                    "Almost done, please wait...",
                    "Finishing up..."
                };
                int tick = 0;
                int pct = 73;
                long deadline = System.currentTimeMillis() + INSTALL_TIMEOUT_MINUTES * 60 * 1000L;

                while (!installFuture.isDone()) {
                    Thread.sleep(2500);
                    if (System.currentTimeMillis() > deadline) {
                        installFuture.cancel(true);
                        try { dadb.close(); } catch (Exception ignored) {}
                        callback.onError("Install timed out after " + INSTALL_TIMEOUT_MINUTES
                            + " minutes.\n\nThe Firestick may still be installing — "
                            + "check your Firestick screen.");
                        return;
                    }
                    if (pct < 97) pct++;
                    callback.onProgress(pct, ticks[tick % ticks.length]);
                    tick++;
                }
                installEx.shutdown();

                try { dadb.close(); } catch (Exception ignored) {}

                if (err[0] != null) {
                    throw err[0];
                }

                callback.onProgress(100, "✅ Installed successfully!");
                callback.onSuccess(app.getName());

            } catch (Exception e) {
                Log.e(TAG, "Install error", e);
                callback.onError("Install failed.\n\n" +
                    "If a popup appeared on Firestick — tap ALLOW and try again.\n\n" +
                    "Error: " + e.getMessage());
            }
        }).start();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

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
