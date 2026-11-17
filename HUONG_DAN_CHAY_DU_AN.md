# Hướng dẫn cài đặt và chạy dự án Scan3D

Dự án gồm 2 phần chạy song song:

```
 Điện thoại Android  ── gửi ảnh qua Wi-Fi ──►  Máy tính (server)
 (app Scan3D)        ◄── trả mô hình 3D ─────  Python + COLMAP + AI, chạy trên GPU NVIDIA
```

- **Server** chạy trên máy tính **Windows hoặc Linux** có GPU NVIDIA: nhận ảnh, dựng mô hình 3D.
- **App Android** dùng để chụp ảnh, gửi lên server, xem và xuất mô hình 3D.
- Điện thoại và máy tính **phải cùng một mạng** (cùng Wi-Fi hoặc cùng router).

> **Lưu ý:** app Android đã được build thử thành công (bản debug và release, lint không báo lỗi). Server đã được kiểm tra cú pháp và đối chiếu với COLMAP 4.2.1 cùng các thư viện trong `requirements.txt`, nhưng toàn bộ quy trình quét **chưa chạy thử với dữ liệu thật**. Nếu gặp lỗi, hãy chụp màn hình hoặc copy nội dung lỗi (kèm file `log.txt`, xem mục 6) để sửa.

---

## 1. Yêu cầu máy tính

| Thành phần | Tối thiểu | Khuyến nghị |
|---|---|---|
| Hệ điều hành | Windows 10/11 64-bit hoặc Linux 64-bit | Windows 11 hoặc Ubuntu 24.04 |
| GPU | NVIDIA có CUDA, 4 GB VRAM | NVIDIA 6 GB VRAM trở lên (RTX 3060 / 4060 / 4050...) |
| Driver NVIDIA | Bản mới (Windows: hỗ trợ CUDA 12; Linux: hỗ trợ CUDA 12.9) | Bản mới nhất |
| RAM | 16 GB | 32 GB |
| Ổ cứng trống | 10 GB | 20 GB |
| Mạng | Cùng mạng với điện thoại | |

Điện thoại: Android 7.0 trở lên, có camera sau.

**Đặt project ở đường dẫn không dấu, không khoảng trắng**, ví dụ `D:\Scan3D` (Linux: `~/Scan3D`). COLMAP hay lỗi với đường dẫn có dấu tiếng Việt.

---

## 2. Danh sách phần mềm và thư viện cần có

### 2.1. Phần mềm cài trên máy tính

| Phần mềm | Phiên bản | Dùng để | Tải ở đâu |
|---|---|---|---|
| Python | Windows: **3.11** (bắt buộc). Linux: 3.11, 3.12 hoặc 3.13 | Chạy server | Windows: https://www.python.org/ftp/python/3.11.9/python-3.11.9-amd64.exe. Linux: có sẵn (bước 3B.4) |
| COLMAP | **4.2.1 bản CUDA** | Photogrammetry: dựng 3D từ ảnh | Windows: https://github.com/colmap/colmap/releases/download/4.2.1/colmap-x64-windows-cuda.zip. Linux: cài qua Miniforge (bước 3B.3) |
| Miniforge (chỉ Linux) | Mới nhất | Cài COLMAP bản CUDA trên Linux | https://github.com/conda-forge/miniforge |
| Driver NVIDIA | Mới nhất | Cho COLMAP dùng GPU | https://www.nvidia.com/Download/index.aspx |
| Android Studio | Bản mới nhất (hỗ trợ AGP 9.3) | Build app Android | https://developer.android.com/studio |

Không cần cài riêng CUDA Toolkit, JDK hay Gradle: COLMAP đã kèm thư viện CUDA (bản Windows nằm trong file zip, bản Linux được conda tải kèm), còn Android Studio tự tải JDK và Gradle.

### 2.2. Thư viện Python (cài tự động bằng `requirements.txt`)

