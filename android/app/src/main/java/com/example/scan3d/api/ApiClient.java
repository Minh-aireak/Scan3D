package com.example.scan3d.api;

import android.os.Handler;
import android.os.Looper;

import com.example.scan3d.model.Job;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Gọi API của server xử lý 3D. Mọi callback đều chạy trên luồng giao diện (main thread).
 *
 * API:
 *   GET  /api/health                     kiểm tra server
 *   POST /api/jobs                       gửi ảnh, tạo công việc quét mới
 *   GET  /api/jobs                       danh sách công việc
 *   GET  /api/jobs/{id}                  trạng thái một công việc
 *   GET  /api/jobs/{id}/files/{name}     tải file kết quả (model.glb, model.obj, ...)
 *   GET  /api/jobs/{id}/viewer           trang xem mô hình 3D
 */
public class ApiClient {

    public interface ResultCallback<T> {
        void onSuccess(T result);

        void onError(String message);
    }

    public interface ProgressListener {
        void onProgress(int percent);
    }

    private interface Parser<T> {
        T parse(String body) throws JSONException;
    }

    private static final MediaType JPEG = MediaType.get("image/jpeg");

    // Dùng chung một OkHttpClient cho cả ứng dụng. Upload nhiều ảnh có thể lâu nên tăng thời gian chờ ghi.
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.MINUTES)
            .readTimeout(2, TimeUnit.MINUTES)
            .build();

    private final String baseUrl;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public ApiClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String jobFileUrl(String jobId, String fileName) {
        return baseUrl + "/api/jobs/" + jobId + "/files/" + fileName;
    }

    public String viewerUrl(String jobId) {
        return baseUrl + "/api/jobs/" + jobId + "/viewer";
    }

    public void checkHealth(ResultCallback<JSONObject> callback) {
        Request request = new Request.Builder().url(baseUrl + "/api/health").build();
        call(request, JSONObject::new, callback);
    }

    public void listJobs(ResultCallback<List<Job>> callback) {
        Request request = new Request.Builder().url(baseUrl + "/api/jobs").build();
        call(request, body -> {
            JSONArray array = new JSONObject(body).getJSONArray("jobs");
            List<Job> jobs = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                jobs.add(Job.fromJson(array.getJSONObject(i)));
            }
            return jobs;
        }, callback);
    }

    public void getJob(String jobId, ResultCallback<Job> callback) {
        Request request = new Request.Builder().url(baseUrl + "/api/jobs/" + jobId).build();
        call(request, body -> Job.fromJson(new JSONObject(body)), callback);
    }

    /** Gửi toàn bộ ảnh đã chụp lên server trong một request multipart. */
    public void createJob(String name, List<File> images, ProgressListener progress,
                          ResultCallback<Job> callback) {
        MultipartBody.Builder form = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("name", name);
        for (File image : images) {
            form.addFormDataPart("images", image.getName(), RequestBody.create(image, JPEG));
        }
        RequestBody body = new CountingRequestBody(form.build(),
                percent -> mainHandler.post(() -> progress.onProgress(percent)));
        Request request = new Request.Builder().url(baseUrl + "/api/jobs").post(body).build();
        call(request, text -> Job.fromJson(new JSONObject(text)), callback);
    }

    /** Tải file từ server và lưu vào dest. */
    public void download(String url, File dest, ResultCallback<File> callback) {
        Request request = new Request.Builder().url(url).build();
        HTTP.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                postError(callback, "Không kết nối được server: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    ResponseBody body = r.body();
                    if (!r.isSuccessful() || body == null) {
                        postError(callback, "Tải file thất bại (mã " + r.code() + ")");
                        return;
                    }
                    File parent = dest.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        postError(callback, "Không tạo được thư mục lưu file");
                        return;
                    }
                    try (InputStream in = body.byteStream(); OutputStream out = new FileOutputStream(dest)) {
                        byte[] buffer = new byte[8192];
                        int n;
                        while ((n = in.read(buffer)) != -1) {
                            out.write(buffer, 0, n);
                        }
                    }
                    mainHandler.post(() -> callback.onSuccess(dest));
                } catch (IOException e) {
                    postError(callback, "Lỗi khi lưu file: " + e.getMessage());
                }
            }
        });
    }

    private <T> void call(Request request, Parser<T> parser, ResultCallback<T> callback) {
        HTTP.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                postError(callback, "Không kết nối được server: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    ResponseBody body = r.body();
                    String text = body != null ? body.string() : "";
                    if (!r.isSuccessful()) {
                        postError(callback, errorMessage(r.code(), text));
                        return;
                    }
                    T result = parser.parse(text);
                    mainHandler.post(() -> callback.onSuccess(result));
                } catch (IOException | JSONException e) {
                    postError(callback, "Dữ liệu trả về không hợp lệ: " + e.getMessage());
                }
            }
        });
    }

    /** FastAPI trả lỗi dạng {"detail": "..."}; lấy nội dung đó để hiện cho người dùng. */
    private static String errorMessage(int code, String body) {
        try {
            Object detail = new JSONObject(body).opt("detail");
            if (detail instanceof String) return (String) detail;
        } catch (JSONException ignored) {
            // nội dung không phải JSON
        }
        return "Server báo lỗi (mã " + code + ")";
    }

    private <T> void postError(ResultCallback<T> callback, String message) {
        mainHandler.post(() -> callback.onError(message));
    }
}
