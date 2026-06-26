package com.gameai.ai

import android.content.Context
import android.graphics.Bitmap
import com.gameai.utils.PreferencesManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 本地WebSocket OCR客户端
 * 用于与局域网PC上的DeepSeek-OCR服务通信
 */
class LocalOcrClient(private val context: Context) {

    companion object {
        const val STATUS_DISCONNECTED = "disconnected"
        const val STATUS_CONNECTING = "connecting"
        const val STATUS_CONNECTED = "connected"
        const val STATUS_ERROR = "error"

        @Volatile private var instance: LocalOcrClient? = null
        fun init(ctx: Context) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = LocalOcrClient(ctx.applicationContext)
                    }
                }
            }
        }
        fun getInstance(): LocalOcrClient = instance
            ?: throw IllegalStateException("LocalOcrClient not initialized")
        fun getOrNull(): LocalOcrClient? = instance
    }

    // 状态
    private val _status = MutableStateFlow(STATUS_DISCONNECTED)
    val status: StateFlow<String> = _status.asStateFlow()

    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null
    private var scope: CoroutineScope? = null

    // 响应回调
    private val pendingRequests = mutableMapOf<String, CompletableDeferred<OcrStructuredResult?>>()
    private var requestId = 0

    // 配置
    private var serverHost: String = "192.168.1.100"
    private var serverPort: Int = 8765

    /**
     * 连接到OCR服务器
     */
    fun connect(host: String? = null, port: Int? = null) {
        host?.let { serverHost = it }
        port?.let { serverPort = it }

        if (_status.value == STATUS_CONNECTING || _status.value == STATUS_CONNECTED) {
            android.util.Log.d("LocalOcr", "已连接或连接中，跳过")
            return
        }

        _status.value = STATUS_CONNECTING
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        android.util.Log.d("LocalOcr", "🔌 连接到 OCR 服务器: ws://$serverHost:$serverPort")

        client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url("ws://$serverHost:$serverPort")
            .build()

        webSocket = client?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: okhttp3.Response) {
                android.util.Log.d("LocalOcr", "✅ WebSocket 连接成功")
                _status.value = STATUS_CONNECTED
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleTextMessage(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                android.util.Log.d("LocalOcr", "🔌 WebSocket 关闭: $code $reason")
                _status.value = STATUS_DISCONNECTED
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: okhttp3.Response?) {
                android.util.Log.e("LocalOcr", "❌ WebSocket 错误", t)
                _status.value = STATUS_ERROR
                // 失败的请求都返回null
                pendingRequests.values.forEach { it.complete(null) }
                pendingRequests.clear()
            }
        })
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        android.util.Log.d("LocalOcr", "⏹️ 断开 OCR 服务器连接")
        webSocket?.close(1000, "client disconnect")
        webSocket = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
        scope?.cancel()
        scope = null
        _status.value = STATUS_DISCONNECTED

        pendingRequests.values.forEach { it.complete(null) }
        pendingRequests.clear()
    }

    /**
     * 发送图片进行OCR识别
     */
    suspend fun recognize(bitmap: Bitmap): OcrStructuredResult? {
        if (_status.value != STATUS_CONNECTED) {
            android.util.Log.w("LocalOcr", "未连接，跳过OCR")
            return null
        }

        val id = (++requestId).toString()
        val deferred = CompletableDeferred<OcrStructuredResult?>()
        pendingRequests[id] = deferred

        return try {
            // 压缩图片
            val bytes = compressBitmap(bitmap)
            android.util.Log.d("LocalOcr", "📤 发送图片: ${bytes.size} bytes")

            // 发送二进制帧
            webSocket?.send(ByteString.of(*bytes))
                ?: throw IllegalStateException("WebSocket 不可用")

            // 等待响应（超时30秒）
            withTimeout(30000) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            android.util.Log.w("LocalOcr", "⏰ OCR 请求超时")
            pendingRequests.remove(id)
            null
        } catch (e: Exception) {
            android.util.Log.e("LocalOcr", "❌ OCR 请求失败", e)
            pendingRequests.remove(id)
            null
        }
    }

    /**
     * 处理文本消息
     */
    private fun handleTextMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "ocr_result" -> handleOcrResult(json)
                "welcome" -> {
                    android.util.Log.d("LocalOcr", "👋 服务器欢迎消息: ${json.optString("model")}")
                }
                "pong" -> {}
                "stats" -> {}
            }
        } catch (e: Exception) {
            android.util.Log.e("LocalOcr", "解析消息失败", e)
        }
    }

    /**
     * 处理OCR结果
     */
    private fun handleOcrResult(json: JSONObject) {
        val success = json.optBoolean("success", false)
        val elapsed = json.optDouble("elapsed_ms", 0.0)

        android.util.Log.d("LocalOcr", "📥 OCR结果: success=$success, 耗时=${elapsed}ms")

        if (success) {
            val data = json.optJSONObject("data")
            if (data != null) {
                val result = parseOcrData(data)
                // 完成第一个等待的请求（简化：按顺序完成）
                completeFirstRequest(result)
            } else {
                completeFirstRequest(null)
            }
        } else {
            val error = json.optString("error", "unknown")
            android.util.Log.e("LocalOcr", "OCR失败: $error")
            completeFirstRequest(null)
        }
    }

    /**
     * 解析OCR数据为结构化结果
     */
    private fun parseOcrData(data: JSONObject): OcrStructuredResult {
        val result = OcrStructuredResult()

        result.rawText = data.optString("raw_text", "")
        result.kills = data.optInt("kills", 0)
        result.deaths = data.optInt("deaths", 0)
        result.assists = data.optInt("assists", 0)
        result.gold = data.optInt("gold", 0)
        result.goldPerMin = data.optInt("gold_per_min", 0)
        result.damageDealt = data.optInt("damage_dealt", 0)
        result.damageDealtPercent = data.optDouble("damage_dealt_percent", 0.0).toFloat()
        result.damageTaken = data.optInt("damage_taken", 0)
        result.damageTakenPercent = data.optDouble("damage_taken_percent", 0.0).toFloat()
        result.participationRate = data.optDouble("participation_rate", 0.0).toFloat()
        result.towers = data.optInt("towers", 0)
        result.dragons = data.optInt("dragons", 0)
        result.barons = data.optInt("barons", 0)
        result.visionPercent = data.optDouble("vision_percent", 0.0).toFloat()
        result.ccDuration = data.optDouble("cc_duration", 0.0).toFloat()
        result.healing = data.optInt("healing", 0)
        result.gameTimeSec = data.optInt("game_time_sec", 0)
        result.heroName = data.optString("hero_name", "")

        // 游戏状态
        result.gamePhase = when (data.optString("game_phase", "")) {
            "lobby" -> com.gameai.common.constants.GameConstants.MatchPhase.LOBBY
            "draft", "hero_select" -> com.gameai.common.constants.GameConstants.MatchPhase.HERO_SELECT
            "in_game" -> com.gameai.common.constants.GameConstants.MatchPhase.IN_GAME
            "result" -> com.gameai.common.constants.GameConstants.MatchPhase.RESULT
            else -> com.gameai.common.constants.GameConstants.MatchPhase.LOBBY
        }

        // 分路
        result.position = when (data.optString("position", "")) {
            "对抗路", "上单" -> com.gameai.common.constants.GameConstants.GamePosition.TOP
            "中路", "中单" -> com.gameai.common.constants.GameConstants.GamePosition.MID
            "发育路", "射手", "下路" -> com.gameai.common.constants.GameConstants.GamePosition.ADC
            "游走", "辅助" -> com.gameai.common.constants.GameConstants.GamePosition.SUPPORT
            "打野" -> com.gameai.common.constants.GameConstants.GamePosition.JUNGLE
            else -> com.gameai.common.constants.GameConstants.GamePosition.MID
        }

        return result
    }

    /**
     * 完成第一个等待的请求
     */
    private fun completeFirstRequest(result: OcrStructuredResult?) {
        if (pendingRequests.isEmpty()) return
        val firstKey = pendingRequests.keys.firstOrNull() ?: return
        val deferred = pendingRequests.remove(firstKey)
        deferred?.complete(result)
    }

    /**
     * 压缩Bitmap为JPEG
     */
    private fun compressBitmap(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
        return stream.toByteArray()
    }

    /**
     * 是否已连接
     */
    fun isConnected(): Boolean = _status.value == STATUS_CONNECTED
}
