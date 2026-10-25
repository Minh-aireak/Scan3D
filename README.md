# Scan3D: Ứng dụng di động quét mô hình 3D vật thể bằng Photogrammetry kết hợp AI

Người dùng chụp 30–80 ảnh quanh vật thể bằng điện thoại Android. Server trên laptop dựng các ảnh đó thành mô hình 3D có màu. Ứng dụng hiển thị mô hình để xoay, phóng to và xuất ra file **GLB / OBJ / PLY**.

Toàn bộ AI dùng **mô hình có sẵn (pre-trained)**, không cần huấn luyện.

> **Cài đặt và chạy từng bước, kèm danh sách thư viện:** xem [HUONG_DAN_CHAY_DU_AN.md](HUONG_DAN_CHAY_DU_AN.md).

## Kiến trúc

```
┌──────────── Điện thoại Android (Java) ────────────┐        ┌──────────── Server laptop (Python) ─────────────┐
│ CameraX: xem trước + chụp ảnh                     │        │ FastAPI: nhận ảnh, hàng đợi công việc            │
│ AI ML Kit: nhận diện vật thể, hướng dẫn căn khung │ ảnh    │ COLMAP SfM: SIFT → ghép cặp → vị trí camera      │
│ Laplacian: loại ảnh mờ / tối                      │ ─────► │ AI rembg: tách nền từng ảnh → mặt nạ vật thể     │
│ OkHttp: gửi ảnh, theo dõi tiến trình              │        │ COLMAP MVS: PatchMatch → đám mây điểm dày        │
│ WebView + model-viewer: xem mô hình 3D            │ ◄───── │ Poisson: dựng lưới bề mặt                        │
│ FileProvider: xuất / chia sẻ GLB, OBJ, PLY        │ file   │ trimesh: làm sạch, giảm lưới, tô màu, xoay đứng  │
└───────────────────────────────────────────────────┘        └──────────────────────────────────────────────────┘
```

## AI và thuật toán sử dụng

| Vị trí | Thành phần | Kỹ thuật | Loại |
|---|---|---|---|
| Điện thoại | Hướng dẫn chụp | Google ML Kit Object Detection (mô hình có sẵn, chạy offline) | AI |
| Điện thoại | Kiểm tra ảnh mờ / tối | Phương sai toán tử Laplacian, độ sáng trung bình | Xử lý ảnh cổ điển |
| Server | Tách nền vật thể | rembg với mô hình `isnet-general-use` (ONNX) | AI |
| Server | Tìm điểm đặc trưng | SIFT | Thị giác máy tính |
| Server | Ghép cặp ảnh | Exhaustive matching + RANSAC (kiểm tra hình học) | Thị giác máy tính |
| Server | Vị trí camera, đám mây điểm thưa | Structure from Motion + Bundle Adjustment | Photogrammetry |
| Server | Đám mây điểm dày | Multi-View Stereo (PatchMatch Stereo, chạy GPU) | Photogrammetry |
| Server | Lọc nền khi hợp nhất điểm | Mặt nạ AI đưa vào Stereo Fusion | AI + Photogrammetry |
| Server | Dựng lưới bề mặt | Poisson Surface Reconstruction | Hình học |
| Server | Hậu xử lý | Lọc theo khoảng cách (KD-tree), giảm lưới Quadric Decimation, PCA để xoay đứng | Hình học |

## Cấu trúc thư mục

```
Scan3D/
├── android/                         Ứng dụng Android (Java, mở bằng Android Studio)
│   └── app/src/main/java/com/example/scan3d/
│       ├── MainActivity.java        Nhập địa chỉ server, kiểm tra kết nối
│       ├── CaptureActivity.java     Chụp ảnh + AI hướng dẫn + kiểm tra độ nét + gửi ảnh
│       ├── JobListActivity.java     Danh sách mô hình đã tạo
│       ├── JobDetailActivity.java   Theo dõi tiến trình, xem 3D, xuất file
│       ├── api/                     Gọi API server (OkHttp), đếm tiến trình upload
│       ├── model/Job.java           Dữ liệu một lần quét
│       └── util/                    ML Kit analyzer, kiểm tra chất lượng ảnh, cấu hình
└── server/                          Server xử lý (Python)
    ├── main.py                      API FastAPI + trang xem mô hình 3D
    ├── jobs.py                      Lưu trạng thái công việc, hàng đợi xử lý
    ├── pipeline.py                  Quy trình Photogrammetry (gọi COLMAP)
    ├── segmentation.py              AI tách nền (rembg)
    ├── mesh_post.py                 Hậu xử lý lưới và xuất GLB/OBJ/PLY
    ├── config.py                    Cấu hình (đường dẫn COLMAP, kích thước ảnh, ...)
    ├── requirements.txt
    └── run_server.bat               Khởi động server trên Windows
```

