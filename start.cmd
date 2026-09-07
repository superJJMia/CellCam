@echo off
rem ============================================================
rem  CellCam Desktop - iniciar receptor + webcam virtual
rem  Uso:  start.cmd [--backend dry] [--room 123456] [--fps 30]
rem ============================================================
setlocal
cd /d "%~dp0desktop"

if not exist ".venv\Scripts\python.exe" (
    echo [ERRO] Ambiente Python nao encontrado em desktop\.venv
    echo.
    echo Crie o ambiente antes:
    echo   python -m venv desktop\.venv
    echo   desktop\.venv\Scripts\pip install -r desktop\requirements.txt
    echo.
    pause
    exit /b 1
)

echo Iniciando CellCam Desktop...
".venv\Scripts\python.exe" main.py %*

pause