| Thư viện | Phiên bản | Vai trò |
|---|---|---|
| fastapi | 0.162.0 | Tạo API cho app gọi |
| uvicorn[standard] | 0.54.0 | Chạy web server |
| python-multipart | 0.0.32 | Nhận file ảnh upload |
| pillow | 13.0.0 | Đọc, xoay, thu nhỏ ảnh |
| numpy, scipy | 2.4.6, 1.17.1 | Tính toán ma trận, tìm điểm lân cận (KD-tree) |
| trimesh | 5.2.0 | Xử lý lưới 3D, xuất GLB/OBJ/PLY |
| fast-simplification | 0.2.0 | Giảm số tam giác của lưới |
| rembg[cpu] | 2.0.86 | AI tách nền vật thể |
| onnxruntime | 1.31.0 | Chạy mô hình AI của rembg |

### 2.3. Thư viện Android (Gradle tự tải khi mở project)

| Thư viện | Phiên bản | Vai trò |
|---|---|---|
| CameraX (core, camera2, lifecycle, view) | 1.4.2 | Xem trước và chụp ảnh |
| ML Kit Object Detection | 17.0.2 | AI nhận diện vật thể ngay trên điện thoại |
| OkHttp | 4.12.0 | Gửi ảnh, tải file mô hình |
| AppCompat, Material, Activity | 1.8.0, 1.14.0, 1.13.0 | Giao diện |

### 2.4. Mô hình AI có sẵn (không cần huấn luyện)

| Mô hình | Chạy ở | Kích thước | Cách có |
|---|---|---|---|
| `isnet-general-use` (rembg) | Server | khoảng 179 MB | Tự tải lần đầu, hoặc tải trước ở bước 3.5 (Linux: 3B.5) |
| ML Kit Object Detection | Điện thoại | Nằm sẵn trong app | Đi kèm thư viện, không cần tải |

---

## 3. Cài đặt server trên Windows

Dùng Linux thì bỏ qua mục này, làm theo **mục 3B** bên dưới.

Mở **Command Prompt (CMD)** để chạy các lệnh dưới đây.

### Bước 3.1. Chép project

