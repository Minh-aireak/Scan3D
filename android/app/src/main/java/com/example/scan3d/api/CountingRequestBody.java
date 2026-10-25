package com.example.scan3d.api;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.Buffer;
import okio.BufferedSink;
import okio.ForwardingSink;
import okio.Okio;

/** Bọc RequestBody để đếm số byte đã gửi, phục vụ thanh tiến trình upload ảnh. */
class CountingRequestBody extends RequestBody {
    private final RequestBody delegate;
    private final ApiClient.ProgressListener listener;

    CountingRequestBody(RequestBody delegate, ApiClient.ProgressListener listener) {
        this.delegate = delegate;
        this.listener = listener;
    }

    @Override
    public MediaType contentType() {
        return delegate.contentType();
    }

    @Override
    public long contentLength() throws IOException {
        return delegate.contentLength();
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
        final long total = contentLength();
        BufferedSink counting = Okio.buffer(new ForwardingSink(sink) {
            private long written = 0;
            private int lastPercent = -1;

            @Override
            public void write(Buffer source, long byteCount) throws IOException {
                super.write(source, byteCount);
                written += byteCount;
                int percent = total > 0 ? (int) (written * 100 / total) : 0;
                // Chỉ báo khi phần trăm thay đổi để không gọi giao diện quá nhiều lần
                if (percent != lastPercent) {
                    lastPercent = percent;
                    listener.onProgress(percent);
                }
            }
        });
        delegate.writeTo(counting);
        counting.flush();
    }
}
