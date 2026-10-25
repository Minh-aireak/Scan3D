package com.example.scan3d;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.example.scan3d.api.ApiClient;
import com.example.scan3d.model.Job;
import com.example.scan3d.util.AppPrefs;
import com.example.scan3d.util.UiUtils;

import java.io.File;
import java.text.Normalizer;

/**
 * Theo dõi tiến trình xử lý trên server (hỏi lại mỗi 3 giây).
 * Khi xong: xem mô hình 3D (model-viewer trong WebView), xem ảnh AI tách nền, xuất file GLB/OBJ/PLY.
 */
public class JobDetailActivity extends AppCompatActivity {

    public static final String EXTRA_JOB_ID = "job_id";
    private static final long POLL_INTERVAL_MS = 3000;
    private static final int COLOR_ERROR = 0xFFC62828;

    private TextView tvName;
    private TextView tvStatus;
    private TextView tvStep;
    private TextView tvInfo;
    private ProgressBar progressJob;
    private WebView webView;
    private View panelExport;
    private Button btnToggleView;
    private Button btnLog;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollTask = this::poll;
    private ApiClient api;
    private String jobId;
    private String jobName = "model";
    private boolean active = false;
    private boolean viewerLoaded = false;
    private boolean showingModel = false;

    public static void open(Context context, String jobId) {
        Intent intent = new Intent(context, JobDetailActivity.class);
        intent.putExtra(EXTRA_JOB_ID, jobId);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_job_detail);
        UiUtils.applySystemBarPadding(findViewById(R.id.main));

        jobId = getIntent().getStringExtra(EXTRA_JOB_ID);
        if (jobId == null) {
            finish();
            return;
        }
        api = new ApiClient(AppPrefs.getServerUrl(this));

        tvName = findViewById(R.id.tvName);
        tvStatus = findViewById(R.id.tvStatus);
        tvStep = findViewById(R.id.tvStep);
        tvInfo = findViewById(R.id.tvInfo);
        progressJob = findViewById(R.id.progressJob);
        webView = findViewById(R.id.webView);
        panelExport = findViewById(R.id.panelExport);
        btnToggleView = findViewById(R.id.btnToggleView);
        btnLog = findViewById(R.id.btnLog);
        Button btnGlb = findViewById(R.id.btnGlb);
        Button btnObj = findViewById(R.id.btnObj);
        Button btnPly = findViewById(R.id.btnPly);

        // model-viewer là thư viện JavaScript nên cần bật JavaScript cho WebView
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        webView.setWebViewClient(new WebViewClient());

        btnGlb.setOnClickListener(v -> downloadAndShare("model.glb"));
        btnObj.setOnClickListener(v -> downloadAndShare("model.obj"));
        btnPly.setOnClickListener(v -> downloadAndShare("model.ply"));
        btnToggleView.setOnClickListener(v -> {
            if (showingModel) showMaskPreview();
            else showModel();
        });
        btnLog.setOnClickListener(v -> {
            webView.setVisibility(View.VISIBLE);
            webView.loadUrl(api.jobFileUrl(jobId, "log.txt"));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        active = true;
        poll();
    }

    @Override
    protected void onPause() {
        super.onPause();
        active = false;
        handler.removeCallbacks(pollTask);
    }

    private void poll() {
        api.getJob(jobId, new ApiClient.ResultCallback<Job>() {
            @Override
            public void onSuccess(Job job) {
                if (!active) return;
                render(job);
                handler.removeCallbacks(pollTask);
                if (job.isRunning()) handler.postDelayed(pollTask, POLL_INTERVAL_MS);
            }

            @Override
            public void onError(String message) {
                if (!active) return;
                tvStep.setText(message + "\nĐang thử lại...");
                handler.removeCallbacks(pollTask);
                handler.postDelayed(pollTask, POLL_INTERVAL_MS * 2);
            }
        });
    }

    private void render(Job job) {
        jobName = job.name;
        boolean done = Job.DONE.equals(job.status);
        boolean failed = Job.FAILED.equals(job.status);

        tvName.setText(job.name);
        tvStatus.setText(job.statusText());
        progressJob.setVisibility(job.isRunning() ? View.VISIBLE : View.GONE);
        progressJob.setProgress(job.progress);

        String step = job.isRunning() ? job.step + " (" + job.progress + "%)" : job.step;
        if (!job.message.isEmpty()) step += "\n" + job.message;
        tvStep.setText(step);
        if (failed) tvStep.setTextColor(COLOR_ERROR);
        tvInfo.setText(job.summary());

        panelExport.setVisibility(done ? View.VISIBLE : View.GONE);
        btnToggleView.setVisibility(done && job.outputs.contains("masks_preview.jpg") ? View.VISIBLE : View.GONE);
        btnLog.setVisibility(failed ? View.VISIBLE : View.GONE);

        if (done && !viewerLoaded) {
            viewerLoaded = true;
            showModel();
        }
    }

    private void showModel() {
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(api.viewerUrl(jobId));
        btnToggleView.setText("Xem ảnh AI tách nền");
        showingModel = true;
    }

    private void showMaskPreview() {
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(api.jobFileUrl(jobId, "masks_preview.jpg"));
        btnToggleView.setText("Xem mô hình 3D");
        showingModel = false;
    }

    /** Tải file mô hình về máy rồi mở hộp thoại chia sẻ (lưu vào Drive, gửi Zalo, mở bằng app 3D...). */
    private void downloadAndShare(String fileName) {
        File dir = getExternalFilesDir("models");
        if (dir == null) dir = new File(getFilesDir(), "models");
        String extension = fileName.substring(fileName.lastIndexOf('.'));
        File dest = new File(dir, safeFileName(jobName) + extension);

        Toast.makeText(this, "Đang tải " + dest.getName() + "...", Toast.LENGTH_SHORT).show();
        api.download(api.jobFileUrl(jobId, fileName), dest, new ApiClient.ResultCallback<File>() {
            @Override
            public void onSuccess(File file) {
                if (isDestroyed()) return;
                shareFile(file);
            }

            @Override
            public void onError(String message) {
                if (isDestroyed()) return;
                Toast.makeText(JobDetailActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void shareFile(File file) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("application/octet-stream");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Lưu hoặc chia sẻ " + file.getName()));
    }

    /** Bỏ dấu tiếng Việt và ký tự đặc biệt để tên file mở được trên mọi ứng dụng. */
    private static String safeFileName(String name) {
        String s = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd')
                .replace('Đ', 'D')
                .replaceAll("[^A-Za-z0-9_-]+", "_")
                .replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "model" : s;
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
