"""AI tách nền vật thể bằng mô hình có sẵn của thư viện rembg (không cần huấn luyện).

Mặt nạ được dùng để COLMAP chỉ hợp nhất các điểm 3D thuộc vật thể, bỏ qua bàn, tường, sàn...
"""
import threading

import numpy as np
from PIL import Image, ImageFilter

import config

IMAGE_EXTS = {".jpg", ".jpeg", ".png"}
MASK_DILATE = 9        # nới rộng mặt nạ vài pixel để không cắt mất mép vật thể (phải là số lẻ)
PREVIEW_COUNT = 6
PREVIEW_WIDTH = 320

_session = None
_session_lock = threading.Lock()


def _get_session():
    """Nạp mô hình AI một lần rồi dùng lại. Lần đầu rembg sẽ tự tải mô hình về thư mục ~/.rembg/models."""
    global _session
    with _session_lock:
        if _session is None:
            from rembg import new_session  # nạp chậm để server khởi động nhanh
            _session = new_session(config.REMBG_MODEL)
        return _session


def predict_mask(image):
    """Trả về mặt nạ đen trắng: trắng (255) là vật thể, đen (0) là nền."""
    from rembg import remove

    mask = remove(image, session=_get_session(), only_mask=True).convert("L")
    if mask.size != image.size:
        mask = mask.resize(image.size, Image.Resampling.BILINEAR)
    mask = mask.point(lambda v: 255 if v >= 128 else 0)
    return mask.filter(ImageFilter.MaxFilter(MASK_DILATE))


def segment_folder(images_dir, masks_dir, preview_path=None, progress=None):
    """Tạo mặt nạ cho mọi ảnh theo quy ước của COLMAP: ảnh 'a.jpg' -> mặt nạ 'a.jpg.png'.

    Trả về danh sách tỉ lệ diện tích vật thể trên từng ảnh (để phát hiện AI tách nền thất bại).
    """
    masks_dir.mkdir(parents=True, exist_ok=True)
    images = sorted(p for p in images_dir.iterdir() if p.suffix.lower() in IMAGE_EXTS)
    preview_step = max(1, len(images) // PREVIEW_COUNT)
    coverages, previews = [], []

    for i, path in enumerate(images):
        with Image.open(path) as im:
            image = im.convert("RGB")
        mask = predict_mask(image)
        mask.save(masks_dir / f"{path.name}.png")
        coverages.append(float(np.asarray(mask).mean() / 255.0))

        if preview_path is not None and i % preview_step == 0 and len(previews) < PREVIEW_COUNT:
            previews.append(_preview_tile(image, mask))
        if progress:
            progress(i + 1, len(images))

    if previews:
        _save_grid(previews, preview_path)
    return coverages


def _preview_tile(image, mask):
    """Ảnh minh họa kết quả AI: giữ nguyên vật thể, làm tối phần nền."""
    dark = Image.eval(image, lambda v: v // 4)
    tile = Image.composite(image, dark, mask)
    tile.thumbnail((PREVIEW_WIDTH, PREVIEW_WIDTH * 2))
    return tile


def _save_grid(tiles, path, columns=3):
    w = max(t.width for t in tiles)
    h = max(t.height for t in tiles)
    rows = (len(tiles) + columns - 1) // columns
    grid = Image.new("RGB", (w * columns, h * rows), (30, 30, 30))
    for i, tile in enumerate(tiles):
        grid.paste(tile, ((i % columns) * w, (i // columns) * h))
    grid.save(path, quality=90)
