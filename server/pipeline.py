"""Quy trình Photogrammetry kết hợp AI: ảnh chụp -> mô hình 3D.

  1. Chuẩn hóa ảnh (xoay theo EXIF, thu nhỏ)
  2. Structure from Motion (COLMAP): trích xuất SIFT -> ghép cặp -> tính vị trí camera + đám mây điểm thưa
  3. Khử méo ảnh theo thông số camera vừa ước lượng
  4. AI tách nền (rembg) trên ảnh đã khử méo -> mặt nạ vật thể
  5. Multi-View Stereo (COLMAP): PatchMatch Stereo -> hợp nhất đám mây điểm dày (chỉ giữ điểm trong mặt nạ)
  6. Dựng lưới bề mặt bằng Poisson Surface Reconstruction
  7. Hậu xử lý: làm sạch, giảm lưới, tô màu, xoay đứng, xuất GLB/OBJ/PLY
"""
import os
import subprocess

import numpy as np
from PIL import Image, ImageOps

import config
import mesh_post
import segmentation

IMAGE_EXTS = {".jpg", ".jpeg", ".png"}
# Tỉ lệ diện tích vật thể trung bình dưới mức này coi như AI không tách được vật thể khỏi nền
MIN_MASK_COVERAGE = 0.02


class PipelineError(Exception):
    """Lỗi kèm thông báo dễ hiểu để hiển thị trên ứng dụng."""


class Colmap:
    """Gọi các lệnh COLMAP qua dòng lệnh, ghi toàn bộ output vào log.txt của công việc."""

    def __init__(self, log_path):
        self.log_path = log_path

    def run(self, command, *args):
        cmd = [config.COLMAP_BIN, command, *[str(a) for a in args]]
        env = os.environ.copy()
        if os.name != "nt":
            env.setdefault("QT_QPA_PLATFORM", "offscreen")  # chạy được trên máy không có màn hình
        with open(self.log_path, "a", encoding="utf-8", errors="replace") as log:
            log.write("\n$ " + " ".join(cmd) + "\n")
            log.flush()
            try:
                proc = subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT, env=env)
            except FileNotFoundError:
                raise PipelineError(
                    f"Không tìm thấy COLMAP ({config.COLMAP_BIN}). Kiểm tra biến môi trường COLMAP_BIN."
                ) from None
        return proc.returncode

    def check(self, command, *args):
        code = self.run(command, *args)
        if code != 0:
            raise PipelineError(f"COLMAP lỗi ở bước '{command}' (mã {code}). Xem chi tiết trong log.txt.")


def run_pipeline(job_dir, report):
    """report(step, progress) dùng để cập nhật tiến trình (0–100) cho ứng dụng."""
    upload_dir = job_dir / "uploads"
    workspace = job_dir / "workspace"
    out_dir = job_dir / "output"
    images_dir = workspace / "images"
    database = workspace / "database.db"
    sparse_dir = workspace / "sparse"
    dense_dir = workspace / "dense"
    for d in (images_dir, sparse_dir, out_dir):
        d.mkdir(parents=True, exist_ok=True)
    colmap = Colmap(job_dir / "log.txt")
    warnings = []

    # 1. Chuẩn hóa ảnh
    report("Chuẩn hóa ảnh", 3)
    names, same_size = prepare_images(upload_dir, images_dir)
    if len(names) < config.MIN_IMAGES:
        raise PipelineError(f"Chỉ đọc được {len(names)} ảnh hợp lệ, cần ít nhất {config.MIN_IMAGES} ảnh.")

    # 2. Structure from Motion
    feature_args = ["--database_path", database, "--image_path", images_dir]
    if same_size:
        feature_args += ["--ImageReader.single_camera", "1"]  # mọi ảnh cùng một camera điện thoại
    if config.USE_MASK_FOR_SFM:
        masks_sfm = workspace / "masks_sfm"
        segmentation.segment_folder(images_dir, masks_sfm, progress=_sub_progress(report, "AI tách nền ảnh gốc", 5, 15))
        feature_args += ["--ImageReader.mask_path", masks_sfm]

    report("Trích xuất đặc trưng SIFT", 15)
    colmap.check("feature_extractor", *feature_args)

    report("Ghép cặp đặc trưng giữa các ảnh", 25)
    colmap.check("exhaustive_matcher", "--database_path", database)

    report("Structure from Motion: tính vị trí camera", 35)
    colmap.check("mapper", "--database_path", database, "--image_path", images_dir, "--output_path", sparse_dir)
    model_dir = _largest_model(sparse_dir)
    if model_dir is None:
        raise PipelineError(
            "Không xác định được vị trí camera. Hãy chụp dày hơn (mỗi ảnh lệch 10–15°) "
            "và chọn vật thể có hoa văn, chi tiết."
        )

    txt_dir = workspace / "sparse_txt"
    txt_dir.mkdir(exist_ok=True)
    colmap.check("model_converter", "--input_path", model_dir, "--output_path", txt_dir, "--output_type", "TXT")
    poses = read_camera_poses(txt_dir / "images.txt")
    registered = len(poses)
    if registered < config.MIN_REGISTERED:
        raise PipelineError(
            f"Chỉ định vị được {registered}/{len(names)} ảnh. Ảnh cần trùng nhau nhiều hơn, tránh ảnh mờ."
        )
    if registered < len(names) * 0.6:
        warnings.append(f"Chỉ {registered}/{len(names)} ảnh được sử dụng, mô hình có thể bị thiếu một số mặt.")

    # 3. Khử méo ảnh
    report("Khử méo ảnh", 45)
    colmap.check(
        "image_undistorter",
        "--image_path", images_dir,
        "--input_path", model_dir,
        "--output_path", dense_dir,
        "--output_type", "COLMAP",
        "--max_image_size", config.DENSE_MAX_IMAGE_SIZE,
    )

    # 4. AI tách nền
    masks_dir = dense_dir / "masks"
    coverages = segmentation.segment_folder(
        dense_dir / "images",
        masks_dir,
        preview_path=out_dir / "masks_preview.jpg",
        progress=_sub_progress(report, "AI tách nền vật thể", 48, 58),
    )
    mean_coverage = float(np.mean(coverages)) if coverages else 0.0

    # 5. Multi-View Stereo
    report("Tái tạo dày đặc bằng PatchMatch Stereo (GPU)", 58)
    colmap.check(
        "patch_match_stereo",
        "--workspace_path", dense_dir,
        "--workspace_format", "COLMAP",
        "--PatchMatchStereo.geom_consistency", "true",
    )

    report("Hợp nhất đám mây điểm", 85)
    fused = dense_dir / "fused.ply"
    fusion_args = [
        "--workspace_path", dense_dir,
        "--workspace_format", "COLMAP",
        "--input_type", "geometric",
        "--output_path", fused,
    ]
    if mean_coverage < MIN_MASK_COVERAGE:
        warnings.append("AI không tách được vật thể khỏi nền, mô hình có thể dính cả phần nền.")
        colmap.check("stereo_fusion", *fusion_args)
    elif colmap.run("stereo_fusion", *fusion_args, "--StereoFusion.mask_path", masks_dir) != 0:
        # Bản COLMAP cũ chưa hỗ trợ mask_path: hợp nhất toàn bộ, phần nền sẽ được lọc bớt ở bước hậu xử lý
        warnings.append("Bản COLMAP này không hỗ trợ mặt nạ khi hợp nhất, mô hình có thể dính phần nền.")
        colmap.check("stereo_fusion", *fusion_args)
    if not fused.exists() or fused.stat().st_size < 1024:
        raise PipelineError("Đám mây điểm rỗng. Vật thể có thể quá bóng, trong suốt hoặc thiếu chi tiết bề mặt.")

    # 6. Dựng lưới Poisson
    report("Dựng lưới bề mặt (Poisson)", 90)
    meshed = dense_dir / "meshed-poisson.ply"
    colmap.check(
        "poisson_mesher",
        "--input_path", fused,
        "--output_path", meshed,
        "--PoissonMeshing.depth", config.POISSON_DEPTH,
    )

    # 7. Hậu xử lý
    report("Hậu xử lý và xuất file", 95)
    try:
        stats = mesh_post.finalize(meshed, fused, poses, out_dir, config.TARGET_FACES)
    except ValueError as e:
        raise PipelineError(f"Hậu xử lý lưới thất bại: {e}") from e

    stats.update(num_images=len(names), num_registered=registered, mask_coverage=round(mean_coverage, 3))
    return {
        "stats": stats,
        "outputs": sorted(p.name for p in out_dir.iterdir() if p.is_file()),
        "warning": " ".join(warnings),
    }