## Cài đặt server (Windows, laptop có GPU NVIDIA)

Yêu cầu: GPU NVIDIA có CUDA (từ 4 GB VRAM, nên có 6 GB), RAM từ 16 GB, Python 3.11. Nên đặt project ở **đường dẫn không dấu, không khoảng trắng**.

1. **Cài COLMAP 4.2.1**
   - Tải bản `colmap-x64-windows-cuda.zip`: https://github.com/colmap/colmap/releases/download/4.2.1/colmap-x64-windows-cuda.zip
   - Giải nén vào `C:\colmap` sao cho có file `C:\colmap\COLMAP.bat`.
   - Nếu để ở chỗ khác, sửa dòng `COLMAP_BIN` trong `run_server.bat`.

2. **Cài thư viện Python**
   ```bat
   cd Scan3D\server
   py -3.11 -m venv .venv
   .venv\Scripts\activate
   pip install -r requirements.txt
   ```

3. **Tải trước mô hình AI tách nền** (không bắt buộc; nếu bỏ qua, lần xử lý đầu tiên sẽ tự tải khoảng 179 MB):
   ```bat
   python -c "from rembg import new_session; new_session('isnet-general-use')"
   ```

4. **Mở cổng 8000 trên tường lửa** để điện thoại kết nối được (chạy CMD bằng quyền Administrator):
   ```bat
   netsh advfirewall firewall add rule name="Scan3D" dir=in action=allow protocol=TCP localport=8000 remoteip=localsubnet
   ```

5. **Khởi động server**: chạy `run_server.bat`. Mở http://localhost:8000/docs để xem và thử API.

6. **Lấy địa chỉ IP của laptop**: chạy `ipconfig` và xem dòng `IPv4 Address` của card Wi-Fi, ví dụ `192.168.1.10`.

7. (Tùy chọn) **Xem 3D không cần Internet**: tải file
   https://cdn.jsdelivr.net/npm/@google/model-viewer@3.5.0/dist/model-viewer.min.js
   và đặt vào `server/static/`. Nếu không có file này, trang xem 3D sẽ tải thư viện từ CDN, nên điện thoại cần có Internet.

### Cấu hình bằng biến môi trường

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `COLMAP_BIN` | `colmap` | Đường dẫn tới COLMAP |
| `SCAN3D_PORT` | `8000` | Cổng server |
| `SCAN3D_DATA_DIR` | `server/data` | Nơi lưu ảnh và kết quả |
| `SCAN3D_REMBG_MODEL` | `isnet-general-use` | Mô hình AI tách nền (`u2net` nhẹ hơn, `birefnet-general` chính xác hơn nhưng chậm) |
| `SCAN3D_MASK_SFM` | `0` | Đặt `1` khi quét bằng **bàn xoay, camera đứng yên** |

Các thông số khác (kích thước ảnh, độ chi tiết Poisson, số tam giác tối đa) nằm trong `config.py`. Nếu GPU báo thiếu bộ nhớ ở bước PatchMatch, hãy giảm `DENSE_MAX_IMAGE_SIZE` (ví dụ 1000).

## Chạy ứng dụng Android

1. Mở thư mục `Scan3D/android` bằng Android Studio, chờ Gradle Sync xong.
2. Cắm điện thoại (Android 7.0 trở lên, đã bật USB debugging) và bấm **Run**.
3. Điện thoại và laptop phải **cùng mạng Wi-Fi**.
4. Trong ứng dụng, nhập `http://<IP laptop>:8000` rồi bấm **Kiểm tra kết nối**. Phải thấy `COLMAP: sẵn sàng`.

## Cách sử dụng

