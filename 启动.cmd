@echo off
cd /d "%~dp0"
where python >nul 2>&1
if not errorlevel 1 (
  python server.py
) else (
  py -3 server.py
)
if errorlevel 1 pause
