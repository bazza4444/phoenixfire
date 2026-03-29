package com.phoenixfire.installer;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

public class AppDetailActivity extends AppCompatActivity {

    private TextView tvAppName, tvVersion, tvDescription, tvStatus;
    private Button btnInstall;
    private ProgressBar progressBar;
    private Handler mainHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_detail);

        tvAppName = findViewById(R.id.tvAppName);
        tvVersion = findViewById(R.id.tvVersion);
        tvDescription = findViewById(R.id.tvDescription);
        tvStatus = findViewById(R.id.tvStatus);
        btnInstall = findViewById(R.id.btnInstall);
        progressBar = findViewById(R.id.progressBar);
        mainHandler = new Handler(Looper.getMainLooper());

        String name = getIntent().getStringExtra("app_name");
        String version = getIntent().getStringExtra("app_version");
        String description = getIntent().getStringExtra("app_description");
        String url = getIntent().getStringExtra("app_url");
        String packageName = getIntent().getStringExtra("app_package");
        String deviceIp = getIntent().getStringExtra("device_ip");
        int devicePort = getIntent().getIntExtra("device_port", 5555);

        tvAppName.setText(name);
        tvVersion.setText("Version: " + version);
        tvDescription.setText(description);

        AppModel app = new AppModel(name, version, description, "", url, packageName, "");

        btnInstall.setOnClickListener(v -> {
            btnInstall.setEnabled(false);
            progressBar.setVisibility(View.VISIBLE);
            tvStatus.setText("Starting...");

            AdbManager.installApp(deviceIp, devicePort, app, new AdbManager.InstallCallback() {
                @Override
                public void onProgress(int percent, String status) {
                    mainHandler.post(() -> {
                        progressBar.setProgress(percent);
                        tvStatus.setText(status);
                    });
                }

                @Override
                public void onSuccess(String appName) {
                    mainHandler.post(() -> {
                        tvStatus.setText("✅ " + appName + " installed successfully on your Firestick!");
                        btnInstall.setText("Installed ✓");
                        btnInstall.setEnabled(false);
                    });
                }

                @Override
                public void onError(String message) {
                    mainHandler.post(() -> {
                        tvStatus.setText("❌ " + message);
                        btnInstall.setEnabled(true);
                        progressBar.setVisibility(View.GONE);
                    });
                }
            }, getCacheDir());
        });
    }
}
