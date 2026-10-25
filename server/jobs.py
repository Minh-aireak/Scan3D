"""Quản lý công việc quét: lưu trạng thái ra file job.json và xử lý lần lượt từng công việc.

Chỉ xử lý 1 công việc mỗi lúc vì GPU laptop (6 GB VRAM) không đủ chạy song song.
"""
import json
import logging
import queue
import threading
import time
import uuid
from datetime import datetime

from pipeline import PipelineError, run_pipeline

log = logging.getLogger("scan3d")

QUEUED, PROCESSING, DONE, FAILED = "queued", "processing", "done", "failed"


def _now():
    return datetime.now().isoformat(timespec="seconds")


class JobStore:
    def __init__(self, root):
        self.root = root
        self.root.mkdir(parents=True, exist_ok=True)
        self._jobs = {}
        self._lock = threading.Lock()
        self._load()

    def _load(self):
        for path in self.root.glob("*/job.json"):
            try:
                job = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                continue
            # Công việc đang dở khi server tắt thì không chạy tiếp được, đánh dấu thất bại
            if job.get("status") in (QUEUED, PROCESSING):
                job.update(status=FAILED, message="Server đã khởi động lại khi đang xử lý. Hãy quét lại.")
                self._save(job)
            self._jobs[job["id"]] = job

    def job_dir(self, job_id):
        return self.root / job_id

    def create(self, name, num_images):
        job = {
            "id": uuid.uuid4().hex[:12],
            "name": name,
            "status": QUEUED,
            "step": "Đang chờ xử lý",
            "progress": 0,
            "message": "",
            "created_at": _now(),
            "started_at": "",
            "finished_at": "",
            "num_images": num_images,
            "stats": {},
            "outputs": [],
        }
        self.job_dir(job["id"]).mkdir(parents=True)
        with self._lock:
            self._jobs[job["id"]] = job
            self._save(job)
        return dict(job)

    def update(self, job_id, **fields):
        with self._lock:
            job = self._jobs[job_id]
            job.update(fields)
            self._save(job)
            return dict(job)

    def get(self, job_id):
        with self._lock:
            job = self._jobs.get(job_id)
            return dict(job) if job else None

    def list_jobs(self):
        with self._lock:
            jobs = [dict(j) for j in self._jobs.values()]
        return sorted(jobs, key=lambda j: j["created_at"], reverse=True)

    def _save(self, job):
        path = self.job_dir(job["id"]) / "job.json"
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps(job, ensure_ascii=False, indent=2), encoding="utf-8")
        tmp.replace(path)


class Worker:
    """Luồng nền lấy công việc từ hàng đợi và chạy quy trình Photogrammetry."""

    def __init__(self, store):
        self.store = store
        self._queue = queue.Queue()
        self._thread = threading.Thread(target=self._run, name="scan3d-worker", daemon=True)

    def start(self):
        if not self._thread.is_alive():
            self._thread.start()

    def submit(self, job_id):
        self._queue.put(job_id)

    def pending(self):
        return self._queue.qsize()

    def _run(self):
        while True:
            job_id = self._queue.get()
            try:
                self._process(job_id)
            finally:
                self._queue.task_done()

    def _process(self, job_id):
        started = time.monotonic()
        self.store.update(job_id, status=PROCESSING, started_at=_now(), step="Bắt đầu xử lý", progress=1)
        log.info("Bắt đầu xử lý %s", job_id)

        def report(step, progress):
            self.store.update(job_id, step=step, progress=int(progress))

        try:
            result = run_pipeline(self.store.job_dir(job_id), report)
        except PipelineError as e:
            log.warning("Công việc %s thất bại: %s", job_id, e)
            self.store.update(job_id, status=FAILED, message=str(e), finished_at=_now())
            return
        except Exception as e:  # lỗi ngoài dự kiến: vẫn báo về ứng dụng thay vì treo ở trạng thái "đang xử lý"
            log.exception("Công việc %s lỗi", job_id)
            self.store.update(job_id, status=FAILED, message=f"Lỗi không xác định: {e}", finished_at=_now())
            return

        result["stats"]["duration_sec"] = round(time.monotonic() - started, 1)
        self.store.update(
            job_id,
            status=DONE,
            step="Hoàn tất",
            progress=100,
            message=result["warning"],
            stats=result["stats"],
            outputs=result["outputs"],
            finished_at=_now(),
        )
        log.info("Hoàn tất %s sau %.0f giây", job_id, result["stats"]["duration_sec"])
