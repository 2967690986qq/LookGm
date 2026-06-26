#!/usr/bin/env python3
"""
DeepSeek-OCR WebSocket 中转服务
用于局域网内手机端与本地OCR模型通信

功能：
1. 接收手机端 WebSocket 二进制帧（JPEG图片）
2. 调用 DeepSeek-OCR 模型进行识别
3. 返回结构化 JSON 结果

启动方式：
    python ocr_websocket_server.py --host 0.0.0.0 --port 8765 --model deepseek-ai/DeepSeek-OCR

依赖：
    pip install websockets pillow requests
"""

import asyncio
import websockets
import json
import base64
import io
import argparse
import time
from PIL import Image
from pathlib import Path

# 配置
CONFIG = {
    "vllm_base_url": "http://localhost:8000",
    "model_name": "deepseek-ai/DeepSeek-OCR",
    "max_queue_size": 5,
    "request_timeout": 30,
}

# 全局状态
stats = {
    "total_requests": 0,
    "success_count": 0,
    "fail_count": 0,
    "avg_time_ms": 0,
    "start_time": time.time(),
}

request_queue = asyncio.Queue(maxsize=CONFIG["max_queue_size"])


def call_vllm_ocr(image_bytes: bytes) -> dict:
    """
    调用 vLLM 部署的 DeepSeek-OCR 模型
    使用 OpenAI 兼容的 API
    """
    import requests

    base64_image = base64.b64encode(image_bytes).decode("utf-8")

    payload = {
        "model": CONFIG["model_name"],
        "messages": [
            {
                "role": "user",
                "content": [
                    {
                        "type": "text",
                        "text": """请仔细分析这张王者荣耀游戏截图，提取以下所有可识别的数据，用JSON格式输出：

要求输出格式：
{
  "game_phase": "in_game",  // lobby, draft, in_game, result
  "hero_name": "",
  "position": "",  // 对抗路/中路/发育路/游走/打野
  "kills": 0,
  "deaths": 0,
  "assists": 0,
  "gold": 0,
  "gold_per_min": 0,
  "damage_dealt": 0,
  "damage_dealt_percent": 0,
  "damage_taken": 0,
  "damage_taken_percent": 0,
  "participation_rate": 0,
  "towers": 0,
  "dragons": 0,
  "barons": 0,
  "vision_percent": 0,
  "cc_duration": 0,
  "healing": 0,
  "game_time_sec": 0,
  "raw_text": ""
}

只输出JSON，不要有其他文字说明。"""
                    },
                    {
                        "type": "image_url",
                        "image_url": {
                            "url": f"data:image/jpeg;base64,{base64_image}"
                        }
                    }
                ]
            }
        ],
        "max_tokens": 2000,
        "temperature": 0.1,
        "stream": False
    }

    try:
        response = requests.post(
            f"{CONFIG['vllm_base_url']}/v1/chat/completions",
            json=payload,
            timeout=CONFIG["request_timeout"]
        )
        response.raise_for_status()
        result = response.json()

        content = result["choices"][0]["message"]["content"]

        # 尝试从内容中提取JSON
        json_str = extract_json(content)
        if json_str:
            parsed = json.loads(json_str)
            parsed["raw_text"] = content
            return {
                "success": True,
                "data": parsed,
                "raw_content": content
            }
        else:
            return {
                "success": True,
                "data": {"raw_text": content},
                "raw_content": content
            }

    except Exception as e:
        return {
            "success": False,
            "error": str(e)
        }


def extract_json(text: str) -> str:
    """从文本中提取JSON字符串"""
    # 尝试找到第一个 { 和最后一个 }
    start = text.find("{")
    end = text.rfind("}")
    if start != -1 and end != -1 and end > start:
        return text[start:end + 1]
    return None


async def process_request(websocket, image_bytes: bytes):
    """处理单个OCR请求"""
    start_time = time.time()
    stats["total_requests"] += 1

    try:
        # 在线程池中执行同步的HTTP请求
        loop = asyncio.get_event_loop()
        result = await loop.run_in_executor(
            None,
            call_vllm_ocr,
            image_bytes
        )

        elapsed = (time.time() - start_time) * 1000

        if result["success"]:
            stats["success_count"] += 1
            # 更新平均耗时
            stats["avg_time_ms"] = (
                stats["avg_time_ms"] * (stats["success_count"] - 1) + elapsed
            ) / stats["success_count"]

            response = {
                "type": "ocr_result",
                "success": True,
                "data": result["data"],
                "elapsed_ms": round(elapsed, 1)
            }
        else:
            stats["fail_count"] += 1
            response = {
                "type": "ocr_result",
                "success": False,
                "error": result["error"],
                "elapsed_ms": round(elapsed, 1)
            }

        await websocket.send(json.dumps(response, ensure_ascii=False))

    except Exception as e:
        stats["fail_count"] += 1
        elapsed = (time.time() - start_time) * 1000
        error_response = {
            "type": "ocr_result",
            "success": False,
            "error": str(e),
            "elapsed_ms": round(elapsed, 1)
        }
        try:
            await websocket.send(json.dumps(error_response, ensure_ascii=False))
        except:
            pass


