package com.phoenixfire.installer;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private AppAdapter adapter;
    private List<AppModel> appList;
    private Button btnScanDevices;
    private TextView tvDeviceStatus;
    private FirestickDevice selectedDevice;
    private LinearLayout headerLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        recyclerView = findViewById(R.id.recyclerView);
        btnScanDevices = findViewById(R.id.btnScanDevices);
        tvDeviceStatus = findViewById(R.id.tvDeviceStatus);
        headerLayout = findViewById(R.id.headerLayout);

        appList = AppListParser.loadApps(this);

        adapter = new AppAdapter(appList, app -> {
            if (selectedDevice == null) {
                Toast.makeText(this, "Please find your Firestick first!", Toast.LENGTH_SHORT).show();
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
            startActivity(intent);
        });

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        btnScanDevices.setOnClickListener(v -> {
            Intent intent = new Intent(this, DeviceScanActivity.class);
            startActivityForResult(intent, 100);
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            String ip = data.getStringExtra("device_ip");
            int port = data.getIntExtra("device_port", 5555);
            String name = data.getStringExtra("device_name");
            selectedDevice = new FirestickDevice(ip, name, port);
            tvDeviceStatus.setText("🔥 Connected: " + name);
            tvDeviceStatus.setVisibility(View.VISIBLE);
            btnScanDevices.setText("Change Device");
        }
    }
}
