@echo off
chcp 65001 >nul
echo ============================================================
echo   DeepSeek-OCR 本地部署启动脚本（vLLM + WebSocket中转）
echo ============================================================
echo.

echo [1/3] 检查 vLLM 是否已安装...
python -c "import vllm" 2>nul
if errorlevel 1 (
    echo [!] vLLM 未安装，正在安装...
    pip install vllm
    if errorlevel 1 (
        echo [!] vLLM 安装失败，请手动安装
        pause
        exit /b 1
    )
)
echo [√] vLLM 已安装
echo.

echo [2/3] 检查 WebSocket 依赖...
python -c "import websockets" 2>nul
if errorlevel 1 (
    echo [!] websockets 未安装，正在安装...
    pip install websockets pillow requests
)
echo [√] 依赖已就绪
echo.

echo [3/3] 启动 vLLM + DeepSeek-OCR...
echo.
echo 提示: 请确保您有足够的GPU显存（>= 8GB推荐）
echo.

start "vLLM-DeepSeek-OCR" cmd /k ^
    "python -m vllm.entrypoints.openai.api_server ^
     --model deepseek-ai/DeepSeek-OCR ^
     --host 0.0.0.0 ^
     --port 8000 ^
     --trust-remote-code ^
     --max-model-len 4096"

echo [*] 等待 vLLM 模型加载完成（首次下载可能需要几分钟）...
timeout /t 30 /nobreak

echo.
echo [*] 启动 WebSocket 中转服务...
python "%~dp0ocr_websocket_server.py" --host 0.0.0.0 --port 8765

pause
