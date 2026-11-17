#!/usr/bin/env bash
# Khởi động server Scan3D trên Linux:   bash run_server.sh
#
# Đường dẫn COLMAP được chọn theo thứ tự:
#   1. Biến môi trường COLMAP_BIN, ví dụ:  COLMAP_BIN=/opt/colmap/bin/colmap bash run_server.sh
#   2. Môi trường conda tên "colmap" (bản CUDA, cài theo bước 3B.3 trong HUONG_DAN_CHAY_DU_AN.md)
#   3. Lệnh "colmap" có trong PATH. Xếp sau conda vì bản "apt install colmap" của Ubuntu
#      không có CUDA, không chạy được bước PatchMatch.
set -euo pipefail
cd "$(dirname "$0")"

if [ -z "${COLMAP_BIN:-}" ]; then
    candidates=()
    conda_exe="${CONDA_EXE:-$(command -v conda || true)}"
    if [ -n "$conda_exe" ]; then
        candidates+=("$("$conda_exe" info --base 2>/dev/null || true)/envs/colmap/bin/colmap")
    fi
    candidates+=(
        "$HOME/miniforge3/envs/colmap/bin/colmap"
        "$HOME/miniconda3/envs/colmap/bin/colmap"
        "$HOME/anaconda3/envs/colmap/bin/colmap"
        "$(command -v colmap || true)"
    )
    for c in "${candidates[@]}"; do
        if [ -n "$c" ] && [ -x "$c" ]; then
            COLMAP_BIN="$c"
            break
        fi
    done
fi
export COLMAP_BIN="${COLMAP_BIN:-colmap}"

if [ ! -x .venv/bin/python ]; then
    echo "Chưa có môi trường Python .venv trong thư mục server." >&2
    echo "Hãy làm theo mục 3B trong HUONG_DAN_CHAY_DU_AN.md trước." >&2
    exit 1
fi

echo "COLMAP_BIN=$COLMAP_BIN"
exec .venv/bin/python main.py
