@echo off
REM 注册 Blurt 桌面后端为 Windows 登录时自动启动的任务
REM 用法：以管理员身份运行此脚本一次即可

setlocal
set TASK_NAME=BlurtDesktop
set SCRIPT_DIR=%~dp0
set MAIN_SCRIPT=%SCRIPT_DIR%main.py

REM 优先使用 venv 里的 python（pythonw 无黑窗口），其次回落到系统 python
set PYTHON_EXE=%SCRIPT_DIR%.venv\Scripts\pythonw.exe
if not exist "%PYTHON_EXE%" set PYTHON_EXE=pythonw

REM 用 cmd /c start "" /MIN 方式启动，避免登陆时弹出可见的命令行窗口
set RUN_CMD=cmd /c start "" /MIN /D "%SCRIPT_DIR%" "%PYTHON_EXE%" "%MAIN_SCRIPT%"

echo Registering scheduled task "%TASK_NAME%" ...
echo Using Python: %PYTHON_EXE%
schtasks /Create ^
    /TN "%TASK_NAME%" ^
    /TR "%RUN_CMD%" ^
    /SC ONLOGON ^
    /RL HIGHEST ^
    /F

if %ERRORLEVEL%==0 (
    echo.
    echo Done. Task "%TASK_NAME%" will run on next logon.
    echo.
    echo Useful commands:
    echo   Run now:  schtasks /Run /TN "%TASK_NAME%"
    echo   Status:   schtasks /Query /TN "%TASK_NAME%"
    echo   Remove:   schtasks /Delete /TN "%TASK_NAME%" /F
) else (
    echo Failed to register task. Run this script as Administrator.
)

endlocal
pause
