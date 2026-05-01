@echo off
setlocal
set PORT=8765
cd /d "%~dp0"
start "" "http://localhost:%PORT%/#/dashboard"
python serve.py