1. Bấm **Bắt đầu quét**. Khung hướng dẫn phía trên chuyển sang màu xanh khi AI thấy vật thể nằm giữa khung với kích thước vừa phải.
2. Đi vòng quanh vật thể và chụp, mỗi ảnh lệch khoảng 10–15°. Nên chụp 2 vòng: ngang tầm vật thể và chéo từ trên xuống. Tổng cộng 30–80 ảnh.
3. Ảnh bị mờ hoặc quá tối sẽ được cảnh báo và gợi ý chụp lại.
4. Bấm **Gửi** và đặt tên cho vật thể. Màn hình chi tiết hiển thị từng bước xử lý trên server.
5. Khi xong: xoay, phóng to mô hình 3D; bấm **Xem ảnh AI tách nền** để xem kết quả AI; bấm **Xuất GLB / OBJ / PLY** để lưu hoặc chia sẻ file.

**Vật thể phù hợp:** có hoa văn, nhiều chi tiết, bề mặt mờ (đồ gốm, giày, tượng, đồ chơi, chai lọ có nhãn).
**Vật thể khó quét:** bóng loáng, trong suốt, trơn một màu, hoặc quá mỏng.

## Thời gian xử lý ước tính (GPU 6 GB, 50 ảnh)

| Bước | Thời gian |
|---|---|
| Chuẩn hóa ảnh + SfM (SIFT, ghép cặp, mapper) | 2–6 phút |
| AI tách nền (CPU) | 1–2 phút |
| PatchMatch Stereo (GPU) | 8–20 phút |
| Hợp nhất điểm + Poisson + hậu xử lý | 1–3 phút |

Đây là ước tính; thời gian thực tế phụ thuộc số ảnh, kích thước ảnh và nhiệt độ máy. Laptop nên cắm sạc và bật chế độ hiệu năng cao.

## API

| Phương thức | Đường dẫn | Mô tả |
|---|---|---|
| GET | `/api/health` | Kiểm tra server, COLMAP và mô hình AI |
| POST | `/api/jobs` | Gửi ảnh (multipart: `name`, nhiều `images`), tạo công việc mới |
| GET | `/api/jobs` | Danh sách công việc |
| GET | `/api/jobs/{id}` | Trạng thái, bước hiện tại, phần trăm, thống kê |
| GET | `/api/jobs/{id}/files/{name}` | Tải `model.glb`, `model.obj`, `model.ply`, `masks_preview.jpg`, `log.txt` |
| GET | `/api/jobs/{id}/viewer` | Trang xem mô hình 3D (model-viewer) |

## Xử lý sự cố

| Hiện tượng | Cách khắc phục |
|---|---|
| Ứng dụng không kết nối được server | Kiểm tra cùng Wi-Fi, đúng IP, đã mở cổng 8000 trên tường lửa |
| `COLMAP: CHƯA cài hoặc sai đường dẫn` | Sửa `COLMAP_BIN` trong `run_server.bat` |
| "Không xác định được vị trí camera" | Chụp dày hơn, ảnh trùng nhau nhiều hơn, vật thể cần có chi tiết |
| Lỗi ở bước `patch_match_stereo` | Cần bản COLMAP có CUDA; nếu thiếu VRAM, giảm `DENSE_MAX_IMAGE_SIZE` |
| Mô hình dính cả mặt bàn | Đặt vật lên nền trơn khác màu vật thể để AI tách nền dễ hơn |
| Mô hình bị nghiêng | Chụp đủ một vòng quanh vật thể để thuật toán xác định được hướng thẳng đứng |

Khi có lỗi, bấm **Xem log xử lý** trong ứng dụng để xem toàn bộ output của COLMAP.

## Hướng phát triển

- Dán texture từ ảnh lên mô hình bằng OpenMVS (`TextureMesh`) để mô hình nét hơn màu đỉnh.
- Thêm chế độ "tạo nhanh từ 1 ảnh" bằng mô hình AI tạo sinh 3D có sẵn (TripoSR).
- Dùng ARCore để ghi lại vị trí điện thoại khi chụp, giúp bước SfM nhanh và ổn định hơn.
- Ước lượng kích thước thật của vật thể bằng một vật mẫu đã biết kích thước (thẻ ATM, tờ A4).
