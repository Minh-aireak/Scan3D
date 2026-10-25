package com.example.scan3d;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Bundle;
import android.util.Size;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.example.scan3d.api.ApiClient;
import com.example.scan3d.model.Job;
import com.example.scan3d.util.AppPrefs;
import com.example.scan3d.util.ImageQuality;
import com.example.scan3d.util.ObjectGuideAnalyzer;
import com.example.scan3d.util.UiUtils;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Màn hình chụp ảnh quanh vật thể:
 * - AI (ML Kit) nhận diện vật thể theo thời gian thực để hướng dẫn căn khung.
 * - Mỗi ảnh chụp xong được kiểm tra độ nét/độ sáng (thuật toán Laplacian).
 * - Chụp đủ ảnh thì gửi toàn bộ lên server để dựng mô hình 3D.
 */
public class CaptureActivity extends AppCompatActivity {

    /** Phải khớp với MIN_IMAGES của server. */
    private static final int MIN_PHOTOS = 10;
    private static final int RECOMMENDED_PHOTOS = 40;
    /** Ảnh 4:3 khoảng 2.7MP là đủ cho Photogrammetry, lại nhẹ khi gửi lên server. */
    private static final Size PHOTO_SIZE = new Size(1920, 1440);

    private static final int COLOR_READY = 0xCC2E7D32;
    private static final int COLOR_WARN = 0xCCE65100;
    private static final int COLOR_NONE = 0x99000000;

    private PreviewView previewView;
    private TextView tvGuide;
    private TextView tvCount;
    private ImageView imgLast;
    private Button btnCapture;
    private Button btnUndo;
    private Button btnUpload;
    private View panelUpload;
    private TextView tvUpload;
    private ProgressBar progressUpload;

    private ImageCapture imageCapture;
    private ObjectGuideAnalyzer analyzer;
    private ExecutorService cameraExecutor;   // phân tích khung hình bằng AI
    private ExecutorService ioExecutor;       // lưu ảnh và kiểm tra chất lượng
    private File sessionDir;
    private final ArrayList<File> photos = new ArrayList<>();
    private int nextIndex = 0;
    private boolean uploading = false;

