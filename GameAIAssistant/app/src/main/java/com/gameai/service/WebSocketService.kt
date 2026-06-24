// WebSocketService.kt - WebSocket通信服务 (完整实现)
package com.gameai.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import com.gameai.model.WsMessage
import com.gameai.model.ScoreResult
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.*
import java.util.concurrent.TimeUnit

class WebSocketService : Service() {

    companion object {
        private var webSocket: WebSocket? = null
        private var isConnected = false
        private var reconnectAttempts = 0
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val RECONNECT_DELAY_MS = 3000L
        private const val HEARTBEAT_INTERVAL_MS = 15000L

        private var host = ""
        private var port = 8765
        private var gameName = ""
        private var deviceId = ""
        private var modelConfigJson = ""

        private val gson = Gson()
        private var scope: CoroutineScope? = null
        private var heartbeatJob: Job? = null

        // 回调
        var onScoreUpdate: ((ScoreResult) -> Unit)? = null
        var onMatchStatus: ((String, String) -> Unit)? = null
        var onConnectionChange: ((Boolean) -> Unit)? = null
        var onTtsText: ((String) -> Unit)? = null

        fun startConnection(context: Context, host: String, port: Int, game: String, modelConfig: String = "") {
            this.host = host
            this.port = port
            this.gameName = game
            this.modelConfigJson = modelConfig
            this.deviceId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
            this.scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            connect()
        }

        fun stopConnection() {
            heartbeatJob?.cancel()
            scope?.cancel()
            webSocket?.close(1000, "User stopped")
            isConnected = false
        }

        fun sendFrame(frameData: ByteArray) {
            if (!isConnected || webSocket == null) return
            try {
                // 使用Base64编码发送
                val base64Frame = Base64.encodeToString(frameData, Base64.NO_WRAP)
                val msg = WsMessage(
                    type = WsMessage.TYPE_FRAME,
                    payload = base64Frame,
                    matchId = ""
                )
                webSocket?.send(WsMessage.toJson(msg))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun sendVoiceCommand(text: String) {
            if (!isConnected) return
            val msg = WsMessage(
                type = WsMessage.TYPE_VOICE,
                payload = text
            )
            webSocket?.send(WsMessage.toJson(msg))
        }

        fun sendGameState(state: String) {
            if (!isConnected) return
            val msg = WsMessage(
                type = WsMessage.TYPE_GAME_STATE,
                payload = state
            )
            webSocket?.send(WsMessage.toJson(msg))
        }

        private fun connect() {
            if (isConnected) return

            val url = "ws://$host:$port/ws"
            val client = OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()

            val request = Request.Builder()
                .url(url)
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    isConnected = true
                    reconnectAttempts = 0
                    onConnectionChange?.invoke(true)

                    // 注册设备（携带模型配置）
                    val registerData = mutableMapOf(
                        "device_id" to deviceId,
                        "game_name" to gameName
                    )
                    if (modelConfigJson.isNotEmpty()) {
                        registerData["model_config"] = modelConfigJson
                    }
                    val registerMsg = WsMessage(
                        type = WsMessage.TYPE_REGISTER,
                        payload = gson.toJson(registerData)
                    )
                    webSocket.send(WsMessage.toJson(registerMsg))

                    // 启动心跳
                    startHeartbeat(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    isConnected = false
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    isConnected = false
                    onConnectionChange?.invoke(false)
                    scheduleReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    isConnected = false
                    onConnectionChange?.invoke(false)
                    scheduleReconnect()
                }
            })
        }

        private fun handleMessage(text: String) {
            try {
                val msg = WsMessage.fromJson(text)
                when (msg.type) {
                    WsMessage.TYPE_SCORE -> {
                        val result = gson.fromJson(msg.payload, ScoreResult::class.java)
                        onScoreUpdate?.invoke(result)
                    }
                    WsMessage.TYPE_ANALYSIS -> {
                        // AI分析结果
                        val result = gson.fromJson<Map<String, String>>(
                            msg.payload,
                            object : TypeToken<Map<String, String>>() {}.type
                        )
                        onTtsText?.invoke(result["text"] ?: "")
                    }
                    WsMessage.TYPE_ADVICE -> {
                        onTtsText?.invoke(msg.payload)
                    }
                    WsMessage.TYPE_MATCH_STATUS -> {
                        val data = gson.fromJson<Map<String, String>>(
                            msg.payload,
                            object : TypeToken<Map<String, String>>() {}.type
                        )
                        onMatchStatus?.invoke(
                            data["status"] ?: "unknown",
                            data["detail"] ?: ""
                        )
                    }
                    WsMessage.TYPE_TTS -> {
                        onTtsText?.invoke(msg.payload)
                    }
                    WsMessage.TYPE_ERROR -> {
                        onTtsText?.invoke("错误: ${msg.payload}")
                    }
                    WsMessage.TYPE_HEARTBEAT -> {
                        // 心跳响应
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        private fun startHeartbeat(ws: WebSocket) {
            heartbeatJob?.cancel()
            heartbeatJob = scope?.launch {
                while (isActive && isConnected) {
                    delay(HEARTBEAT_INTERVAL_MS)
                    try {
                        val heartbeat = WsMessage(type = WsMessage.TYPE_HEARTBEAT, payload = "ping")
                        ws.send(WsMessage.toJson(heartbeat))
                    } catch (e: Exception) {
                        break
                    }
                }
            }
        }

        private fun scheduleReconnect() {
            if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) return

            scope?.launch {
                delay(RECONNECT_DELAY_MS * (reconnectAttempts + 1))
                reconnectAttempts++
                connect()
            }
        }
    }

    // Service 生命周期
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopConnection()
        super.onDestroy()
    }
}
