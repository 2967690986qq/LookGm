@echo off
chcp 65001 >nul 2>&1
setlocal enabledelayedexpansion
title 全游戏通用AI视觉助手 - PC服务端

:: ── ANSI 颜色代码 ──
for /f %%a in ('echo prompt $E^| cmd') do set "ESC=%%a"
set "C_RESET=%ESC%[0m"
set "C_RED=%ESC%[91m"
set "C_GREEN=%ESC%[92m"
set "C_YELLOW=%ESC%[93m"
set "C_BLUE=%ESC%[94m"
set "C_CYAN=%ESC%[96m"
set "C_BOLD=%ESC%[1m"
set "C_DIM=%ESC%[2m"

:: ── 路径 ──
set "SCRIPT_DIR=%~dp0"
cd /d "%SCRIPT_DIR%"
set "VENV_DIR=%SCRIPT_DIR%venv"

echo.
echo %C_CYAN%%C_BOLD%╔══════════════════════════════════════════════════╗%C_RESET%
echo %C_CYAN%%C_BOLD%║       🎮 全游戏通用AI视觉助手 - PC服务端        ║%C_RESET%
echo %C_CYAN%%C_BOLD%╚══════════════════════════════════════════════════╝%C_RESET%
echo.

:: ── 解析命令行参数 ──
set "ARG_MODEL_TYPE=auto"
set "ARG_NO_AUTO_DOWNLOAD=0"
set "ARG_PORT=8765"
set "ARG_SKIP_VENV=0"

:parse_args
if "%~1"=="" goto :args_done
if /i "%~1"=="--model-type" (
    set "ARG_MODEL_TYPE=%~2"
    shift
    shift
    goto :parse_args
)
if /i "%~1"=="--ollama" (
    set "ARG_MODEL_TYPE=ollama"
    shift
    goto :parse_args
)
if /i "%~1"=="--vllm" (
    set "ARG_MODEL_TYPE=vllm"
    shift
    goto :parse_args
)
if /i "%~1"=="--lmstudio" (
    set "ARG_MODEL_TYPE=lmstudio"
    shift
    goto :parse_args
)
if /i "%~1"=="--no-download" (
    set "ARG_NO_AUTO_DOWNLOAD=1"
    shift
    goto :parse_args
)
if /i "%~1"=="--port" (
    set "ARG_PORT=%~2"
    shift
    shift
    goto :parse_args
)
if /i "%~1"=="--no-venv" (
    set "ARG_SKIP_VENV=1"
    shift
    goto :parse_args
)
if /i "%~1"=="--help" (
    goto :show_help
)
shift
goto :parse_args

:args_done

:: ── 第一步：Python环境检测 ──
echo %C_BOLD%[1/6]%C_RESET% 检测Python环境...

:: 优先检测 Python 3.10+
set "PYTHON_EXE="
for %%v in (python3.13 python3.12 python3.11 python3.10 python3 python) do (
    where %%v >nul 2>&1
    if !errorlevel! equ 0 (
        for /f "delims=" %%p in ('where %%v 2^>nul') do (
            set "PYTHON_EXE=%%p"
            goto :python_found
        )
    )
)

:python_found
if "%PYTHON_EXE%"=="" (
    echo %C_RED%[错误]%C_RESET% 未检测到Python，请先安装Python 3.10+
    echo   下载地址: https://www.python.org/downloads/
    pause
    exit /b 1
)

for /f "tokens=2" %%v in ('"%PYTHON_EXE%" --version 2^>^&1') do set "PY_VER=%%v"
echo %C_GREEN%  ✓%C_RESET% 找到 Python %PY_VER% ^(%PYTHON_EXE%^)

:: ── 第二步：虚拟环境 ──
if "%ARG_SKIP_VENV%"=="1" (
    echo %C_YELLOW%[2/6]%C_RESET% 跳过虚拟环境 (--no-venv)
    set "PIP_EXE=%PYTHON_EXE% -m pip"
    set "PY_RUN=%PYTHON_EXE%"
    goto :deps_check
)

