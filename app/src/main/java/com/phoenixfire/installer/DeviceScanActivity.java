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
import com.google.android.material.button.MaterialButton;
import java.util.ArrayList;
import java.util.List;

public class DeviceScanActivity extends AppCompatActivity {

    private RecyclerView rvDevices;
    private ProgressBar progressBar;
    private TextView tvStatus;
    private MaterialButton btnRescan;
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
        btnRescan.setEnabled(false);
        btnRescan.setText("Scanning...");
        tvStatus.setText("Scanning your WiFi network for Firestick devices...\n\nThis may take up to 20 seconds.");

        AdbManager.scanForDevices(this, new AdbManager.ScanCallback() {
            @Override
            public void onDeviceFound(FirestickDevice device) {
                mainHandler.post(() -> {
                    deviceList.add(device);
                    deviceAdapter.notifyItemInserted(deviceList.size() - 1);
                    tvStatus.setText("Found " + deviceList.size() + " device(s) — tap to connect:");
                });
            }

            @Override
            public void onScanComplete(List<FirestickDevice> devices) {
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    btnRescan.setEnabled(true);
                    btnRescan.setText("🔍  SCAN AGAIN");
                    if (devices.isEmpty()) {
                        tvStatus.setText("No Firestick found.\n\n" +
                            "Please check:\n" +
                            "• Firestick and phone on same WiFi\n" +
                            "• Settings → My Fire TV → Developer Options\n" +
                            "• ADB Debugging → ON\n\n" +
                            "Then tap Scan Again.");
                    } else {
                        tvStatus.setText("Found " + devices.size() +
                            " device(s). Tap to connect:");
                    }
                });
            }

            @Override
            public void onError(String message) {
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    btnRescan.setEnabled(true);
                    btnRescan.setText("🔍  SCAN AGAIN");
                    tvStatus.setText(message);
                });
            }
        });
    }
}
