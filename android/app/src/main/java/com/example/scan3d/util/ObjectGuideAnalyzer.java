package com.example.scan3d.util;

import android.graphics.Rect;
import android.media.Image;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.objects.DetectedObject;
import com.google.mlkit.vision.objects.ObjectDetection;
import com.google.mlkit.vision.objects.ObjectDetector;
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions;

import java.util.List;
import java.util.Locale;

/**
 * AI trên thiết bị: dùng mô hình nhận diện vật thể có sẵn của Google ML Kit để hướng dẫn người dùng
 * giữ vật thể ở giữa khung hình với kích thước phù hợp trước khi chụp.
 */
public class ObjectGuideAnalyzer implements ImageAnalysis.Analyzer {

    /** Vật thể chiếm ít hơn tỉ lệ này của khung hình thì nhắc lại gần. */
    private static final float MIN_AREA = 0.08f;
    /** Vật thể chiếm nhiều hơn tỉ lệ này thì nhắc lùi ra (cần thấy trọn vật thể). */
    private static final float MAX_AREA = 0.85f;
    /** Tâm vật thể lệch khỏi tâm khung quá tỉ lệ này thì nhắc căn giữa. */
    private static final float MAX_OFFSET = 0.2f;

    public interface Listener {
        void onGuide(Guide guide);
    }

    public static class Guide {
        public final boolean found;
        /** Vật thể ở giữa khung và kích thước vừa phải: có thể chụp. */
        public final boolean ready;
        public final String message;

        Guide(boolean found, boolean ready, String message) {
            this.found = found;
            this.ready = ready;
            this.message = message;
        }
    }

    private final ObjectDetector detector;
    private final Listener listener;

    public ObjectGuideAnalyzer(Listener listener) {
        this.listener = listener;
        ObjectDetectorOptions options = new ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                .enableClassification()
                .build();
        detector = ObjectDetection.getClient(options);
    }

    @Override
    @OptIn(markerClass = ExperimentalGetImage.class)
    public void analyze(@NonNull ImageProxy imageProxy) {
        Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            imageProxy.close();
            return;
        }
        int rotation = imageProxy.getImageInfo().getRotationDegrees();
        // ML Kit trả về toạ độ theo ảnh đã xoay đứng, nên đổi chiều rộng/cao khi xoay 90° hoặc 270°
        boolean swap = rotation == 90 || rotation == 270;
        int width = swap ? imageProxy.getHeight() : imageProxy.getWidth();
        int height = swap ? imageProxy.getWidth() : imageProxy.getHeight();

        InputImage input = InputImage.fromMediaImage(mediaImage, rotation);
        detector.process(input)
                .addOnSuccessListener(objects -> listener.onGuide(evaluate(objects, width, height)))
                .addOnCompleteListener(task -> imageProxy.close());
    }

    public void close() {
        detector.close();
    }

    private static Guide evaluate(List<DetectedObject> objects, int width, int height) {
        if (objects.isEmpty()) {
            return new Guide(false, false, "Chưa thấy vật thể. Hướng camera vào vật cần quét.");
        }
        DetectedObject best = objects.get(0);
        for (DetectedObject o : objects) {
            if (area(o.getBoundingBox()) > area(best.getBoundingBox())) best = o;
        }
        Rect box = best.getBoundingBox();
        String label = labelOf(best);
        float areaRatio = area(box) / (float) (width * height);
        float dx = Math.abs(box.exactCenterX() / width - 0.5f);
        float dy = Math.abs(box.exactCenterY() / height - 0.5f);

        if (areaRatio < MIN_AREA) {
            return new Guide(true, false, "Phát hiện " + label + ". Lại gần hơn một chút.");
        }
        if (areaRatio > MAX_AREA) {
            return new Guide(true, false, "Phát hiện " + label + ". Lùi ra xa để thấy trọn vật thể.");
        }
        if (dx > MAX_OFFSET || dy > MAX_OFFSET) {
            return new Guide(true, false, "Phát hiện " + label + ". Đưa vật thể vào giữa khung.");
        }
        return new Guide(true, true, "✓ " + capitalize(label) + " ở giữa khung. Có thể chụp.");
    }

    private static int area(Rect r) {
        return Math.max(0, r.width()) * Math.max(0, r.height());
    }

    /** Đổi nhãn tiếng Anh của mô hình ML Kit sang tiếng Việt. */
    private static String labelOf(DetectedObject object) {
        String best = null;
        float confidence = 0;
        for (DetectedObject.Label label : object.getLabels()) {
            if (label.getConfidence() > confidence) {
                confidence = label.getConfidence();
                best = label.getText();
            }
        }
        if (best == null) return "vật thể";
        switch (best) {
            case "Home good":
                return "đồ gia dụng";
            case "Fashion good":
                return "đồ thời trang";
            case "Food":
                return "thực phẩm";
            case "Plant":
                return "cây";
            case "Place":
                return "địa điểm";
            default:
                return "vật thể";
        }
    }

    private static String capitalize(String s) {
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}
