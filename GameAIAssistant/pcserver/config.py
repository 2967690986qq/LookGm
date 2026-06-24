# config.py - 配置管理
import os
import sys
import subprocess
from pathlib import Path
from typing import Tuple

# 项目根目录
ROOT_DIR = Path(__file__).parent

# 模型存储目录
MODELS_DIR = ROOT_DIR / "models"
MODELS_DIR.mkdir(exist_ok=True)

# 默认端口
DEFAULT_PORT = 8765

# 支持的模型列表（自动检测优先级）
SUPPORTED_MODELS = {
    "ollama": {
        "check_cmd": ["ollama", "list"],
        "default_model": "qwen2.5:7b",
        "models": ["qwen2.5:7b", "qwen2.5:14b", "llama3.2-vision:11b", "minicpm-v:8b"]
    },
    "vllm": {
        "check_cmd": None,  # 通过HTTP检查
        "default_model": "Qwen2.5-VL-7B-Instruct",
        "check_url": "http://127.0.0.1:8000/v1/models",
        "models": ["Qwen2.5-VL-7B-Instruct", "Qwen2-VL-7B-Instruct", "llava-v1.6-7b"]
    },
    "lmstudio": {
        "check_cmd": None,
        "default_model": "local-model",
        "check_url": "http://127.0.0.1:1234/v1/models",
        "models": ["local-model"]
    },
    "openai": {
        "check_cmd": None,
        "default_model": "gpt-4o",
        "check_url": "https://api.openai.com/v1/models",
        "models": ["gpt-4o", "gpt-4o-mini", "gpt-4-turbo"]
    }
}

# 评分引擎配置
SCORING_CONFIG = {
    "min_frame_interval": 1.0,     # 最小分析间隔（秒）
    "teamfight_boost_fps": 15,     # 团战状态推帧率
    "idle_fps": 2,                 # 静止状态推帧率
    "analysis_cooldown": 3.0,      # AI分析冷却时间（秒）
    "max_context_frames": 5,       # 上下文窗口帧数
}


def check_environment() -> Tuple[bool, str]:
    """检查运行环境"""
    issues = []

    # 检查Python版本
    py_ver = sys.version_info
    if py_ver < (3, 10):
        issues.append(f"Python版本过低: {py_ver.major}.{py_ver.minor} (需要 ≥ 3.10)")

    # 检查pip包
    try:
        import fastapi
        import websockets
        import uvicorn
    except ImportError as e:
        issues.append(f"缺少依赖包: {e}. 请运行: pip install -r requirements.txt")

    if issues:
        return False, "; ".join(issues)
    return True, "环境正常"


def get_local_ip() -> str:
    """获取本机局域网IP"""
    import socket
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"
