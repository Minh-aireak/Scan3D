package com.example.scan3d;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.example.scan3d.api.ApiClient;
import com.example.scan3d.util.AppPrefs;
import com.example.scan3d.util.UiUtils;

import org.json.JSONObject;

/** Màn hình chính: nhập địa chỉ server, kiểm tra kết nối, bắt đầu quét hoặc xem mô hình đã tạo. */
public class MainActivity extends AppCompatActivity {

    private EditText edtServer;
    private TextView tvServerStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        UiUtils.applySystemBarPadding(findViewById(R.id.main));

        edtServer = findViewById(R.id.edtServer);
        tvServerStatus = findViewById(R.id.tvServerStatus);
        Button btnCheck = findViewById(R.id.btnCheck);
        Button btnScan = findViewById(R.id.btnScan);
        Button btnJobs = findViewById(R.id.btnJobs);

        edtServer.setText(AppPrefs.getServerUrl(this));

        btnCheck.setOnClickListener(v -> {
            if (saveServerUrl()) checkServer();
        });
        btnScan.setOnClickListener(v -> {
            if (saveServerUrl()) startActivity(new Intent(this, CaptureActivity.class));
        });
        btnJobs.setOnClickListener(v -> {
            if (saveServerUrl()) startActivity(new Intent(this, JobListActivity.class));
        });
    }

    private boolean saveServerUrl() {
        String url = AppPrefs.normalizeUrl(edtServer.getText().toString());
        if (url.isEmpty()) {
            Toast.makeText(this, "Địa chỉ server không hợp lệ", Toast.LENGTH_SHORT).show();
            return false;
        }
        AppPrefs.setServerUrl(this, url);
        edtServer.setText(url);
        return true;
    }

    private void checkServer() {
        tvServerStatus.setText("Đang kiểm tra kết nối...");
        new ApiClient(AppPrefs.getServerUrl(this)).checkHealth(new ApiClient.ResultCallback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject info) {
                boolean colmap = info.optBoolean("colmap");
                tvServerStatus.setText("✓ Đã kết nối server"
                        + "\nCOLMAP: " + (colmap ? "sẵn sàng" : "CHƯA cài hoặc sai đường dẫn")
                        + "\nMô hình AI tách nền: " + info.optString("rembg_model")
                        + "\nCông việc đang chờ: " + info.optInt("queue"));
            }

            @Override
            public void onError(String message) {
                tvServerStatus.setText("✗ " + message);
            }
        });
    }
}