echo %C_BOLD%[2/6]%C_RESET% 准备虚拟环境...
if exist "%VENV_DIR%\Scripts\activate.bat" (
    echo %C_GREEN%  ✓%C_RESET% 找到已有虚拟环境
) else (
    echo %C_DIM%  创建虚拟环境...%C_RESET%
    "%PYTHON_EXE%" -m venv "%VENV_DIR%" 2>nul
    if !errorlevel! neq 0 (
        echo %C_YELLOW%[警告]%C_RESET% 虚拟环境创建失败，使用系统Python
        set "PIP_EXE=%PYTHON_EXE% -m pip"
        set "PY_RUN=%PYTHON_EXE%"
        goto :deps_check
    )
    echo %C_GREEN%  ✓%C_RESET% 虚拟环境创建完成
)

call "%VENV_DIR%\Scripts\activate.bat"
set "PIP_EXE=pip"
set "PY_RUN=python"
echo %C_GREEN%  ✓%C_RESET% 已激活虚拟环境

:deps_check

:: ── 第三步：依赖安装 ──
echo %C_BOLD%[3/6]%C_RESET% 检查Python依赖包...

:: 快速检查关键包
set "DEPS_OK=1"
for %%p in (fastapi uvicorn websockets aiohttp) do (
    %PY_RUN% -c "import %%p" 2>nul
    if !errorlevel! neq 0 set "DEPS_OK=0"
)

if "%DEPS_OK%"=="0" (
    echo %C_DIM%  安装依赖包...%C_RESET%
    %PIP_EXE% install -r requirements.txt -q 2>nul
    if !errorlevel! neq 0 (
        echo %C_YELLOW%[警告]%C_RESET% 部分依赖安装失败，尝试继续...
    ) else (
        echo %C_GREEN%  ✓%C_RESET% 依赖安装完成
    )
) else (
    echo %C_GREEN%  ✓%C_RESET% 依赖已就绪
)

:: ── 第四步：GPU检测 ──
echo %C_BOLD%[4/6]%C_RESET% 检测硬件加速...

where nvidia-smi >nul 2>&1
if %errorlevel% equ 0 (
    for /f "tokens=*" %%g in ('nvidia-smi --query-gpu^=name --format^=csv^,noheader 2^>nul') do (
        echo %C_GREEN%  ✓%C_RESET% 发现GPU: %%g
        goto :gpu_done
    )
)
echo %C_DIM%  未检测到NVIDIA GPU，将使用CPU推理%C_RESET%

:gpu_done

:: ── 第五步：模型服务检测 ──
echo %C_BOLD%[5/6]%C_RESET% 扫描本地模型服务...

where ollama >nul 2>&1
if %errorlevel% equ 0 (
    for /f "tokens=*" %%m in ('ollama list 2^>nul ^| findstr /v "NAME"') do (
        echo %C_GREEN%  ✓%C_RESET% Ollama可用: %%m
        goto :model_scan_done
    )
    echo %C_YELLOW%  •%C_RESET% Ollama已安装但无模型，使用降级评分模式
) else (
    echo %C_DIM%  •%C_RESET% 未检测到Ollama
)

