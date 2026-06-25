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

    // 悬浮窗配置
    const val FLOATING_BALL_SIZE_DP = 60
    const val FLOATING_BALL_ALPHA_MIN = 0.3f
    const val FLOATING_BALL_ALPHA_MAX = 1.0f

    // 数据库
    const val DB_NAME = "game_ai.db"
    const val DB_VERSION = 2
}