Giải nén hoặc chép thư mục `Scan3D` vào `D:\Scan3D`. Trong thư mục phải có `server\` và `android\`.

### Bước 3.2. Cài Python 3.11

1. Tải và chạy `python-3.11.9-amd64.exe` (link ở mục 2.1).
2. Ở màn hình đầu tiên, tích **"Add python.exe to PATH"**, rồi bấm **Install Now**.
3. Kiểm tra:
   ```bat
   py -3.11 --version
   ```
   Kết quả phải là `Python 3.11.9`.

> Nếu máy đã có **Python Install Manager** (lệnh `py install` dùng được), có thể cài nhanh bằng lệnh `py install 3.11`.

### Bước 3.3. Cài COLMAP

1. Kiểm tra GPU và driver:
   ```bat
   nvidia-smi
   ```
   Lệnh phải hiện tên GPU NVIDIA. Nếu báo lỗi, cài driver NVIDIA trước.
2. Tải `colmap-x64-windows-cuda.zip` (link ở mục 2.1). **Không** tải bản `nocuda`.
3. Giải nén vào `C:\colmap` sao cho có file **`C:\colmap\COLMAP.bat`** và thư mục `C:\colmap\bin`. Nếu giải nén ra thêm một thư mục con, hãy chuyển nội dung bên trong ra `C:\colmap`.
4. Kiểm tra:
   ```bat
   C:\colmap\COLMAP.bat help
   ```
   Lệnh phải in ra danh sách lệnh của COLMAP và dòng có chữ `with CUDA`.

Nếu muốn đặt COLMAP ở chỗ khác, sửa dòng `set COLMAP_BIN=...` trong file `server\run_server.bat`.

### Bước 3.4. Tạo môi trường Python và cài thư viện

```bat
cd /d D:\Scan3D\server
py -3.11 -m venv .venv
.venv\Scripts\activate
python -m pip install --upgrade pip
pip install -r requirements.txt
```

Quá trình cài mất khoảng 3–10 phút và tải khoảng 500 MB. Sau khi xong, dòng cuối phải có chữ `Successfully installed ...`.

### Bước 3.5. Tải trước mô hình AI (nên làm)

Vẫn trong cửa sổ CMD đã `activate` ở bước 3.4:

```bat
python -c "from rembg import new_session; new_session('isnet-general-use'); print('OK')"
```

Mô hình được lưu vào `C:\Users\<tên>\.rembg\models\`. Nếu bỏ qua bước này, lần quét đầu tiên sẽ tự tải.

### Bước 3.6. Mở cổng 8000 trên tường lửa

Mở CMD bằng quyền Administrator (chuột phải vào CMD, chọn **Run as administrator**) rồi chạy:

```bat
netsh advfirewall firewall add rule name="Scan3D" dir=in action=allow protocol=TCP localport=8000 remoteip=localsubnet
```

`remoteip=localsubnet` nghĩa là chỉ thiết bị trong cùng mạng mới kết nối được, người ngoài Internet thì không.

### Bước 3.7. Chỉnh cấu hình theo GPU (nếu cần)

Mở `server\config.py` và sửa `DENSE_MAX_IMAGE_SIZE` theo VRAM của GPU:

| VRAM | `DENSE_MAX_IMAGE_SIZE` |
|---|---|
| 4 GB | `1000` |
| 6 GB | `1200` (mặc định) |
| 8 GB trở lên | `1600` (mô hình chi tiết hơn) |

### Bước 3.8. Chạy server

Nhấp đúp file `server\run_server.bat`. Khi thấy dòng sau là server đã chạy:

```
Uvicorn running on http://0.0.0.0:8000
```

Kiểm tra bằng trình duyệt trên chính máy đó:
- http://localhost:8000/api/health phải trả về `"status":"ok"` và `"colmap":true`.
- http://localhost:8000/docs là trang tài liệu API, có thể bấm thử.

**Để cửa sổ server mở trong suốt lúc dùng app.** Đóng cửa sổ là server dừng.

### Bước 3.9. Lấy địa chỉ IP của máy tính

```bat
ipconfig
```

Xem dòng **IPv4 Address** của card mạng đang dùng (Wi-Fi hoặc Ethernet), ví dụ `192.168.1.25`. Địa chỉ server sẽ là `http://192.168.1.25:8000`.

---

## 3B. Cài đặt server trên Linux

Các lệnh dưới đây soạn cho **Ubuntu 22.04 / 24.04** (Debian, Linux Mint làm tương tự). Mở **Terminal** để chạy. Không cần cài CUDA Toolkit.

### Bước 3B.1. Chép project

Chép thư mục `Scan3D` vào thư mục nhà, ví dụ `~/Scan3D` (hoặc `git clone` về đó). Trong thư mục phải có `server/` và `android/`.

### Bước 3B.2. Kiểm tra GPU và driver NVIDIA

```bash
nvidia-smi
```

Lệnh phải hiện tên GPU và dòng `CUDA Version` từ **12.9** trở lên. Nếu lệnh báo lỗi hoặc số nhỏ hơn, cài driver mới rồi khởi động lại máy (trên Ubuntu có thể chọn driver trong **Software & Updates → Additional Drivers**, hoặc chạy):

```bash
sudo ubuntu-drivers install
sudo reboot
```

### Bước 3B.3. Cài COLMAP 4.2.1 bản CUDA

COLMAP không phát hành bản cài sẵn cho Linux. Cách đơn giản nhất là cài từ kho conda-forge bằng Miniforge: không cần quyền root, không phải tự biên dịch, thư viện CUDA được tải kèm.

```bash
cd ~
wget "https://github.com/conda-forge/miniforge/releases/latest/download/Miniforge3-Linux-$(uname -m).sh"
bash "Miniforge3-Linux-$(uname -m).sh" -b -p ~/miniforge3
~/miniforge3/bin/conda create -y -n colmap -c conda-forge "colmap=4.2.1=cuda_129*"
```

Kiểm tra:

```bash
~/miniforge3/envs/colmap/bin/colmap help
```

Lệnh phải in ra danh sách lệnh của COLMAP và dòng có chữ `with CUDA`.

