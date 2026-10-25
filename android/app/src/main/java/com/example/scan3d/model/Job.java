package com.example.scan3d.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Một lần quét 3D trên server (ảnh đã gửi lên và trạng thái xử lý). */
public class Job {
    public static final String QUEUED = "queued";
    public static final String PROCESSING = "processing";
    public static final String DONE = "done";
    public static final String FAILED = "failed";

    public String id;
    public String name;
    public String status;
    public String step;
    public int progress;
    public String message;
    public String createdAt;
    public int numImages;
    public int numRegistered;
    public long numPoints;
    public long faces;
    public double durationSec;
    public final List<String> outputs = new ArrayList<>();

    public static Job fromJson(JSONObject o) {
        Job job = new Job();
        job.id = text(o, "id");
        job.name = text(o, "name");
        job.status = text(o, "status");
        job.step = text(o, "step");
        job.progress = o.optInt("progress");
        job.message = text(o, "message");
        job.createdAt = text(o, "created_at");
        job.numImages = o.optInt("num_images");

        JSONObject stats = o.optJSONObject("stats");
        if (stats != null) {
            job.numRegistered = stats.optInt("num_registered");
            job.numPoints = stats.optLong("num_points");
            job.faces = stats.optLong("faces");
            job.durationSec = stats.optDouble("duration_sec", 0);
        }
        JSONArray files = o.optJSONArray("outputs");
        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                job.outputs.add(files.optString(i));
            }
        }
        return job;
    }

    /** optString trả về "null" khi giá trị JSON là null, nên đọc qua hàm này. */
    private static String text(JSONObject o, String key) {
        return o.isNull(key) ? "" : o.optString(key, "");
    }

    public boolean isRunning() {
        return QUEUED.equals(status) || PROCESSING.equals(status);
    }

    public String statusText() {
        switch (status) {
            case QUEUED:
                return "Đang chờ xử lý";
            case PROCESSING:
                return "Đang xử lý";
            case DONE:
                return "Hoàn tất";
            case FAILED:
                return "Thất bại";
            default:
                return status;
        }
    }

    public String summary() {
        StringBuilder sb = new StringBuilder("Số ảnh: " + numImages);
        if (numRegistered > 0) {
            sb.append(" (định vị được ").append(numRegistered).append(")");
        }
        if (faces > 0) {
            sb.append(String.format(Locale.US, "\nĐám mây điểm: %,d điểm · Lưới: %,d tam giác", numPoints, faces));
        }
        if (durationSec > 0) {
            sb.append(String.format(Locale.US, "\nThời gian xử lý: %d phút %d giây",
                    (int) durationSec / 60, (int) durationSec % 60));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        String state = PROCESSING.equals(status) ? statusText() + " " + progress + "%" : statusText();
        return name + "\n" + state + " · " + createdAt.replace('T', ' ');
    }
}