    private final ActivityResultLauncher<String> cameraPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
                    Toast.makeText(this, "Cần quyền camera để quét vật thể", Toast.LENGTH_LONG).show();
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_capture);
        UiUtils.applySystemBarPadding(findViewById(R.id.main));

        previewView = findViewById(R.id.previewView);
        tvGuide = findViewById(R.id.tvGuide);
        tvCount = findViewById(R.id.tvCount);
        imgLast = findViewById(R.id.imgLast);
        btnCapture = findViewById(R.id.btnCapture);
        btnUndo = findViewById(R.id.btnUndo);
        btnUpload = findViewById(R.id.btnUpload);
        panelUpload = findViewById(R.id.panelUpload);
        tvUpload = findViewById(R.id.tvUpload);
        progressUpload = findViewById(R.id.progressUpload);

        sessionDir = new File(getCacheDir(), "capture_" + System.currentTimeMillis());
        if (!sessionDir.mkdirs()) {
            Toast.makeText(this, "Không tạo được thư mục lưu ảnh", Toast.LENGTH_LONG).show();
        }
        cameraExecutor = Executors.newSingleThreadExecutor();
        ioExecutor = Executors.newSingleThreadExecutor();

        btnCapture.setOnClickListener(v -> takePhoto());
        btnUndo.setOnClickListener(v -> removeLastPhoto());
        btnUpload.setOnClickListener(v -> askNameAndUpload());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmExit();
            }
        });

        updateCount();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ResolutionSelector selector = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(new ResolutionStrategy(PHOTO_SIZE,
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build();
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setResolutionSelector(selector)
                        .setJpegQuality(92)
                        .build();

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analyzer = new ObjectGuideAnalyzer(this::showGuide);
                analysis.setAnalyzer(cameraExecutor, analyzer);

                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, imageCapture, analysis);
            } catch (Exception e) {
                Toast.makeText(this, "Không mở được camera: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void showGuide(ObjectGuideAnalyzer.Guide guide) {
        tvGuide.setText(guide.message);
        tvGuide.setBackgroundColor(guide.ready ? COLOR_READY : guide.found ? COLOR_WARN : COLOR_NONE);
    }

    private void takePhoto() {
        if (imageCapture == null || uploading) return;
        btnCapture.setEnabled(false);
        File file = new File(sessionDir, String.format(Locale.US, "img_%04d.jpg", nextIndex++));
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(file).build();
        imageCapture.takePicture(options, ioExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
                // Đang ở luồng nền: kiểm tra chất lượng và tạo ảnh nhỏ trước khi về giao diện
                ImageQuality.Result quality = ImageQuality.analyze(file);
                Bitmap thumbnail = decodeThumbnail(file);
                runOnUiThread(() -> onPhotoSaved(file, quality, thumbnail));
            }

            @Override
            public void onError(@NonNull ImageCaptureException e) {
                runOnUiThread(() -> {
                    btnCapture.setEnabled(true);
                    Toast.makeText(CaptureActivity.this, "Chụp ảnh lỗi: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void onPhotoSaved(File file, ImageQuality.Result quality, Bitmap thumbnail) {
        if (isFinishing()) return;
        btnCapture.setEnabled(true);
        photos.add(file);
        imgLast.setImageBitmap(thumbnail);
        updateCount();

        if (quality != null && (quality.isBlurry() || quality.isTooDark())) {
            String reason = quality.isBlurry() ? "bị mờ" : "quá tối";
            new AlertDialog.Builder(this)
                    .setTitle("Ảnh chất lượng thấp")
                    .setMessage(String.format(Locale.US,
                            "Ảnh vừa chụp %s (độ nét %.0f, độ sáng %.0f).\n"
                                    + "Ảnh kém làm mô hình 3D bị lỗi. Nên chụp lại.",
                            reason, quality.sharpness, quality.brightness))
                    .setPositiveButton("Chụp lại", (d, w) -> removeLastPhoto())
                    .setNegativeButton("Vẫn giữ", null)
                    .show();
        }
    }

    private void removeLastPhoto() {
        if (photos.isEmpty()) return;
        File last = photos.remove(photos.size() - 1);
        if (!last.delete()) last.deleteOnExit();
        imgLast.setImageBitmap(photos.isEmpty() ? null : decodeThumbnail(photos.get(photos.size() - 1)));
        updateCount();
    }

    private void updateCount() {
        int n = photos.size();
        String text = String.format(Locale.US, "Đã chụp: %d ảnh (nên chụp %d–80 ảnh)", n, RECOMMENDED_PHOTOS);
        if (n < MIN_PHOTOS) text += String.format(Locale.US, "\nCần thêm ít nhất %d ảnh", MIN_PHOTOS - n);
        tvCount.setText(text);
        btnUndo.setEnabled(n > 0 && !uploading);
        btnUpload.setEnabled(n >= MIN_PHOTOS && !uploading);
    }

    private void askNameAndUpload() {
        EditText input = new EditText(this);
        input.setHint("Tên vật thể");
        input.setText("Vật thể " + new SimpleDateFormat("dd/MM HH:mm", Locale.US).format(new Date()));

        FrameLayout container = new FrameLayout(this);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("Gửi " + photos.size() + " ảnh lên server")
                .setView(container)
                .setPositiveButton("Gửi", (d, w) -> upload(input.getText().toString().trim()))
                .setNegativeButton("Hủy", null)
                .show();
    }

    private void upload(String name) {
        final String jobName = name.isEmpty() ? "Vật thể" : name;
        uploading = true;
        btnCapture.setEnabled(false);
        updateCount();
        panelUpload.setVisibility(View.VISIBLE);
        progressUpload.setProgress(0);
        tvUpload.setText("Đang gửi ảnh... 0%");

        ApiClient api = new ApiClient(AppPrefs.getServerUrl(this));
        api.createJob(jobName, new ArrayList<>(photos), percent -> {
            progressUpload.setProgress(percent);
            tvUpload.setText(percent < 100 ? "Đang gửi ảnh... " + percent + "%" : "Server đang nhận ảnh...");
        }, new ApiClient.ResultCallback<Job>() {
            @Override
            public void onSuccess(Job job) {
                deleteSession();
                JobDetailActivity.open(CaptureActivity.this, job.id);
                finish();
            }

            @Override
            public void onError(String message) {
                uploading = false;
                panelUpload.setVisibility(View.GONE);
                btnCapture.setEnabled(true);
                updateCount();
                new AlertDialog.Builder(CaptureActivity.this)
                        .setTitle("Gửi ảnh thất bại")
                        .setMessage(message + "\n\nẢnh vẫn được giữ lại, hãy kiểm tra kết nối rồi gửi lại.")
                        .setPositiveButton("OK", null)
                        .show();
            }
        });
    }

    private void confirmExit() {
        if (uploading) {
            Toast.makeText(this, "Đang gửi ảnh, vui lòng chờ", Toast.LENGTH_SHORT).show();
            return;
        }
        if (photos.isEmpty()) {
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Thoát chế độ quét?")
                .setMessage("Các ảnh đã chụp (" + photos.size() + " ảnh) sẽ bị xóa.")
                .setPositiveButton("Thoát", (d, w) -> finish())
                .setNegativeButton("Ở lại", null)
                .show();
    }

    private void deleteSession() {
        File[] files = sessionDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.delete()) f.deleteOnExit();
            }
        }
        if (!sessionDir.delete()) sessionDir.deleteOnExit();
        photos.clear();
    }

    /** Ảnh nhỏ để xem lại; xoay theo thông tin EXIF vì CameraX ghi hướng xoay vào EXIF. */
    private static Bitmap decodeThumbnail(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 8;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) return null;
        try {
            int orientation = new ExifInterface(file.getAbsolutePath())
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int degrees = 0;
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) degrees = 90;
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) degrees = 180;
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) degrees = 270;
            if (degrees != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(degrees);
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            }
        } catch (IOException ignored) {
            // không đọc được EXIF thì hiển thị ảnh như cũ
        }
        return bitmap;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (analyzer != null) analyzer.close();
        cameraExecutor.shutdown();
        ioExecutor.shutdown();
        if (isFinishing() && !uploading) deleteSession();
    }
}