- `cuda_129*` là bản dùng CUDA 12.9, chạy được trên GPU NVIDIA từ dòng GTX 900 trở lên.
- Nếu `conda create` báo lỗi có chữ `__cuda`: driver NVIDIA quá cũ, làm lại bước 3B.2.
- **Không** dùng `sudo apt install colmap`: bản trong kho của Ubuntu không có CUDA, bước PatchMatch sẽ báo lỗi `Dense stereo reconstruction requires CUDA`.
- Không cần `conda activate`: `run_server.sh` tự tìm COLMAP ở `~/miniforge3/envs/colmap/bin/colmap`.

### Bước 3B.4. Tạo môi trường Python và cài thư viện

Kiểm tra phiên bản Python có sẵn:

```bash
python3 --version
```

- Nếu là **3.11, 3.12 hoặc 3.13** (Ubuntu 24.04 có sẵn 3.12), dùng luôn:
  ```bash
  sudo apt install -y python3-venv
  cd ~/Scan3D/server
  python3 -m venv .venv
  ```
- Nếu thấp hơn 3.11 (Ubuntu 22.04 là 3.10), lấy Python 3.11 từ Miniforge đã cài ở bước 3B.3:
  ```bash
  ~/miniforge3/bin/conda create -y -n py311 python=3.11
  cd ~/Scan3D/server
  ~/miniforge3/envs/py311/bin/python -m venv .venv
  ```

Sau đó cài thư viện:

```bash
.venv/bin/python -m pip install --upgrade pip
.venv/bin/pip install -r requirements.txt
```

Quá trình cài mất khoảng 3–10 phút. Sau khi xong, dòng cuối phải có chữ `Successfully installed ...`.

### Bước 3B.5. Tải trước mô hình AI (nên làm)

```bash
.venv/bin/python -c "from rembg import new_session; new_session('isnet-general-use'); print('OK')"
```

Mô hình được lưu vào `~/.rembg/models/`. Nếu bỏ qua bước này, lần quét đầu tiên sẽ tự tải.

### Bước 3B.6. Mở cổng 8000 trên tường lửa

Ubuntu dùng tường lửa `ufw` (mặc định tắt). Kiểm tra:

```bash
sudo ufw status
```

Nếu kết quả là `Status: active` thì mở cổng 8000:

```bash
sudo ufw allow 8000/tcp
```

Fedora dùng `firewalld`: `sudo firewall-cmd --permanent --add-port=8000/tcp && sudo firewall-cmd --reload`.

### Bước 3B.7. Chỉnh cấu hình theo GPU (nếu cần)

Giống bước 3.7: sửa `DENSE_MAX_IMAGE_SIZE` trong `server/config.py` theo VRAM của GPU.

### Bước 3B.8. Chạy server

```bash
cd ~/Scan3D/server
bash run_server.sh
```

Dòng đầu tiên cho biết COLMAP đang dùng, ví dụ `COLMAP_BIN=/home/<tên>/miniforge3/envs/colmap/bin/colmap`. Khi thấy dòng sau là server đã chạy:

```
Uvicorn running on http://0.0.0.0:8000
```

Kiểm tra bằng trình duyệt trên chính máy đó: http://localhost:8000/api/health phải trả về `"status":"ok"` và `"colmap":true`.

Nếu COLMAP cài ở chỗ khác (ví dụ tự biên dịch), chỉ đường dẫn khi chạy:

```bash
COLMAP_BIN=/duong/dan/toi/colmap bash run_server.sh
```

**Để cửa sổ Terminal mở trong suốt lúc dùng app.** Bấm `Ctrl+C` để dừng server.

### Bước 3B.9. Lấy địa chỉ IP của máy

```bash
hostname -I
```

Lấy địa chỉ trong mạng LAN (thường có dạng `192.168.x.x`), ví dụ `192.168.1.25`. Địa chỉ server sẽ là `http://192.168.1.25:8000`.

---

## 4. Build và cài app Android

### Bước 4.1. Mở project

