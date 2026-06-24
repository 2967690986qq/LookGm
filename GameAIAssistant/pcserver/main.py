# GameAI PC Server - 全游戏通用AI视觉助手服务端
# 用法: python main.py --host 0.0.0.0 --port 8765

import argparse
import asyncio
import json
import logging
import os
import sys
from pathlib import Path

# 设置项目根目录
ROOT_DIR = Path(__file__).parent
sys.path.insert(0, str(ROOT_DIR))

# 配置日志
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(name)s: %(message)s',
    handlers=[
        logging.StreamHandler(),
        logging.FileHandler(ROOT_DIR / 'server.log', encoding='utf-8')
    ]
)
logger = logging.getLogger('GameAI.Server')


def parse_args():
    parser = argparse.ArgumentParser(description='GameAI PC Server')
    parser.add_argument('--host', type=str, default='0.0.0.0', help='监听地址')
    parser.add_argument('--port', type=int, default=8765, help='监听端口')
    parser.add_argument('--model', type=str, default='auto', help='默认模型名称 (auto=自动检测)')
    parser.add_argument('--model-type', type=str, default='auto',
                        choices=['auto', 'ollama', 'vllm', 'lmstudio', 'openai'],
                        help='模型服务类型')
    parser.add_argument('--no-auto-download', action='store_true', help='禁止自动下载模型')
    return parser.parse_args()


def print_banner(host: str, port: int):
    """打印启动横幅"""
    import socket
    local_ip = "127.0.0.1"
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        local_ip = s.getsockname()[0]
        s.close()
    except Exception:
        pass

    banner = f"""
╔══════════════════════════════════════════════════╗
║       🎮 全游戏通用AI视觉助手 - PC服务端        ║
╠══════════════════════════════════════════════════╣
║  本地地址: ws://{local_ip}:{port}/ws/game_stream   ║
║  HTTP API: http://{local_ip}:{port}/health         ║
╠══════════════════════════════════════════════════╣
║  手机端配置:                                      ║
║  → IP地址: {local_ip:<37}║
║  → 端口: {port:<39}║
╚══════════════════════════════════════════════════╝
"""
    print(banner)
    return local_ip


async def main():
    args = parse_args()

    # 检查环境
    from config import check_environment
    env_ok, env_msg = check_environment()
    if not env_ok:
        logger.warning(f"环境检查: {env_msg}")
    else:
        logger.info(f"环境检查: {env_msg}")

    # 初始化模型管理器
    from model_manager import ModelManager
    model_mgr = ModelManager(
        model_name=args.model,
        model_type=args.model_type,
        auto_download=not args.no_auto_download
    )

    # 尝试加载模型
    model_ready = await model_mgr.initialize()
    if not model_ready:
        logger.warning("模型未就绪，服务器将以降级模式运行（仅本地评分引擎可用）")

    # 启动WebSocket服务器
    from ws_server import GameWSServer
    ws_server = GameWSServer(
        host=args.host,
        port=args.port,
        model_manager=model_mgr
    )

    local_ip = print_banner(args.host, args.port)

    try:
        await ws_server.start()
        logger.info(f"服务器已启动: ws://{local_ip}:{args.port}")

        # 保持运行
        while True:
            await asyncio.sleep(1)

    except KeyboardInterrupt:
        logger.info("收到退出信号，正在关闭...")
    except Exception as e:
        logger.error(f"服务器异常: {e}", exc_info=True)
    finally:
        await ws_server.stop()
        logger.info("服务器已关闭")


if __name__ == '__main__':
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
    except Exception as e:
        logger.error(f"启动失败: {e}")
        sys.exit(1)
