package com.phoenixfire.installer;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class DeviceScanActivity extends AppCompatActivity {

    private RecyclerView rvDevices;
    private ProgressBar progressBar;
    private TextView tvStatus;
    private Button btnRescan;
    private List<FirestickDevice> deviceList = new ArrayList<>();
    private DeviceAdapter deviceAdapter;
    private Handler mainHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_scan);

        rvDevices = findViewById(R.id.rvDevices);
        progressBar = findViewById(R.id.progressBar);
        tvStatus = findViewById(R.id.tvStatus);
        btnRescan = findViewById(R.id.btnRescan);
        mainHandler = new Handler(Looper.getMainLooper());

        deviceAdapter = new DeviceAdapter(deviceList, device -> {
            Intent result = new Intent();
            result.putExtra("device_ip", device.getIpAddress());
            result.putExtra("device_port", device.getPort());
            result.putExtra("device_name", device.getName());
            setResult(RESULT_OK, result);
            finish();
        });

        rvDevices.setLayoutManager(new LinearLayoutManager(this));
        rvDevices.setAdapter(deviceAdapter);

        btnRescan.setOnClickListener(v -> startScan());
        startScan();
    }

    private void startScan() {
        deviceList.clear();
        deviceAdapter.notifyDataSetChanged();
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("🔍 Scanning your WiFi network for Fire TV devices...\n\nMake sure:\n• Firestick is on same WiFi\n• ADB Debugging is ON (Settings > My Fire TV > Developer Options)");
        btnRescan.setEnabled(false);

        AdbManager.scanForDevices(this, new AdbManager.ScanCallback() {
            @Override
            public void onDeviceFound(FirestickDevice device) {
                mainHandler.post(() -> {
                    deviceList.add(device);
                    deviceAdapter.notifyItemInserted(deviceList.size() - 1);
                });
            }

            @Override
            public void onScanComplete(List<FirestickDevice> devices) {
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    btnRescan.setEnabled(true);
                    if (devices.isEmpty()) {
                        tvStatus.setText("❌ No Fire TV devices found.\n\nPlease check:\n• Both devices on same WiFi\n• Go to Settings > My Fire TV > Developer Options\n• Enable ADB Debugging\n• Enable Apps from Unknown Sources");
                    } else {
                        tvStatus.setText("✅ Found " + devices.size() + " device(s). Tap to connect:");
                    }
                });
            }

            @Override
            public void onError(String message) {
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    btnRescan.setEnabled(true);
                    tvStatus.setText("⚠️ " + message);
                });
            }
        });
    }
}
