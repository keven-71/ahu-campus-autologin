@echo off
setlocal
cd /d "%~dp0"
where pythonw.exe >nul 2>nul
if errorlevel 1 goto try_launcher
start "" pythonw.exe "%~dp0campus_login.py"
exit /b
:try_launcher
where pyw.exe >nul 2>nul
if errorlevel 1 goto try_python
start "" pyw.exe -3 "%~dp0campus_login.py"
exit /b
:try_python
where python.exe >nul 2>nul
if errorlevel 1 goto missing
python.exe "%~dp0campus_login.py"
if errorlevel 1 pause
exit /b
:missing
echo Python was not found. Install Python 3.9+ with tkinter, then try again.
pause
