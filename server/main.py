"""Server quét 3D: nhận ảnh từ ứng dụng Android, chạy Photogrammetry + AI, trả về mô hình 3D.

Chạy: python main.py   (tài liệu API tự sinh tại http://<ip>:8000/docs)
"""
import html
import logging
import shutil
from contextlib import asynccontextmanager
from pathlib import Path
from typing import List

import uvicorn
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse, HTMLResponse
from fastapi.staticfiles import StaticFiles

import config
from jobs import DONE, FAILED, JobStore, Worker

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

store = JobStore(config.DATA_DIR / "jobs")
worker = Worker(store)

# File được phép tải về: tên file -> (thư mục con trong thư mục công việc, kiểu MIME)
DOWNLOADS = {
    "model.glb": ("output", "model/gltf-binary"),
    "model.obj": ("output", "text/plain"),
    "model.ply": ("output", "application/octet-stream"),
    "masks_preview.jpg": ("output", "image/jpeg"),
    "log.txt": ("", "text/plain; charset=utf-8"),
}

MODEL_VIEWER_CDN = "https://cdn.jsdelivr.net/npm/@google/model-viewer@3.5.0/dist/model-viewer.min.js"

VIEWER_HTML = """<!doctype html>
<html lang="vi">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{name}</title>
<script type="module" src="{script}"></script>
<style>
  html, body {{ margin: 0; height: 100%; background: #eceff1; font-family: sans-serif; }}
  model-viewer {{ width: 100%; height: 100%; --poster-color: transparent; }}
</style>
</head>
<body>
<model-viewer src="{model}" alt="{name}" camera-controls auto-rotate touch-action="none"
  shadow-intensity="1" exposure="1.1"></model-viewer>
</body>
</html>"""


@asynccontextmanager
async def lifespan(_app):
    worker.start()
    yield


app = FastAPI(title="Scan3D Server", lifespan=lifespan)
config.STATIC_DIR.mkdir(exist_ok=True)
app.mount("/static", StaticFiles(directory=config.STATIC_DIR), name="static")


def _colmap_available():
    return shutil.which(config.COLMAP_BIN) is not None or Path(config.COLMAP_BIN).is_file()


def _get_job(job_id):
    job = store.get(job_id)
    if job is None:
        raise HTTPException(404, "Không tìm thấy công việc")
    return job


@app.get("/")
def root():
    return {"app": "Scan3D server", "docs": "/docs"}


@app.get("/api/health")
def health():
    return {
        "status": "ok",
        "colmap": _colmap_available(),
        "colmap_bin": config.COLMAP_BIN,
        "rembg_model": config.REMBG_MODEL,
        "queue": worker.pending(),
    }


@app.post("/api/jobs")
def create_job(name: str = Form("Vật thể"), images: List[UploadFile] = File(...)):
    if len(images) < config.MIN_IMAGES:
        raise HTTPException(400, f"Cần ít nhất {config.MIN_IMAGES} ảnh, server mới nhận {len(images)} ảnh")
    if len(images) > config.MAX_IMAGES:
        raise HTTPException(400, f"Tối đa {config.MAX_IMAGES} ảnh mỗi lần quét")

    job = store.create(name.strip()[:100] or "Vật thể", len(images))
    upload_dir = store.job_dir(job["id"]) / "uploads"
    upload_dir.mkdir()
    try:
        for i, image in enumerate(images):
            ext = Path(image.filename or "").suffix.lower()
            if ext not in (".jpg", ".jpeg", ".png"):
                ext = ".jpg"
            with open(upload_dir / f"{i:04d}{ext}", "wb") as out:
                shutil.copyfileobj(image.file, out)
    except OSError as e:
        store.update(job["id"], status=FAILED, message=f"Server không lưu được ảnh: {e}")
        raise HTTPException(500, "Server không lưu được ảnh") from e

    worker.submit(job["id"])
    return store.get(job["id"])


@app.get("/api/jobs")
def list_jobs():
    return {"jobs": store.list_jobs()}


@app.get("/api/jobs/{job_id}")
def get_job(job_id: str):
    return _get_job(job_id)


@app.get("/api/jobs/{job_id}/files/{filename}")
def download_file(job_id: str, filename: str):
    _get_job(job_id)  # kiểm tra job_id hợp lệ, tránh truy cập file ngoài thư mục dữ liệu
    if filename not in DOWNLOADS:
        raise HTTPException(404, "File không hợp lệ")
    subdir, media_type = DOWNLOADS[filename]
    path = store.job_dir(job_id) / subdir / filename
    if not path.is_file():
        raise HTTPException(404, "File chưa được tạo")
    return FileResponse(path, media_type=media_type)


@app.get("/api/jobs/{job_id}/viewer", response_class=HTMLResponse)
def viewer(job_id: str):
    job = _get_job(job_id)
    if job["status"] != DONE:
        raise HTTPException(409, "Mô hình chưa xử lý xong")
    # Nếu đã tải model-viewer.min.js vào thư mục static thì dùng bản offline, không cần Internet
    local_script = config.STATIC_DIR / "model-viewer.min.js"
    script = "/static/model-viewer.min.js" if local_script.is_file() else MODEL_VIEWER_CDN
    return VIEWER_HTML.format(
        name=html.escape(job["name"]),
        script=script,
        model=f"/api/jobs/{job_id}/files/model.glb",
    )


if __name__ == "__main__":
    uvicorn.run(app, host=config.HOST, port=config.PORT)
