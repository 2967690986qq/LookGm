# inference.py - AI推理接口封装
import base64
import json
import logging
from typing import Optional, Dict, Any, List

logger = logging.getLogger('GameAI.Inference')

try:
    import aiohttp
except ImportError:
    aiohttp = None


class InferenceEngine:
    """统一推理接口：支持Ollama、vLLM、LM Studio、OpenAI等多后端"""

    # 游戏分析提示词模板
    GAME_ANALYSIS_PROMPT = """你是一个专业的{game_name}游戏分析师。请根据这张游戏截图，分析当前局势：

1. **当前阶段**：识别当前处于哪个游戏阶段（选人/对线/团战/后期等）
2. **玩家表现**：评价玩家当前的表现（KDA、位置、装备等可视信息）
3. **战术建议**：给出1-2条当前最优的战术行动建议
4. **评分预估**：预估当前玩家表现评分（0-100分）

请用中文简洁回复，控制在200字以内。只分析可见信息，不猜测hidden信息。"""

    MATCH_SUMMARY_PROMPT = """你是{game_name}游戏分析师。请根据本局数据生成对局总结：

本局数据：
- 英雄：{hero}
- 位置：{position}
- KDA：{kills}/{deaths}/{assists}
- 经济：{gpm} GPM
- 参团率：{participation}%
- 最终评分：{score}分 ({grade})

请分析：
1. 本局整体表现评价（1句话）
2. 最大亮点（1个）
3. 最大短板（1个）
4. 下局改进建议（1条）

控制在150字以内。"""

    def __init__(self, base_url: str = "", api_key: str = "", model: str = ""):
        self.base_url = base_url
        self.api_key = api_key
        self.model = model

    async def analyze_game_frame(self, image_b64: str, game_name: str = "王者荣耀",
                                  context: Optional[str] = None) -> Optional[str]:
        """分析游戏帧画面"""
        prompt = self.GAME_ANALYSIS_PROMPT.format(game_name=game_name)
        if context:
            prompt = f"上轮分析：{context}\n\n{prompt}"

        return await self._call_vision_api(image_b64, prompt)

    async def generate_match_summary(self, match_data: Dict) -> Optional[str]:
        """生成对局总结"""
        prompt = self.MATCH_SUMMARY_PROMPT.format(
            game_name=match_data.get("game_name", "王者荣耀"),
            hero=match_data.get("hero", "未知"),
            position=match_data.get("position", "未知"),
            kills=match_data.get("kills", 0),
            deaths=match_data.get("deaths", 0),
            assists=match_data.get("assists", 0),
            gpm=match_data.get("gpm", 0),
            participation=int(match_data.get("participation", 0) * 100),
            score=match_data.get("score", 0),
            grade=match_data.get("grade", "D")
        )

        return await self._call_chat_api([
            {"role": "user", "content": prompt}
        ])

    async def _call_vision_api(self, image_b64: str, prompt: str) -> Optional[str]:
        """调用视觉API"""
        if not aiohttp or not self.base_url:
            return None

        payload = {
            "model": self.model,
            "messages": [{
                "role": "user",
                "content": [
                    {"type": "text", "text": prompt},
                    {"type": "image_url", "image_url": {
                        "url": f"data:image/jpeg;base64,{image_b64}"
                    }}
                ]
            }],
            "max_tokens": 500,
            "temperature": 0.7
        }

        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"

        try:
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    f"{self.base_url}/chat/completions",
                    json=payload,
                    headers=headers,
                    timeout=aiohttp.ClientTimeout(total=30)
                ) as resp:
                    if resp.status == 200:
                        data = await resp.json()
                        choices = data.get("choices", [])
                        if choices:
                            return choices[0].get("message", {}).get("content", "")
        except Exception as e:
            logger.error(f"视觉API调用失败: {e}")
        return None

    async def _call_chat_api(self, messages: List[Dict]) -> Optional[str]:
        """调用对话API"""
        if not aiohttp or not self.base_url:
            return None

        payload = {
            "model": self.model,
            "messages": messages,
            "max_tokens": 300,
            "temperature": 0.7
        }

        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"

        try:
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    f"{self.base_url}/chat/completions",
                    json=payload,
                    headers=headers,
                    timeout=aiohttp.ClientTimeout(total=20)
                ) as resp:
                    if resp.status == 200:
                        data = await resp.json()
                        choices = data.get("choices", [])
                        if choices:
                            return choices[0].get("message", {}).get("content", "")
        except Exception as e:
            logger.error(f"对话API调用失败: {e}")
        return None
