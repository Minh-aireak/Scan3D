@echo off
REM Khoi dong server Scan3D tren Windows.
REM Sua duong dan COLMAP ben duoi cho dung voi may cua ban (ban Windows co CUDA).
cd /d %~dp0
if "%COLMAP_BIN%"=="" set COLMAP_BIN=C:\colmap\COLMAP.bat
call .venv\Scripts\activate.bat
python main.py
pause
