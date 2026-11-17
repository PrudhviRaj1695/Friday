@echo off
cd /d "%~dp0"
if not exist .venv py -3 -m venv .venv
call .venv\Scripts\activate.bat
python -m pip install --upgrade pip >nul
pip install -r requirements.txt
echo.
echo Starting FRIDAY worker on port 8765 (LAN). Keep this window open.
echo The pairing token below is needed once in FRIDAY ^> Settings ^> Connectivity.
echo Tip: for an 8 GB GPU set OLLAMA_MAX_LOADED_MODELS=1 in Windows environment variables.
echo.
python -m uvicorn app:app --host 0.0.0.0 --port 8765
pause
