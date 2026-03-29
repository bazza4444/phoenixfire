package com.phoenixfire.installer;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private AppAdapter adapter;
    private List<AppModel> appList;
    private MaterialButton btnScanDevices;
    private TextView tvDeviceStatus, tvChangeDevice, tvAppCount;
    private View layoutDeviceStatus;
    private FirestickDevice selectedDevice;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        recyclerView = findViewById(R.id.recyclerView);
        btnScanDevices = findViewById(R.id.btnScanDevices);
        tvDeviceStatus = findViewById(R.id.tvDeviceStatus);
        tvChangeDevice = findViewById(R.id.tvChangeDevice);
        tvAppCount = findViewById(R.id.tvAppCount);
        layoutDeviceStatus = findViewById(R.id.layoutDeviceStatus);

        appList = AppListParser.loadApps(this);
        tvAppCount.setText(appList.size() + " apps");

        adapter = new AppAdapter(appList, app -> {
            if (selectedDevice == null) {
                Toast.makeText(this,
                    "Find your Firestick first!", Toast.LENGTH_SHORT).show();
                launchScan();
                return;
            }
            Intent intent = new Intent(this, AppDetailActivity.class);
            intent.putExtra("app_name", app.getName());
            intent.putExtra("app_version", app.getVersion());
            intent.putExtra("app_description", app.getDescription());
            intent.putExtra("app_url", app.getApkUrl());
            intent.putExtra("app_package", app.getPackageName());
            intent.putExtra("device_ip", selectedDevice.getIpAddress());
            intent.putExtra("device_port", selectedDevice.getPort());
            intent.putExtra("device_name", selectedDevice.getName());
            startActivity(intent);
        });

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        btnScanDevices.setOnClickListener(v -> launchScan());
        tvChangeDevice.setOnClickListener(v -> launchScan());
    }

    private void launchScan() {
        startActivityForResult(new Intent(this, DeviceScanActivity.class), 100);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            String ip = data.getStringExtra("device_ip");
            int port = data.getIntExtra("device_port", 5555);
            String name = data.getStringExtra("device_name");
            selectedDevice = new FirestickDevice(ip, name, port);
            tvDeviceStatus.setText("Connected: " + name);
            layoutDeviceStatus.setVisibility(View.VISIBLE);
            btnScanDevices.setVisibility(View.GONE);
        }
    }
}