1. Mở Android Studio, chọn **File → Open**, rồi chọn thư mục **`D:\Scan3D\android`** (Linux: `~/Scan3D/android`; không chọn thư mục `Scan3D`). Trên Linux, Android Studio được mở bằng file `android-studio/bin/studio.sh` trong thư mục giải nén.
2. Chờ **Gradle Sync** chạy xong. Lần đầu Android Studio sẽ tự tải Gradle 9.5, JDK và các thư viện ở mục 2.3, nên cần Internet và mất khoảng 10–20 phút.
3. Nếu được nhắc cài **Android SDK Platform 37**, bấm **Install**. Có thể cài thủ công trong **Tools → SDK Manager**.

### Bước 4.2. Cài app lên điện thoại

**Cách 1: cắm cáp USB**
1. Trên điện thoại, vào **Cài đặt → Thông tin điện thoại** và bấm 7 lần vào **Số hiệu bản tạo** để bật Tùy chọn nhà phát triển.
2. Vào **Tùy chọn nhà phát triển** và bật **Gỡ lỗi USB**.
3. Cắm cáp, chọn điện thoại ở thanh trên cùng của Android Studio, rồi bấm nút **Run ▶**.

Trên Linux, nếu Android Studio không thấy điện thoại, cài quy tắc USB cho Android và thêm tài khoản vào nhóm `plugdev`, sau đó đăng xuất, đăng nhập lại và cắm lại cáp:

```bash
sudo apt install -y android-sdk-platform-tools-common
sudo usermod -aG plugdev $USER
```

**Cách 2: tạo file APK để gửi**
1. Chọn **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
2. File APK nằm ở `android\app\build\outputs\apk\debug\app-debug.apk` (Linux: `android/app/build/outputs/apk/debug/app-debug.apk`).
3. Gửi file này qua Zalo hoặc Drive sang điện thoại rồi cài (cho phép cài từ nguồn không xác định).

### Bước 4.3. Kết nối app với server

1. Điện thoại kết nối **cùng mạng** với máy chạy server.
2. Mở app Scan3D, nhập `http://<IP ở bước 3.9 hoặc 3B.9>:8000`, rồi bấm **Kiểm tra kết nối**.
3. Phải hiện `✓ Đã kết nối server` và `COLMAP: sẵn sàng`.

---

## 5. Chạy thử lần đầu

1. **Chọn vật thể dễ quét:** hộp giấy có in hình, chiếc giày, chai có nhãn, đồ chơi nhiều màu. Tránh đồ bóng loáng, trong suốt hoặc trơn một màu.
2. Đặt vật thể trên bàn, nơi đủ sáng và có nền khác màu với vật thể.
3. Bấm **Bắt đầu quét**. Khung hướng dẫn phía trên chuyển màu xanh khi AI thấy vật thể nằm giữa khung.
4. Đi vòng quanh và chụp khoảng **40 ảnh**: 1 vòng ngang tầm vật thể và 1 vòng chéo từ trên xuống, mỗi ảnh lệch khoảng 10°.
5. Bấm **Gửi**, đặt tên, rồi chờ. Màn hình hiển thị từng bước và phần trăm tiến độ.
6. Khi xong, xoay xem mô hình 3D, bấm **Xem ảnh AI tách nền**, và thử **Xuất GLB / OBJ / PLY**.

**Thời gian ước tính** với 40–50 ảnh trên GPU 6 GB:

| Bước | Thời gian |
|---|---|
| Chuẩn hóa ảnh + SfM (SIFT, ghép cặp, mapper) | 2–6 phút |
| AI tách nền (chạy CPU) | 1–2 phút |
| PatchMatch Stereo (GPU) | 8–20 phút |
| Hợp nhất điểm + Poisson + hậu xử lý | 1–3 phút |

GPU mạnh hơn sẽ nhanh hơn. Nếu là laptop, nên cắm sạc và bật chế độ hiệu năng cao.

---

## 6. Xem log khi có lỗi

