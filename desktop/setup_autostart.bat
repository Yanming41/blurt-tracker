@echo off
REM 注册 Blurt 桌面后端为 Windows 登录时自动启动的任务
REM 用法：以管理员身份运行此脚本一次即可

setlocal
set TASK_NAME=BlurtDesktop
set SCRIPT_DIR=%~dp0
set PYTHON_EXE=python
set MAIN_SCRIPT=%SCRIPT_DIR%main.py

echo Registering scheduled task "%TASK_NAME%" ...
schtasks /Create ^
    /TN "%TASK_NAME%" ^
    /TR "\"%PYTHON_EXE%\" \"%MAIN_SCRIPT%\"" ^
    /SC ONLOGON ^
    /RL HIGHEST ^
    /F

if %ERRORLEVEL%==0 (
    echo Done. Task "%TASK_NAME%" will run on next logon.
    echo To remove:  schtasks /Delete /TN "%TASK_NAME%" /F
) else (
    echo Failed to register task. Run this script as Administrator.
)

endlocal
pause
