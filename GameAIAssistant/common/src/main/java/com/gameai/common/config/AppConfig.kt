// AppConfig.kt - 全局应用配置
package com.gameai.common.config

object AppConfig {
    // WebSocket配置
    const val DEFAULT_WS_HOST = "192.168.1.100"
    const val DEFAULT_WS_PORT = 8765
    const val WS_RECONNECT_DELAY_MS = 3000L
    const val WS_MAX_RECONNECT_ATTEMPTS = 10
    const val WS_HEARTBEAT_INTERVAL_MS = 15000L

    // 屏幕采集配置
    const val CAPTURE_WIDTH = 720
    const val CAPTURE_HEIGHT = 1280
    const val CAPTURE_FPS = 10
    const val CAPTURE_FRAME_INTERVAL_MS = 100L
    const val JPEG_QUALITY = 60

    // 游戏配置
    const val DEFAULT_GAME = "王者荣耀"
    
    // 对局配置
    const val SCORE_UPDATE_INTERVAL_MS = 3000L
    const val MATCH_TIMEOUT_MS = 3600000L // 1小时

    // AI配置
    const val DEFAULT_CLOUD_MODEL = "gpt-4o"
    const val DEFAULT_LOCAL_MODEL = "yolov8n"

    // 语音配置
    const val TTS_SPEECH_RATE = 1.0f
    const val TTS_PITCH = 1.0f

    // ===== SiliconFlow 语音转文字 (STT) 配置 =====
    // 使用 SiliconFlow API 进行语音转文字
    // 模型：FunAudioLLM/SenseVoiceSmall（永久免费，低延迟）
    //      ← 替代 TeleAI/TeleSpeechASR（该模型仅支持批量文件上传，无法实时流式）
    // API 文档: https://api-docs.siliconflow.cn/docs/api/audio-transcriptions-post
    // 接口：POST https://api.siliconflow.cn/v1/audio/transcriptions (multipart/form-data)
    // 注意：SiliconFlow 目前不提供 WebSocket 流式 ASR 端点，
    //       本 APP 通过 1.5s 短分片 HTTP POST 连续调用模拟实时体验。
    const val SILICONFLOW_STT_API_URL = "https://api.siliconflow.cn/v1/audio/transcriptions"
    const val SILICONFLOW_STT_MODEL = "FunAudioLLM/SenseVoiceSmall"
    // 音频采样率（SiliconFlow 要求 16000Hz）
    const val ASR_SAMPLE_RATE = 16000
    // 本地降噪：AEC回声消除（防止 TTS 播报被回采）
    const val ASR_ENABLE_AEC = true
    // 本地降噪：NS环境噪声抑制
    const val ASR_ENABLE_NS = true

    // 悬浮窗配置
    const val FLOATING_BALL_SIZE_DP = 60
    const val FLOATING_BALL_ALPHA_MIN = 0.3f
    const val FLOATING_BALL_ALPHA_MAX = 1.0f

    // 数据库
    const val DB_NAME = "game_ai.db"
    const val DB_VERSION = 2
}