:: vLLM检测
%PY_RUN% -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=2)" 2>nul
if %errorlevel% equ 0 (
    echo %C_GREEN%  ✓%C_RESET% vLLM服务可用 (http://127.0.0.1:8000)
)

:: LM Studio检测
%PY_RUN% -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:1234/v1/models', timeout=2)" 2>nul
if %errorlevel% equ 0 (
    echo %C_GREEN%  ✓%C_RESET% LM Studio服务可用 (http://127.0.0.1:1234)
)

:model_scan_done

:: ── 第六步：网络信息 ──
echo %C_BOLD%[6/6]%C_RESET% 获取网络信息...

set "LOCAL_IP=127.0.0.1"
for /f "tokens=*" %%i in ('%PY_RUN% -c "import socket;s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.connect(('8.8.8.8',80));print(s.getsockname()[0]);s.close()" 2^>nul') do (
    set "LOCAL_IP=%%i"
)

:: 备用：从ipconfig解析（过滤虚拟适配器）
if "%LOCAL_IP%"=="127.0.0.1" (
    for /f "tokens=1,2 delims=:" %%a in ('ipconfig ^| findstr /i "IPv4" ^| findstr /v "192.168.56 192.168.137 192.168.223"') do (
        for /f "tokens=*" %%i in ("%%b") do (
            set "IP_TMP=%%i"
            set "IP_TMP=!IP_TMP: =!"
            if not "!IP_TMP!"=="" if not "!IP_TMP!"=="127.0.0.1" if "!IP_TMP:~0,3!"=="192" set "LOCAL_IP=!IP_TMP!"
            if not "!IP_TMP!"=="" if not "!IP_TMP!"=="127.0.0.1" if "!IP_TMP:~0,3!"=="10." set "LOCAL_IP=!IP_TMP!"
            if not "!IP_TMP!"=="" if not "!IP_TMP!"=="127.0.0.1" if "!IP_TMP:~0,4!"=="172." set "LOCAL_IP=!IP_TMP!"
        )
        if not "!LOCAL_IP!"=="127.0.0.1" goto :ip_found
    )
)

:ip_found

:: ── 启动服务 ──
echo.
echo %C_BOLD%══════════════════════════════════════════%C_RESET%
echo %C_GREEN%  服务端已就绪，正在启动...%C_RESET%
echo %C_BOLD%══════════════════════════════════════════%C_RESET%
echo.
echo   %C_CYAN%本地地址:%C_RESET%    ws://127.0.0.1:%ARG_PORT%/ws/game_stream
echo   %C_CYAN%局域网地址:%C_RESET%  ws://%LOCAL_IP%:%ARG_PORT%/ws/game_stream
echo   %C_CYAN%HTTP API:%C_RESET%    http://%LOCAL_IP%:%ARG_PORT%/health
echo   %C_CYAN%模型类型:%C_RESET%    %ARG_MODEL_TYPE%
echo.
echo   %C_DIM%手机端配置 →%C_RESET% IP: %C_CYAN%%LOCAL_IP%%C_RESET%  端口: %C_CYAN%%ARG_PORT%%C_RESET%
echo.
echo %C_BOLD%══════════════════════════════════════════%C_RESET%
echo   %C_DIM%按 Ctrl+C 停止服务%C_RESET%
echo %C_BOLD%══════════════════════════════════════════%C_RESET%
echo.

:: 构建命令行参数
set "CMD_ARGS=--host 0.0.0.0 --port %ARG_PORT% --model-type %ARG_MODEL_TYPE%"
if "%ARG_NO_AUTO_DOWNLOAD%"=="1" set "CMD_ARGS=%CMD_ARGS% --no-auto-download"

%PY_RUN% main.py %CMD_ARGS%

echo.
echo %C_YELLOW%服务已停止%C_RESET%
pause
exit /b 0

:show_help
echo.
echo %C_BOLD%全游戏通用AI视觉助手 - PC服务端启动脚本%C_RESET%
echo.
echo 用法: start.bat [选项]
echo.
echo 选项:
echo   --port ^<端口^>       指定监听端口 (默认: 8765^)
echo   --model-type ^<类型^> 指定模型后端: auto^|ollama^|vllm^|lmstudio
echo   --ollama             快捷使用Ollama后端
echo   --vllm               快捷使用vLLM后端
echo   --lmstudio           快捷使用LM Studio后端
echo   --no-download        禁止自动下载模型
echo   --no-venv            跳过虚拟环境，直接使用系统Python
echo   --help               显示此帮助信息
echo.
echo 示例:
echo   start.bat                                  # 自动检测一切
echo   start.bat --ollama --port 9000             # 用Ollama, 端口9000
echo   start.bat --vllm --no-download             # 用vLLM, 不自动下载
echo   start.bat --model-type ollama --no-venv    # 强制Ollama, 跳过venv
echo.
exit /b 0
