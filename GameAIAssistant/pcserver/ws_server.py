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
                    # 解析注册消息（兼容Android端格式：payload是JSON字符串）
                    payload = msg.get("payload", "{}")
                    if isinstance(payload, str):
                        try:
                            register_data = json.loads(payload)
                        except json.JSONDecodeError:
                            register_data = {}
                    else:
                        register_data = payload if isinstance(payload, dict) else {}
                    
                    device_id = register_data.get("device_id", msg.get("device_id", "unknown"))
                    game_name = register_data.get("game_name", msg.get("game_name", "未知游戏"))
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
                    await self._send_ws(websocket, "match_status", json.dumps({
                        "status": "connected",
                        "detail": "设备已注册"
                    }))

                elif msg_type == "frame":
                    # 接收帧数据（Android端格式：payload是base64字符串）
                    frame_b64 = msg.get("payload", "")
                    await self._handle_frame(device_id, websocket, {
                        "frame": frame_b64,
                        "timestamp": msg.get("timestamp", 0),
                        "match_id": msg.get("match_id", "")
                    })

                elif msg_type == "game_state":
                    # 状态更新（Android端格式：payload是状态字符串）
                    game_state = msg.get("payload", "lobby")
                    await self._handle_status_update(device_id, {"game_state": game_state})

                elif msg_type == "heartbeat":
                    # 心跳（Android端格式）
                    await self._send_ws(websocket, "heartbeat", "pong")

                elif msg_type == "voice":
                    # 语音指令（Android端格式）
                    voice_text = msg.get("payload", "")
                    logger.info(f"[{device_id}] 收到语音指令: {voice_text}")

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
        match_id = msg.get("match_id", "")
        timestamp = msg.get("timestamp", 0)

        # 获取当前对局状态
        match_state = self._match_states.get(device_id, {})
        game_state = match_state.get("phase", "in_game")

        # 控制分析频率（冷却）
        now = time.time()
        last_time = self._last_analysis_time.get(device_id, 0)
        if now - last_time < 3.0:
            # 太快，跳过本帧分析
            return
        self._last_analysis_time[device_id] = now

        # 本地评分引擎计算
        local_result = self.scoring_engine.calculate_from_state(match_state)

        # 按Android端格式发送评分结果
        score_data = {
            "matchId": match_id,
            "totalScore": local_result.get("score", 0),
            "grade": local_result.get("rating", "D"),
            "categories": local_result.get("categories", {}),
            "aiAnalysis": "",
            "aiAdvice": "",
            "timestamp": int(time.time() * 1000)
        }
        await self._send_ws(websocket, "score", json.dumps(score_data, ensure_ascii=False), match_id)

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
                analysis_data = {"text": ai_analysis}
                await self._send_ws(websocket, "analysis", json.dumps(analysis_data, ensure_ascii=False), match_id)

        # 同时发送语音文本（TTS）
        if local_result.get("voice_text"):
            await self._send_ws(websocket, "tts", local_result["voice_text"], match_id)

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

    async def _send_ws(self, websocket, msg_type: str, payload: str = "", match_id: str = ""):
        """按Android端WsMessage格式发送消息"""
        data = {
            "type": msg_type,
            "payload": payload,
            "timestamp": int(time.time() * 1000),
            "match_id": match_id
        }
        try:
            await websocket.send(json.dumps(data, ensure_ascii=False))
        except Exception as e:
            logger.error(f"发送消息失败: {e}")

    async def _send(self, websocket, data: Dict):
        """发送原始JSON消息（向后兼容）"""
        try:
            await websocket.send(json.dumps(data, ensure_ascii=False))
        except Exception as e:
            logger.error(f"发送消息失败: {e}")

    def get_connection_count(self) -> int:
        """获取当前连接数"""
        return len(self._connections)
