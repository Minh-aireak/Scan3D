"""Cấu hình server. Mọi giá trị đều có thể ghi đè bằng biến môi trường."""
import os
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent

HOST = os.environ.get("SCAN3D_HOST", "0.0.0.0")
PORT = int(os.environ.get("SCAN3D_PORT", "8000"))

# Thư mục lưu ảnh và kết quả. Nên dùng đường dẫn không dấu, không khoảng trắng để COLMAP chạy ổn định.
DATA_DIR = Path(os.environ.get("SCAN3D_DATA_DIR", BASE_DIR / "data"))
STATIC_DIR = BASE_DIR / "static"

# Lệnh gọi COLMAP: "colmap" nếu đã có trong PATH, hoặc đường dẫn tới COLMAP.bat (bản Windows có CUDA)
COLMAP_BIN = os.environ.get("COLMAP_BIN", "colmap")

# Mô hình AI tách nền có sẵn của rembg, tự tải về lần đầu sử dụng.
# Gợi ý: isnet-general-use (cân bằng), u2net (nhẹ), birefnet-general (chính xác nhưng chậm, tốn RAM)
REMBG_MODEL = os.environ.get("SCAN3D_REMBG_MODEL", "isnet-general-use")

# Dùng mặt nạ AI ngay từ bước SfM. Nên bật (=1) khi vật đặt trên bàn xoay và camera đứng yên,
# vì khi đó nền không đổi giữa các ảnh sẽ làm sai vị trí camera.
USE_MASK_FOR_SFM = os.environ.get("SCAN3D_MASK_SFM", "0") == "1"

MIN_IMAGES = 10               # số ảnh tối thiểu mỗi lần quét (khớp với ứng dụng Android)
MAX_IMAGES = 200
MIN_REGISTERED = 8            # số ảnh tối thiểu COLMAP phải định vị được
MAX_IMAGE_SIZE = 1600         # cạnh dài của ảnh dùng cho SfM (px)
DENSE_MAX_IMAGE_SIZE = 1200   # cạnh dài của ảnh dùng cho PatchMatch; giảm nếu GPU thiếu VRAM
POISSON_DEPTH = 10            # độ chi tiết lưới Poisson (COLMAP mặc định 13 rất nặng)
TARGET_FACES = 150_000        # số tam giác tối đa của mô hình xuất ra, để điện thoại hiển thị mượt
