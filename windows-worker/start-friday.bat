@echo off
cd /d "%~dp0"
if not exist .venv py -3 -m venv .venv
call .venv\Scripts\activate.bat
python -m pip install --upgrade pip
pip install -r requirements.txt
echo.
echo Starting FRIDAY local worker on port 8765...
echo Keep this window open while using the Android app.
python -m uvicorn app:app --host 0.0.0.0 --port 8765
pause