def prepare_images(src_dir, dst_dir):
    """Xoay ảnh theo EXIF và thu nhỏ. Trả về (danh sách tên ảnh, mọi ảnh có cùng kích thước hay không)."""
    sources = sorted(p for p in src_dir.iterdir() if p.suffix.lower() in IMAGE_EXTS)
    names, sizes = [], set()
    for i, src in enumerate(sources):
        name = f"img_{i:04d}.jpg"
        try:
            with Image.open(src) as im:
                img = ImageOps.exif_transpose(im)
                # Giữ lại EXIF (tiêu cự ống kính) giúp COLMAP ước lượng thông số camera chính xác hơn
                exif = img.info.get("exif")
                img = img.convert("RGB")
                img.thumbnail((config.MAX_IMAGE_SIZE, config.MAX_IMAGE_SIZE), Image.Resampling.LANCZOS)
                save_args = {"quality": 95}
                if exif:
                    save_args["exif"] = exif
                img.save(dst_dir / name, **save_args)
        except OSError:
            continue  # bỏ qua file ảnh hỏng
        names.append(name)
        sizes.add(img.size)
    return names, len(sizes) == 1


def read_camera_poses(images_txt):
    """Đọc images.txt của COLMAP -> danh sách (tâm camera, hướng "lên" của camera) trong hệ toạ độ thế giới."""
    lines = [line for line in images_txt.read_text(encoding="utf-8").splitlines() if not line.startswith("#")]
    poses = []
    # Mỗi ảnh chiếm 2 dòng: dòng thông số camera, sau đó là dòng điểm 2D (có thể rỗng)
    for line in lines[::2]:
        parts = line.split()
        if len(parts) < 10:
            continue
        qw, qx, qy, qz, tx, ty, tz = map(float, parts[1:8])
        rot = _quat_to_matrix(qw, qx, qy, qz)
        center = -rot.T @ np.array([tx, ty, tz])
        up = rot.T @ np.array([0.0, -1.0, 0.0])  # trục y của camera COLMAP hướng xuống
        poses.append((center, up))
    return poses


def _quat_to_matrix(w, x, y, z):
    n = np.sqrt(w * w + x * x + y * y + z * z)
    w, x, y, z = w / n, x / n, y / n, z / n
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y)],
        [2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x)],
        [2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)],
    ])


def _largest_model(sparse_dir):
    """COLMAP có thể tạo nhiều mô hình rời (sparse/0, sparse/1...); chọn mô hình có nhiều ảnh nhất."""
    models = [d for d in sparse_dir.iterdir() if (d / "images.bin").exists()]
    return max(models, key=lambda d: (d / "images.bin").stat().st_size, default=None)


def _sub_progress(report, step, start, end):
    def update(done, total):
        report(f"{step} ({done}/{total})", start + (end - start) * done / max(total, 1))
    return update
