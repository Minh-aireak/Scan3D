package com.example.scan3d.util;

import android.content.Context;
import android.content.SharedPreferences;

import okhttp3.HttpUrl;

/** Lưu cấu hình của ứng dụng (địa chỉ server xử lý 3D). */
public final class AppPrefs {
    public static final String DEFAULT_SERVER_URL = "http://192.168.1.10:8000";

    private static final String PREFS_NAME = "scan3d_prefs";
    private static final String KEY_SERVER_URL = "server_url";

    private AppPrefs() {
    }

    public static String getServerUrl(Context context) {
        return prefs(context).getString(KEY_SERVER_URL, DEFAULT_SERVER_URL);
    }

    public static void setServerUrl(Context context, String url) {
        prefs(context).edit().putString(KEY_SERVER_URL, url).apply();
    }

    /**
     * Chuẩn hóa địa chỉ người dùng nhập: thêm http:// nếu thiếu, bỏ dấu / ở cuối.
     * Trả về chuỗi rỗng nếu địa chỉ không hợp lệ.
     */
    public static String normalizeUrl(String input) {
        String url = input.trim();
        if (url.isEmpty()) return "";
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return HttpUrl.parse(url) != null ? url : "";
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