- **Trên app:** công việc bị lỗi sẽ có nút **Xem log xử lý**.
- **Trên máy server:** mỗi lần quét có một thư mục `server\data\jobs\<mã>\` (Linux: `server/data/jobs/<mã>/`) chứa:
  - `uploads\`: ảnh gốc gửi lên
  - `workspace\`: dữ liệu trung gian của COLMAP (có thể rất nặng)
  - `output\`: `model.glb`, `model.obj`, `model.ply`, `masks_preview.jpg`
  - `log.txt`: toàn bộ output của COLMAP (**gửi file này khi báo lỗi**)
  - `job.json`: trạng thái công việc
- Cửa sổ `run_server.bat` (Linux: Terminal đang chạy `run_server.sh`) cũng in lỗi Python, nếu có.

Muốn giải phóng ổ cứng thì xóa các thư mục trong `server\data\jobs\`.

---

## 7. Lỗi thường gặp

| Hiện tượng | Nguyên nhân | Cách sửa |
|---|---|---|
| `pip install` báo lỗi không tìm thấy phiên bản | Windows: không dùng Python 3.11. Linux: Python thấp hơn 3.11 | Windows: tạo lại venv bằng `py -3.11 -m venv .venv`. Linux: xóa `.venv` rồi làm lại bước 3B.4 |
| `/api/health` báo `"colmap": false` | Sai đường dẫn COLMAP | Windows: kiểm tra có file `C:\colmap\COLMAP.bat`, sửa `COLMAP_BIN` trong `run_server.bat`. Linux: chạy thử `~/miniforge3/envs/colmap/bin/colmap help`; nếu COLMAP ở chỗ khác thì chạy `COLMAP_BIN=/duong/dan/toi/colmap bash run_server.sh` |
| Lỗi ở bước `patch_match_stereo`, log có chữ "CUDA" | Bản COLMAP không có CUDA (Windows: tải nhầm bản `nocuda`; Linux: cài bằng `apt`) hoặc driver cũ | Windows: tải lại bản `windows-cuda`. Linux: cài lại theo bước 3B.3. Cập nhật driver NVIDIA |
| `conda create` báo lỗi có chữ `__cuda` (Linux) | Driver NVIDIA quá cũ | Cập nhật driver theo bước 3B.2 |
| `bash run_server.sh` báo "Chưa có môi trường Python .venv" | Chưa tạo venv | Làm bước 3B.4 |
| Android Studio trên Linux không thấy điện thoại | Thiếu quyền truy cập USB | Làm theo ghi chú Linux ở bước 4.2 |
| Lỗi "out of memory" ở `patch_match_stereo` | GPU thiếu VRAM | Giảm `DENSE_MAX_IMAGE_SIZE` trong `config.py` (bước 3.7) |
| App báo "Không kết nối được server" | Khác mạng, sai IP, hoặc tường lửa chặn | Kiểm tra cùng mạng, chạy lại `ipconfig`, làm lại bước 3.6 |
| Lỗi "Không xác định được vị trí camera" | Ảnh ít trùng nhau hoặc vật thể ít chi tiết | Chụp dày hơn, chọn vật thể nhiều hoa văn |
| Mô hình dính cả mặt bàn | AI không tách được nền | Đặt vật lên nền trơn khác màu với vật thể |
| Màn hình xem 3D trắng trơn | Điện thoại không có Internet để tải thư viện model-viewer | Tải file https://cdn.jsdelivr.net/npm/@google/model-viewer@3.5.0/dist/model-viewer.min.js và bỏ vào `server\static\` |
| Gradle Sync lỗi | Android Studio quá cũ hoặc thiếu SDK 37 | Cập nhật Android Studio, cài SDK Platform 37 |

---

## 8. Khi gửi project cho người khác

Không cần gửi các thư mục sau vì chúng nặng và sẽ tự tạo lại:

- `server\.venv\`
- `server\data\`
- `android\.gradle\`, `android\.idea\`, `android\build\`, `android\app\build\`
- `android\local.properties`

Môi trường `server\.venv\` tạo trên Windows không dùng được trên Linux (và ngược lại), nên khi chuyển máy phải tạo lại theo bước 3.4 hoặc 3B.4.

Xem thêm `README.md` để hiểu kiến trúc, thuật toán và danh sách API của dự án.
