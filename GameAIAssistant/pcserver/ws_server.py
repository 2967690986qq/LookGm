# ws_server.py - WebSocket帧接收与推理调度
import asyncio
import base64
import json
import logging
import time
from typing import Optional, Dict, Any

logger = logging.getLogger('GameAI.WSServer')

try:
    import websockets
    from websockets.server import WebSocketServerProtocol
except ImportError:
    websockets = None

from scoring_engine import LocalScoringEngine


class GameWSServer:
    """WebSocket服务器：接收手机端帧，调度AI推理，返回分析结果"""

    def __init__(self, host: str, port: int, model_manager=None):
        self.host = host
        self.port = port
        self.model_manager = model_manager
        self.scoring_engine = LocalScoringEngine()
        self._server = None
        self._connections: Dict[str, WebSocketServerProtocol] = {}
        self._match_states: Dict[str, Dict] = {}  # device_id -> match_state
        self._last_analysis_time: Dict[str, float] = {}

    async def start(self):
        """启动WebSocket服务器"""
        if websockets is None:
            raise RuntimeError("请安装websockets: pip install websockets")

        self._server = await websockets.serve(
            self._handle_connection,
            self.host,
            self.port,
            max_size=10 * 1024 * 1024,  # 10MB
            ping_interval=30,
            ping_timeout=10
        )
        logger.info(f"WebSocket服务器启动: ws://{self.host}:{self.port}")

    async def stop(self):
        """停止服务器"""
        if self._server:
            self._server.close()
            await self._server.wait_closed()

    async def _handle_connection(self, websocket: WebSocketServerProtocol):
        """处理单个WebSocket连接"""
        device_id = "unknown"
        try:
            # 等待注册消息
            async for raw_msg in websocket:
                try:
                    msg = json.loads(raw_msg)
                except json.JSONDecodeError:
                    continue

                msg_type = msg.get("type", "")

                if msg_type == "register":
                    device_id = msg.get("device_id", "unknown")
                    game_name = msg.get("game_name", "未知游戏")
                    self._connections[device_id] = websocket
                    self._match_states[device_id] = {
                        "device_id": device_id,
                        "game_name": game_name,
                        "phase": "lobby",
                        "score": 0,
                        "grade": "D",
                        "connected_at": time.time()
                    }
                    logger.info(f"设备连接: {device_id} ({game_name})")
                    await self._send(websocket, {
                        "type": "registered",
                        "device_id": device_id,
                        "model_status": self.model_manager.get_status() if self.model_manager else {"ready": False}
                    })

                elif msg_type == "frame_data":
                    # 接收帧数据
                    await self._handle_frame(device_id, websocket, msg)

                elif msg_type == "game_event":
                    # 游戏事件
                    await self._handle_game_event(device_id, websocket, msg)

                elif msg_type == "status_update":
                    # 状态更新 (game_state: lobby/select/in_game/result)
                    await self._handle_status_update(device_id, msg)

                elif msg_type == "ping":
                    await self._send(websocket, {"type": "pong", "timestamp": int(time.time() * 1000)})

                elif msg_type == "get_models":
                    if self.model_manager:
                        models = self.model_manager.get_models()
                        await self._send(websocket, {
                            "type": "model_list",
                            "models": models,
                            "loaded_model": self.model_manager.loaded_model
                        })

        except websockets.exceptions.ConnectionClosed:
            pass
        except Exception as e:
            logger.error(f"连接处理异常 [{device_id}]: {e}")
        finally:
            if device_id in self._connections:
                del self._connections[device_id]
            if device_id in self._match_states:
                del self._match_states[device_id]
            logger.info(f"设备断开: {device_id}")

    async def _handle_frame(self, device_id: str, websocket, msg: Dict):
        """处理帧数据"""
        frame_b64 = msg.get("frame", "")
        game_state = msg.get("game_state", "in_game")
        timestamp = msg.get("timestamp", 0)

        # 更新对局状态
        if device_id in self._match_states:
            self._match_states[device_id]["phase"] = game_state

        # 控制分析频率（冷却）
        now = time.time()
        last_time = self._last_analysis_time.get(device_id, 0)
        if now - last_time < 3.0:
            # 太快，跳过本帧分析
            return
        self._last_analysis_time[device_id] = now

        # 本地评分引擎计算
        local_result = self.scoring_engine.calculate_from_state(
            self._match_states.get(device_id, {})
        )

        result = {
            "type": "analysis_result",
            "score": local_result.get("score", 0),
            "rating": local_result.get("rating", "D"),
            "game_state": game_state,
            "suggestions": local_result.get("suggestions", []),
            "timestamp": int(time.time() * 1000)
        }

        # 如果有AI模型且帧变化显著，进行深度分析
        if self.model_manager and self.model_manager.is_ready and frame_b64:
            ai_analysis = await self.model_manager.chat_completion(
                messages=[
                    {"role": "system", "content": "你是一个专业游戏分析师。请用中文分析当前游戏画面中的对战情况，给出简洁的战术建议。字数控制在100字以内。"},
                    {"role": "user", "content": "请分析这张游戏截图的当前局势，给出玩家表现评价和改进建议。"}
                ],
                image_base64=frame_b64,
                max_tokens=256,
                temperature=0.5
            )
            if ai_analysis:
                result["ai_analysis"] = ai_analysis

        await self._send(websocket, result)
        # 同时发送语音文本
        if local_result.get("voice_text"):
            await self._send(websocket, {
                "type": "tts",
                "text": local_result["voice_text"]
            })

    async def _handle_game_event(self, device_id: str, websocket, msg: Dict):
        """处理游戏事件"""
        event = msg.get("event", "")
        data = msg.get("data", {})

        match_state = self._match_states.get(device_id, {})

        # 更新事件到评分引擎
        self.scoring_engine.record_event(device_id, event, data)

        # 根据事件类型处理
        if event == "kill":
            match_state["kills"] = match_state.get("kills", 0) + 1
        elif event == "death":
            match_state["deaths"] = match_state.get("deaths", 0) + 1
        elif event == "assist":
            match_state["assists"] = match_state.get("assists", 0) + 1
        elif event == "dragon":
            match_state["objectives"] = match_state.get("objectives", 0) + 1
        elif event == "teamfight_start":
            # 团战开始，提升分析频率
            self._last_analysis_time[device_id] = 0

        # 实时评分
        score_result = self.scoring_engine.calculate_from_state(match_state)
        await self._send(websocket, {
            "type": "score",
            "score": score_result["score"],
            "rating": score_result["rating"],
            "event": event,
            "detail": score_result.get("detail", "")
        })

    async def _handle_status_update(self, device_id: str, msg: Dict):
        """处理状态更新"""
        new_phase = msg.get("game_state", "lobby")
        if device_id in self._match_states:
            old_phase = self._match_states[device_id].get("phase", "")
            self._match_states[device_id]["phase"] = new_phase

            if new_phase == "result" and old_phase == "in_game":
                logger.info(f"[{device_id}] 对局结束，生成总结")
                # 触发对局总结
                match_state = self._match_states[device_id]
                final_score = self.scoring_engine.get_final_score(device_id)
                # 重置
                self.scoring_engine.reset(device_id)
                self._match_states[device_id] = {
                    "device_id": device_id,
                    "game_name": match_state.get("game_name", ""),
                    "phase": "result",
                    "score": final_score["score"],
                    "grade": final_score["rating"]
                }

    async def _send(self, websocket, data: Dict):
        """发送JSON消息"""
        try:
            await websocket.send(json.dumps(data, ensure_ascii=False))
        except Exception as e:
            logger.error(f"发送消息失败: {e}")

    def get_connection_count(self) -> int:
        """获取当前连接数"""
        return len(self._connections)
