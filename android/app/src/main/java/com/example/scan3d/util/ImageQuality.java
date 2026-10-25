package com.example.scan3d.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;

/**
 * Kiểm tra chất lượng ảnh bằng thuật toán xử lý ảnh cổ điển (không dùng AI):
 * - Độ nét: phương sai của toán tử Laplacian trên ảnh xám. Ảnh mờ có ít cạnh sắc nên phương sai thấp.
 * - Độ sáng: giá trị xám trung bình.
 * Ảnh mờ hoặc quá tối làm COLMAP khó tìm điểm đặc trưng, nên cần loại ngay khi chụp.
 */
public final class ImageQuality {
    /** Ngưỡng độ nét, thấp hơn thì coi là ảnh mờ. Có thể chỉnh lại theo camera của máy. */
    public static final double BLUR_THRESHOLD = 60.0;
    /** Ngưỡng độ sáng (0–255), thấp hơn thì coi là ảnh quá tối. */
    public static final double DARK_THRESHOLD = 40.0;

    /** Thu nhỏ ảnh về cỡ này trước khi tính cho nhanh. */
    private static final int ANALYZE_SIZE = 512;

    private ImageQuality() {
    }

    public static class Result {
        public final double sharpness;
        public final double brightness;

        Result(double sharpness, double brightness) {
            this.sharpness = sharpness;
            this.brightness = brightness;
        }

        public boolean isBlurry() {
            return sharpness < BLUR_THRESHOLD;
        }

        public boolean isTooDark() {
            return brightness < DARK_THRESHOLD;
        }
    }

    /** Trả về null nếu không đọc được ảnh. */
    public static Result analyze(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);

        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ANALYZE_SIZE) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) return null;

        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        bitmap.recycle();
        if (w < 3 || h < 3) return null;

        // Chuyển sang ảnh xám
        double[] gray = new double[w * h];
        double sum = 0;
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            gray[i] = 0.299 * ((c >> 16) & 0xFF) + 0.587 * ((c >> 8) & 0xFF) + 0.114 * (c & 0xFF);
            sum += gray[i];
        }

        // Laplacian 4 lân cận: L = 4*I(x,y) - I(x-1,y) - I(x+1,y) - I(x,y-1) - I(x,y+1)
        double lapSum = 0;
        double lapSquareSum = 0;
        int count = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                double lap = 4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - w] - gray[i + w];
                lapSum += lap;
                lapSquareSum += lap * lap;
                count++;
            }
        }
        double mean = lapSum / count;
        double variance = lapSquareSum / count - mean * mean;
        return new Result(variance, sum / gray.length);
    }
}
