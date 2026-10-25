"""Hậu xử lý lưới 3D sau Poisson: làm sạch, giảm số tam giác, tô màu, xoay đứng và xuất file."""
import numpy as np
import trimesh
from scipy.spatial import cKDTree

# Đỉnh lưới cách đám mây điểm xa hơn (FAR_FACTOR x khoảng cách điểm trung bình) là bề mặt "ảo" do Poisson tự thêm
FAR_FACTOR = 6.0
# Giữ các mảnh có số tam giác >= tỉ lệ này so với mảnh lớn nhất (vật thể có thể gồm vài phần rời)
KEEP_COMPONENT_RATIO = 0.05
EXPORT_NAMES = ("model.glb", "model.obj", "model.ply")


def finalize(mesh_path, cloud_path, poses, out_dir, target_faces):
    """Trả về thống kê của mô hình; ghi model.glb / model.obj / model.ply vào out_dir."""
    mesh = trimesh.load(mesh_path, force="mesh", process=False)
    if len(mesh.faces) == 0:
        raise ValueError("Lưới Poisson rỗng")

    cloud = trimesh.load(cloud_path)
    points = np.asarray(cloud.vertices)
    if len(points) == 0:
        raise ValueError("Đám mây điểm rỗng")
    colors = _point_colors(cloud, len(points))
    tree = cKDTree(points)

    mesh = _remove_far_faces(mesh, tree, points)
    mesh = _keep_main_components(mesh)
    mesh = _simplify(mesh, target_faces)

    # Tô màu mỗi đỉnh theo điểm gần nhất của đám mây điểm (màu lấy từ ảnh chụp thật)
    _, nearest = tree.query(mesh.vertices)
    mesh.visual = trimesh.visual.ColorVisuals(mesh, vertex_colors=colors[nearest])

    # Xoay cho vật thể đứng thẳng (trục +Y hướng lên như chuẩn glTF), đặt đáy vật thể tại gốc toạ độ
    mesh.apply_transform(_upright_transform(poses))
    lo, hi = mesh.bounds
    mesh.apply_translation([-(lo[0] + hi[0]) / 2, -lo[1], -(lo[2] + hi[2]) / 2])

    for name in EXPORT_NAMES:
        mesh.export(out_dir / name)
    return {"num_points": int(len(points)), "vertices": int(len(mesh.vertices)), "faces": int(len(mesh.faces))}


def _point_colors(cloud, count):
    if isinstance(cloud, trimesh.Trimesh):
        colors = np.asarray(cloud.visual.vertex_colors)
    else:
        colors = np.asarray(getattr(cloud, "colors", []))
    if colors.ndim != 2 or len(colors) != count:
        return np.tile(np.array([180, 180, 180, 255], dtype=np.uint8), (count, 1))
    if colors.shape[1] == 3:
        colors = np.hstack([colors, np.full((count, 1), 255)])
    return colors.astype(np.uint8)


def _remove_far_faces(mesh, tree, points):
    rng = np.random.default_rng(0)
    sample = points[rng.choice(len(points), size=min(len(points), 20000), replace=False)]
    spacing = float(np.median(tree.query(sample, k=2)[0][:, 1]))
    dist, _ = tree.query(mesh.vertices)
    threshold = max(spacing * FAR_FACTOR, float(np.median(dist)) * 3)
    near = dist <= threshold
    keep = near[mesh.faces].all(axis=1)
    if keep.sum() < 100:
        return mesh
    return mesh.submesh([np.nonzero(keep)[0]], append=True)


def _keep_main_components(mesh):
    try:
        parts = list(mesh.split(only_watertight=False))
    except Exception:  # thiếu thư viện đồ thị hoặc lỗi tách mảnh -> giữ nguyên
        return mesh
    if len(parts) <= 1:
        return mesh
    largest = max(len(p.faces) for p in parts)
    kept = [p for p in parts if len(p.faces) >= largest * KEEP_COMPONENT_RATIO]
    return trimesh.util.concatenate(kept)


def _simplify(mesh, target_faces):
    if len(mesh.faces) <= target_faces:
        return mesh
    try:
        return mesh.simplify_quadric_decimation(face_count=target_faces)
    except Exception:  # thiếu thư viện fast-simplification -> giữ lưới gốc
        return mesh


def _upright_transform(poses):
    """Tìm hướng "lên" từ vị trí các camera.

    Khi đi vòng quanh vật thể, các camera nằm gần một mặt phẳng nằm ngang; pháp tuyến của mặt phẳng đó
    (tìm bằng PCA/SVD) chính là hướng thẳng đứng. Dấu của hướng lấy theo trục "lên" trung bình của camera.
    """
    if len(poses) < 3:
        return np.eye(4)
    centers = np.array([c for c, _ in poses])
    mean_up = np.mean([u for _, u in poses], axis=0)
    _, s, vt = np.linalg.svd(centers - centers.mean(axis=0))
    up = vt[2]
    # Camera chỉ đi một cung nhỏ thì mặt phẳng không rõ ràng, dùng hướng "lên" trung bình của camera
    if s[1] < 0.3 * s[0]:
        up = mean_up
    if np.dot(up, mean_up) < 0:
        up = -up
    norm = np.linalg.norm(up)
    if norm < 1e-9:
        return np.eye(4)
    return trimesh.geometry.align_vectors(up / norm, [0.0, 1.0, 0.0])