async def handle_client(websocket):
    """处理客户端连接"""
    client_addr = websocket.remote_address
    print(f"[+] 新客户端连接: {client_addr}")

    try:
        # 发送欢迎消息
        await websocket.send(json.dumps({
            "type": "welcome",
            "model": CONFIG["model_name"],
            "status": "ready"
        }))

        async for message in websocket:
            try:
                if isinstance(message, str):
                    # 文本消息（控制命令）
                    data = json.loads(message)
                    msg_type = data.get("type", "")

                    if msg_type == "ping":
                        await websocket.send(json.dumps({"type": "pong"}))

                    elif msg_type == "get_stats":
                        uptime = time.time() - stats["start_time"]
                        await websocket.send(json.dumps({
                            "type": "stats",
                            "total_requests": stats["total_requests"],
                            "success_count": stats["success_count"],
                            "fail_count": stats["fail_count"],
                            "avg_time_ms": round(stats["avg_time_ms"], 1),
                            "uptime_sec": round(uptime, 1),
                            "queue_size": request_queue.qsize()
                        }))

                elif isinstance(message, bytes):
                    # 二进制消息（图片数据）
                    if request_queue.full():
                        # 队列满，丢弃最旧的请求
                        try:
                            request_queue.get_nowait()
                        except:
                            pass

                    await request_queue.put((websocket, message))

            except json.JSONDecodeError:
                print(f"[!] 无效的JSON消息")
            except Exception as e:
                print(f"[!] 消息处理错误: {e}")

    except websockets.exceptions.ConnectionClosed:
        print(f"[-] 客户端断开: {client_addr}")
    except Exception as e:
        print(f"[!] 连接错误: {e}")


async def ocr_worker():
    """OCR工作协程，从队列中取请求处理"""
    print("[*] OCR工作协程已启动")
    while True:
        try:
            websocket, image_bytes = await request_queue.get()
            await process_request(websocket, image_bytes)
        except asyncio.CancelledError:
            break
        except Exception as e:
            print(f"[!] OCR工作协程错误: {e}")
            await asyncio.sleep(1)


async def main():
    parser = argparse.ArgumentParser(description="DeepSeek-OCR WebSocket 中转服务")
    parser.add_argument("--host", default="0.0.0.0", help="监听地址")
    parser.add_argument("--port", type=int, default=8765, help="监听端口")
    parser.add_argument("--vllm-url", default="http://localhost:8000", help="vLLM API地址")
    parser.add_argument("--model", default="deepseek-ai/DeepSeek-OCR", help="模型名称")
    parser.add_argument("--workers", type=int, default=2, help="并发工作数")

    args = parser.parse_args()

    CONFIG["vllm_base_url"] = args.vllm_url.rstrip("/")
    CONFIG["model_name"] = args.model

    print("=" * 60)
    print("  DeepSeek-OCR WebSocket 中转服务")
    print("=" * 60)
    print(f"  监听地址: {args.host}:{args.port}")
    print(f"  vLLM地址: {CONFIG['vllm_base_url']}")
    print(f"  模型名称: {CONFIG['model_name']}")
    print(f"  工作线程: {args.workers}")
    print("=" * 60)
    print()

    # 启动OCR工作协程
    workers = []
    for i in range(args.workers):
        worker = asyncio.create_task(ocr_worker())
        workers.append(worker)

    # 启动WebSocket服务器
    print(f"[*] WebSocket服务器启动中...")
    async with websockets.serve(
        handle_client,
        args.host,
        args.port,
        max_size=10 * 1024 * 1024,  # 最大10MB消息
        ping_interval=30,
        ping_timeout=10
    ):
        print(f"[+] 服务器已启动: ws://{args.host}:{args.port}")
        print("[*] 等待客户端连接...")
        print()

        # 保持运行
        await asyncio.Future()

    # 清理
    for w in workers:
        w.cancel()
    await asyncio.gather(*workers, return_exceptions=True)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n[*] 服务器停止")
