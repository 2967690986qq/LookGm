// WebSocketMessage.kt - WebSocket消息模型
package com.gameai.model

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

data class WsMessage(
    @SerializedName("type") val type: String,
    @SerializedName("payload") val payload: String = "",
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis(),
    @SerializedName("match_id") val matchId: String = ""
) {
    companion object {
        private val gson = Gson()

        fun fromJson(json: String): WsMessage = gson.fromJson(json, WsMessage::class.java)
        fun toJson(msg: WsMessage): String = gson.toJson(msg)

        // 消息类型常量
        const val TYPE_REGISTER = "register"           // 注册设备
        const val TYPE_FRAME = "frame"                 // 屏幕帧
        const val TYPE_HEARTBEAT = "heartbeat"         // 心跳
        const val TYPE_SCORE = "score"                 // 评分结果
        const val TYPE_ANALYSIS = "analysis"           // AI分析
        const val TYPE_ADVICE = "advice"               // 操作建议
        const val TYPE_MATCH_STATUS = "match_status"   // 对局状态
        const val TYPE_MATCH_START = "match_start"     // 对局开始
        const val TYPE_MATCH_END = "match_end"         // 对局结束
        const val TYPE_CONFIG = "config"               // 配置同步
        const val TYPE_GAME_STATE = "game_state"     // 游戏状态变化
        const val TYPE_ERROR = "error"                 // 错误
        const val TYPE_VOICE = "voice"                 // 语音指令
        const val TYPE_TTS = "tts"                     // TTS播报
    }
}
