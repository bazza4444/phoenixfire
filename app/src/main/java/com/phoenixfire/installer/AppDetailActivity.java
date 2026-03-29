package com.phoenixfire.installer;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;

public class AppDetailActivity extends AppCompatActivity {

    private TextView tvAppName, tvVersion, tvDescription, tvStatus, tvTargetDevice;
    private MaterialButton btnInstall;
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
        tvTargetDevice = findViewById(R.id.tvTargetDevice);
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
        String deviceName = getIntent().getStringExtra("device_name");

        tvAppName.setText(name);
        tvVersion.setText("Version " + version);
        tvDescription.setText(description);
        tvTargetDevice.setText("Target: " + deviceName + "  •  " + deviceIp + ":" + devicePort);

        AppModel app = new AppModel(name, version, description, "", url, packageName, "");

        btnInstall.setOnClickListener(v -> {
            btnInstall.setEnabled(false);
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setProgress(0);
            tvStatus.setText("Starting...");

            AdbManager.installApp(deviceIp, devicePort, app,
                new AdbManager.InstallCallback() {
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
                            tvStatus.setText("✅ " + appName +
                                " installed successfully on your Firestick!");
                            btnInstall.setText("✓ INSTALLED");
                            btnInstall.setEnabled(false);
                        });
                    }

                    @Override
                    public void onError(String message) {
                        mainHandler.post(() -> {
                            tvStatus.setText("❌ " + message);
                            btnInstall.setEnabled(true);
                            btnInstall.setText("🚀  TRY AGAIN");
                            progressBar.setVisibility(View.GONE);
                        });
                    }
                }, getCacheDir());
        });
    }
}
