# model_manager.py - 模型加载与切换管理
import asyncio
import json
import logging
import subprocess
from enum import Enum
from pathlib import Path
from typing import Optional, List, Dict, Any

logger = logging.getLogger('GameAI.ModelManager')

try:
    import aiohttp
except ImportError:
    aiohttp = None


class ModelBackend(Enum):
    OLLAMA = "ollama"
    VLLM = "vllm"
    LMSTUDIO = "lmstudio"
    OPENAI = "openai"
    NONE = "none"


class ModelManager:
    """管理本地/云端模型加载、切换和推理"""

    def __init__(self, model_name: str = "auto", model_type: str = "auto",
                 auto_download: bool = False):
        self.model_name = model_name
        self.model_type = model_type
        self.auto_download = auto_download
        self.backend = ModelBackend.NONE
        self.loaded_model = None
        self.base_url = None
        self.is_ready = False
        self._models_cache: List[str] = []

    async def initialize(self) -> bool:
        """自动检测并初始化模型"""
        if self.model_type != "auto":
            # 用户指定了类型
            backend = ModelBackend(self.model_type)
            success = await self._try_backend(backend)
            if success:
                return True
            logger.warning(f"指定的模型类型 {self.model_type} 不可用")

        # 自动检测
        detection_order = [ModelBackend.OLLAMA, ModelBackend.VLLM, ModelBackend.LMSTUDIO]
        for backend in detection_order:
            if await self._check_backend_available(backend):
                success = await self._try_backend(backend)
                if success:
                    return True

        logger.info("未检测到本地模型服务，将使用降级评分引擎")
        self.backend = ModelBackend.NONE
        return False

    async def _check_backend_available(self, backend: ModelBackend) -> bool:
        """检查后端是否可用"""
        try:
            if backend == ModelBackend.OLLAMA:
                result = subprocess.run(
                    ["ollama", "list"], capture_output=True, text=True, timeout=5
                )
                return result.returncode == 0
            elif backend == ModelBackend.VLLM:
                if aiohttp:
                    async with aiohttp.ClientSession() as session:
                        async with session.get("http://127.0.0.1:8000/health", timeout=aiohttp.ClientTimeout(total=3)) as resp:
                            return resp.status == 200
            elif backend == ModelBackend.LMSTUDIO:
                if aiohttp:
                    async with aiohttp.ClientSession() as session:
                        async with session.get("http://127.0.0.1:1234/v1/models", timeout=aiohttp.ClientTimeout(total=3)) as resp:
                            return resp.status == 200
        except Exception:
            pass
        return False

    async def _try_backend(self, backend: ModelBackend) -> bool:
        """尝试使用指定后端加载模型"""
        try:
            if backend == ModelBackend.OLLAMA:
                return await self._init_ollama()
            elif backend == ModelBackend.VLLM:
                return await self._init_vllm()
            elif backend == ModelBackend.LMSTUDIO:
                return await self._init_lmstudio()
        except Exception as e:
            logger.error(f"初始化 {backend.value} 失败: {e}")
        return False

    async def _init_ollama(self) -> bool:
        """初始化Ollama后端"""
        self.base_url = "http://127.0.0.1:11434"
        try:
            # 获取已安装模型列表
            result = subprocess.run(
                ["ollama", "list"], capture_output=True, text=True, timeout=10
            )
            if result.returncode != 0:
                return False

            models = []
            for line in result.stdout.strip().split('\n')[1:]:  # 跳过表头
                parts = line.split()
                if parts:
                    models.append(parts[0])

            self._models_cache = models

            if self.model_name != "auto" and self.model_name in models:
                self.loaded_model = self.model_name
            elif models:
                # 优先选择多模态模型
                vision_models = [m for m in models if any(
                    v in m.lower() for v in ['vision', 'vl', 'llava', 'minicpm']
                )]
                self.loaded_model = vision_models[0] if vision_models else models[0]
            else:
                self.loaded_model = "qwen2.5:7b"
                if self.auto_download:
                    logger.info(f"自动下载模型: {self.loaded_model}")
                    subprocess.run(["ollama", "pull", self.loaded_model], check=False)

            self.backend = ModelBackend.OLLAMA
            self.is_ready = True
            logger.info(f"Ollama就绪: {self.loaded_model}")
            return True
        except Exception as e:
            logger.error(f"Ollama初始化失败: {e}")
            return False

    async def _init_vllm(self) -> bool:
        """初始化vLLM后端"""
        self.base_url = "http://127.0.0.1:8000/v1"
        try:
            if aiohttp:
                async with aiohttp.ClientSession() as session:
                    async with session.get(f"{self.base_url}/models", timeout=aiohttp.ClientTimeout(total=5)) as resp:
                        if resp.status == 200:
                            data = await resp.json()
                            models = [m.get("id", "") for m in data.get("data", [])]
                            self._models_cache = models
                            self.loaded_model = models[0] if models else "Qwen2.5-VL-7B-Instruct"
                            self.backend = ModelBackend.VLLM
                            self.is_ready = True
                            logger.info(f"vLLM就绪: {self.loaded_model}")
                            return True
        except Exception as e:
            logger.error(f"vLLM初始化失败: {e}")
        return False

    async def _init_lmstudio(self) -> bool:
        """初始化LM Studio后端"""
        self.base_url = "http://127.0.0.1:1234/v1"
        try:
            if aiohttp:
                async with aiohttp.ClientSession() as session:
                    async with session.get(f"{self.base_url}/models", timeout=aiohttp.ClientTimeout(total=5)) as resp:
                        if resp.status == 200:
                            data = await resp.json()
                            models = [m.get("id", "") for m in data.get("data", [])]
                            self._models_cache = models
                            self.loaded_model = models[0] if models else "local-model"
                            self.backend = ModelBackend.LMSTUDIO
                            self.is_ready = True
                            logger.info(f"LM Studio就绪: {self.loaded_model}")
                            return True
        except Exception as e:
            logger.error(f"LM Studio初始化失败: {e}")
        return False

    def get_models(self) -> List[str]:
        """获取可用模型列表"""
        return self._models_cache

    async def chat_completion(self, messages: List[Dict], image_base64: Optional[str] = None,
                              max_tokens: int = 1024, temperature: float = 0.7) -> Optional[str]:
        """调用模型进行对话补全"""
        if not self.is_ready or not self.base_url:
            return None

        try:
            if self.backend == ModelBackend.OLLAMA:
                return await self._ollama_chat(messages, image_base64, max_tokens, temperature)
            else:
                return await self._openai_compatible_chat(messages, image_base64, max_tokens, temperature)
        except Exception as e:
            logger.error(f"模型推理失败: {e}")
            return None

    async def _ollama_chat(self, messages: List[Dict], image_base64: Optional[str],
                           max_tokens: int, temperature: float) -> Optional[str]:
        """Ollama Chat API"""
        if not aiohttp:
            return None

        payload = {
            "model": self.loaded_model,
            "messages": messages,
            "stream": False,
            "options": {
                "num_predict": max_tokens,
                "temperature": temperature
            }
        }
        if image_base64:
            # Ollama vision格式：将图片放入最后一条消息的images字段
            for msg in reversed(payload["messages"]):
                if msg["role"] == "user":
                    msg["images"] = [image_base64]
                    break

        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"{self.base_url}/api/chat",
                json=payload,
                timeout=aiohttp.ClientTimeout(total=60)
            ) as resp:
                if resp.status == 200:
                    data = await resp.json()
                    return data.get("message", {}).get("content", "")
        return None

    async def _openai_compatible_chat(self, messages: List[Dict], image_base64: Optional[str],
                                       max_tokens: int, temperature: float) -> Optional[str]:
        """OpenAI兼容API（vLLM/LM Studio均适用）"""
        if not aiohttp:
            return None

        payload = {
            "model": self.loaded_model,
            "messages": messages,
            "max_tokens": max_tokens,
            "temperature": temperature
        }
        if image_base64:
            # 多模态格式
            for msg in payload["messages"]:
                if msg["role"] == "user":
                    msg["content"] = [
                        {"type": "text", "text": msg.get("content", "分析这张游戏截图")},
                        {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{image_base64}"}}
                    ]
                    break

        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"{self.base_url}/chat/completions",
                json=payload,
                timeout=aiohttp.ClientTimeout(total=60)
            ) as resp:
                if resp.status == 200:
                    data = await resp.json()
                    choices = data.get("choices", [])
                    if choices:
                        return choices[0].get("message", {}).get("content", "")
        return None

    def get_status(self) -> Dict[str, Any]:
        """获取模型状态"""
        return {
            "backend": self.backend.value,
            "model": self.loaded_model,
            "ready": self.is_ready,
            "base_url": self.base_url,
            "available_models": self._models_cache
        }
